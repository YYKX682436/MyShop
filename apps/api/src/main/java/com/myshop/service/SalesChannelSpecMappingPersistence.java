package com.myshop.service;

import com.myshop.constant.SalesChannelProvider;
import com.myshop.constant.SalesChannelListingManagementMode;
import com.myshop.entity.Product;
import com.myshop.entity.ProductSpec;
import com.myshop.entity.SalesChannelAccount;
import com.myshop.entity.SalesChannelListing;
import com.myshop.entity.SalesChannelListingSpec;
import com.myshop.exception.SalesChannelSpecMappingException;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.ProductSpecRepository;
import com.myshop.repository.SalesChannelAccountRepository;
import com.myshop.repository.SalesChannelListingRepository;
import com.myshop.repository.SalesChannelListingSpecRepository;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.ListingSpecMappingSummary;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.SpecMappingSyncResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Transaction boundaries for spec backfill; no transaction spans Agent HTTP. */
@Service
@RequiredArgsConstructor
public class SalesChannelSpecMappingPersistence {

    private final SalesChannelAccountRepository accountRepository;
    private final ProductRepository productRepository;
    private final ProductSpecRepository productSpecRepository;
    private final SalesChannelListingRepository listingRepository;
    private final SalesChannelListingSpecRepository listingSpecRepository;
    private final SalesChannelSpecMappingValidator validator;

    @Transactional(readOnly = true)
    public ListingSpecSyncSnapshot snapshot(UUID listingId) {
        SalesChannelListing listing = listingRepository.findById(listingId)
                .orElseThrow(() -> failure("LISTING_NOT_FOUND", "Sales channel listing does not exist"));
        SalesChannelAccount account = accountRepository.findById(listing.getSalesChannelAccountId())
                .orElseThrow(() -> failure("ACCOUNT_NOT_FOUND", "Sales channel account does not exist"));
        if (account.getProvider() != SalesChannelProvider.XIANYU) {
            throw failure("UNSUPPORTED_PROVIDER", "Specification backfill is supported only for XIANYU listings");
        }
        if (listing.getExternalItemId() == null || listing.getExternalItemId().isBlank()) {
            throw failure("EXTERNAL_ITEM_REQUIRED", "The listing has no bound external item");
        }
        Product product = productRepository.findById(listing.getProductId())
                .orElseThrow(() -> failure("PRODUCT_NOT_FOUND", "Product does not exist"));
        if (!product.isSpecEnabled()) {
            throw failure("SPEC_MAPPING_NOT_REQUIRED", "Specification backfill requires a spec-enabled product");
        }
        List<SalesChannelSpecMappingValidator.ProductSpecSnapshot> productSpecs = productSpecRepository
                .findByProductIdAndIsDeletedOrderBySortOrderAsc(product.getId(), 0).stream()
                .map(SalesChannelSpecMappingValidator::snapshot)
                .toList();
        List<SalesChannelSpecMappingValidator.ExistingMappingSnapshot> existingMappings = listingSpecRepository
                .findBySalesChannelListingIdOrderByCreatedAtAsc(listing.getId()).stream()
                .map(SalesChannelSpecMappingValidator::snapshot)
                .toList();
        return new ListingSpecSyncSnapshot(listing.getId(), listing.getSalesChannelAccountId(),
                account.getProviderAccountId(), listing.getProductId(), listing.getExternalItemId(),
                listing.getManagementMode(), listing.getDesiredRevision(), productSpecs, existingMappings);
    }

    /**
     * Re-locks in the stable order listing -> listing specs, revalidates all
     * identity/version facts, and only then writes the complete mapping set.
     */
    @Transactional
    public SpecMappingSyncResult apply(
            ListingSpecSyncSnapshot snapshot,
            List<SalesChannelSpecMappingValidator.ResolvedSpecMapping> resolved) {
        SalesChannelListing listing = listingRepository.findByIdForUpdate(snapshot.listingId())
                .orElseThrow(() -> failure("LISTING_NOT_FOUND", "Sales channel listing does not exist"));
        List<SalesChannelListingSpec> lockedRows = listingSpecRepository
                .findBySalesChannelListingIdForUpdate(snapshot.listingId());

        SalesChannelAccount account = accountRepository.findById(listing.getSalesChannelAccountId())
                .orElseThrow(() -> failure("ACCOUNT_NOT_FOUND", "Sales channel account does not exist"));
        Product product = productRepository.findById(listing.getProductId())
                .orElseThrow(() -> failure("PRODUCT_NOT_FOUND", "Product does not exist"));
        if (account.getProvider() != SalesChannelProvider.XIANYU
                || !Objects.equals(account.getId(), snapshot.accountId())
                || !Objects.equals(account.getProviderAccountId(), snapshot.providerAccountId())
                || !Objects.equals(listing.getProductId(), snapshot.productId())
                || !Objects.equals(listing.getExternalItemId(), snapshot.externalItemId())
                || listing.getDesiredRevision() != snapshot.desiredRevision()
                || !product.isSpecEnabled()) {
            throw failure("LISTING_CHANGED_DURING_SYNC", "The listing identity changed while reading provider specifications");
        }

        List<SalesChannelSpecMappingValidator.ProductSpecSnapshot> currentSpecs = productSpecRepository
                .findByProductIdAndIsDeletedOrderBySortOrderAsc(product.getId(), 0).stream()
                .map(SalesChannelSpecMappingValidator::snapshot)
                .toList();
        List<SalesChannelSpecMappingValidator.ExistingMappingSnapshot> currentMappings = lockedRows.stream()
                .map(SalesChannelSpecMappingValidator::snapshot)
                .toList();
        if (!sameProductSpecs(snapshot.productSpecs(), currentSpecs)
                || !sameMappings(snapshot.existingMappings(), currentMappings)) {
            throw failure("LISTING_CHANGED_DURING_SYNC", "The listing specification identity changed during provider read");
        }

        Map<UUID, SalesChannelListingSpec> rowsBySpec = new LinkedHashMap<>();
        for (SalesChannelListingSpec row : lockedRows) {
            if (rowsBySpec.put(row.getProductSpecId(), row) != null) {
                throw failure("SPEC_MAPPING_INCOMPLETE", "A listing specification row is duplicated");
            }
        }
        for (SalesChannelSpecMappingValidator.ResolvedSpecMapping mapping : resolved) {
            SalesChannelListingSpec row = rowsBySpec.get(mapping.productSpecId());
            if (row == null) {
                throw failure("SPEC_MAPPING_INCOMPLETE", "Every active product specification needs a listing mapping row");
            }
            if (row.getExternalSkuId() != null && mapping.externalSkuId() != null
                    && !row.getExternalSkuId().equals(mapping.externalSkuId())) {
                throw failure("EXTERNAL_SKU_ID_CONFLICT", "An existing external SKU identity conflicts with the provider");
            }
        }

        for (SalesChannelSpecMappingValidator.ResolvedSpecMapping mapping : resolved) {
            SalesChannelListingSpec row = rowsBySpec.get(mapping.productSpecId());
            row.setProviderMapping(Map.of(
                    "provider_spec_name", mapping.providerSpecName(),
                    "provider_spec_value", mapping.providerSpecValue()));
            if (mapping.externalSkuId() != null) {
                row.setExternalSkuId(mapping.externalSkuId());
            }
        }
        listingSpecRepository.saveAllAndFlush(lockedRows);

        List<ListingSpecMappingSummary> summaries = resolved.stream()
                .map(mapping -> new ListingSpecMappingSummary(mapping.productSpecId().toString(),
                        mapping.productSpecName(), mapping.providerSpecName(), mapping.providerSpecValue(),
                        mapping.externalSkuId()))
                .toList();
        return new SpecMappingSyncResult(listing.getId().toString(), listing.getExternalItemId(),
                summaries.size(), summaries);
    }

    private boolean sameProductSpecs(
            List<SalesChannelSpecMappingValidator.ProductSpecSnapshot> expected,
            List<SalesChannelSpecMappingValidator.ProductSpecSnapshot> actual) {
        return Objects.equals(expected, actual);
    }

    private boolean sameMappings(
            List<SalesChannelSpecMappingValidator.ExistingMappingSnapshot> expected,
            List<SalesChannelSpecMappingValidator.ExistingMappingSnapshot> actual) {
        Map<UUID, SalesChannelSpecMappingValidator.ExistingMappingSnapshot> expectedById = expected.stream()
                .collect(java.util.stream.Collectors.toMap(
                        SalesChannelSpecMappingValidator.ExistingMappingSnapshot::productSpecId,
                        value -> value, (left, right) -> left, LinkedHashMap::new));
        Map<UUID, SalesChannelSpecMappingValidator.ExistingMappingSnapshot> actualById = actual.stream()
                .collect(java.util.stream.Collectors.toMap(
                        SalesChannelSpecMappingValidator.ExistingMappingSnapshot::productSpecId,
                        value -> value, (left, right) -> left, LinkedHashMap::new));
        return expectedById.equals(actualById);
    }

    private SalesChannelSpecMappingException failure(String code, String message) {
        return new SalesChannelSpecMappingException(code, message);
    }

    public record ListingSpecSyncSnapshot(
            UUID listingId,
            UUID accountId,
            String providerAccountId,
            UUID productId,
            String externalItemId,
            SalesChannelListingManagementMode managementMode,
            long desiredRevision,
            List<SalesChannelSpecMappingValidator.ProductSpecSnapshot> productSpecs,
            List<SalesChannelSpecMappingValidator.ExistingMappingSnapshot> existingMappings) { }
}
