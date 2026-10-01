package com.myshop.service;

import com.myshop.constant.SalesChannelProvider;
import com.myshop.constant.SalesChannelListingManagementMode;
import com.myshop.entity.Product;
import com.myshop.entity.ProductSpec;
import com.myshop.entity.SalesChannelAccount;
import com.myshop.entity.SalesChannelListing;
import com.myshop.entity.SalesChannelListingGeneration;
import com.myshop.entity.SalesChannelListingGenerationSpec;
import com.myshop.exception.SalesChannelSpecMappingException;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.ProductSpecRepository;
import com.myshop.repository.SalesChannelAccountRepository;
import com.myshop.repository.SalesChannelListingRepository;
import com.myshop.repository.SalesChannelListingGenerationRepository;
import com.myshop.repository.SalesChannelListingGenerationSpecRepository;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.ListingSpecMappingSummary;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.SpecMappingSyncResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Transaction boundaries for spec backfill; no transaction spans Agent HTTP. */
@Service
@RequiredArgsConstructor
public class SalesChannelSpecMappingPersistence {

    private final SalesChannelAccountRepository accountRepository;
    private final ProductRepository productRepository;
    private final ProductSpecRepository productSpecRepository;
    private final SalesChannelListingRepository listingRepository;
    private final SalesChannelListingGenerationRepository generationRepository;
    private final SalesChannelListingGenerationSpecRepository generationSpecRepository;
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
        SalesChannelListingGeneration generation = generationRepository
                .findCurrentByListingId(listing.getId())
                .orElseThrow(() -> failure("EXTERNAL_ITEM_REQUIRED", "The listing has no current provider generation"));
        Product product = productRepository.findById(listing.getProductId())
                .orElseThrow(() -> failure("PRODUCT_NOT_FOUND", "Product does not exist"));
        if (!product.isSpecEnabled()) {
            throw failure("SPEC_MAPPING_NOT_REQUIRED", "Specification backfill requires a spec-enabled product");
        }
        List<SalesChannelSpecMappingValidator.ProductSpecSnapshot> productSpecs = productSpecRepository
                .findByProductIdAndIsDeletedOrderBySortOrderAsc(product.getId(), 0).stream()
                .map(SalesChannelSpecMappingValidator::snapshot)
                .toList();
        List<SalesChannelSpecMappingValidator.ExistingMappingSnapshot> existingMappings = generationSpecRepository
                .findBySalesChannelListingGenerationIdOrderByCreatedAtAsc(generation.getId()).stream()
                .map(SalesChannelSpecMappingValidator::snapshot)
                .toList();
        return new ListingSpecSyncSnapshot(listing.getId(), generation.getId(), listing.getSalesChannelAccountId(),
                account.getProviderAccountId(), listing.getProductId(), generation.getExternalItemId(),
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
        SalesChannelListingGeneration generation = generationRepository
                .findCurrentByListingIdForUpdate(snapshot.listingId())
                .filter(candidate -> candidate.getId().equals(snapshot.generationId()))
                .orElseThrow(() -> failure("LISTING_CHANGED_DURING_SYNC", "The current provider generation changed"));
        List<SalesChannelListingGenerationSpec> lockedRows = generationSpecRepository
                .findBySalesChannelListingGenerationIdForUpdate(snapshot.generationId());

        SalesChannelAccount account = accountRepository.findById(listing.getSalesChannelAccountId())
                .orElseThrow(() -> failure("ACCOUNT_NOT_FOUND", "Sales channel account does not exist"));
        Product product = productRepository.findById(listing.getProductId())
                .orElseThrow(() -> failure("PRODUCT_NOT_FOUND", "Product does not exist"));
        return applyLocked(snapshot, resolved, account, product, listing, generation, lockedRows);
    }

    /**
     * Applies already-resolved provider identities while the caller owns the
     * Account -> Product -> Listing/ListingSpec lock order.  The ordinary
     * spec-sync path and takeover path both use this method so they cannot
     * drift into different conflict or persistence rules.
     */
    public SpecMappingSyncResult applyLocked(
            ListingSpecSyncSnapshot snapshot,
            List<SalesChannelSpecMappingValidator.ResolvedSpecMapping> resolved,
            SalesChannelAccount account,
            Product product,
            SalesChannelListing listing,
            SalesChannelListingGeneration generation,
            List<SalesChannelListingGenerationSpec> lockedRows) {
        if (account.getProvider() != SalesChannelProvider.XIANYU
                || !Objects.equals(account.getId(), snapshot.accountId())
                || !Objects.equals(account.getProviderAccountId(), snapshot.providerAccountId())
                || !Objects.equals(listing.getProductId(), snapshot.productId())
                || !Objects.equals(generation.getExternalItemId(), snapshot.externalItemId())
                || !Objects.equals(generation.getSalesChannelListingId(), snapshot.listingId())
                || !Objects.equals(generation.getSalesChannelAccountId(), snapshot.accountId())
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

        Map<UUID, SalesChannelListingGenerationSpec> rowsBySpec = validateResolvedMappings(currentSpecs, lockedRows, resolved);

        for (SalesChannelSpecMappingValidator.ResolvedSpecMapping mapping : resolved) {
            SalesChannelListingGenerationSpec row = rowsBySpec.get(mapping.productSpecId());
            Map<String, Object> providerMapping = new LinkedHashMap<>(
                    row.getProviderMapping() == null ? Map.of() : row.getProviderMapping());
            providerMapping.put("provider_spec_name", mapping.providerSpecName());
            providerMapping.put("provider_spec_value", mapping.providerSpecValue());
            row.setProviderMapping(providerMapping);
            if (mapping.externalSkuId() != null) {
                row.setExternalSkuId(mapping.externalSkuId());
            }
        }
        generationSpecRepository.saveAllAndFlush(lockedRows);

        List<ListingSpecMappingSummary> summaries = resolved.stream()
                .map(mapping -> new ListingSpecMappingSummary(mapping.productSpecId().toString(),
                        mapping.productSpecName(), mapping.providerSpecName(), mapping.providerSpecValue(),
                        mapping.externalSkuId()))
                .toList();
        return new SpecMappingSyncResult(listing.getId().toString(), generation.getExternalItemId(),
                summaries.size(), summaries);
    }

    private Map<UUID, SalesChannelListingGenerationSpec> validateResolvedMappings(
            List<SalesChannelSpecMappingValidator.ProductSpecSnapshot> currentSpecs,
            List<SalesChannelListingGenerationSpec> lockedRows,
            List<SalesChannelSpecMappingValidator.ResolvedSpecMapping> resolved) {
        if (resolved == null) {
            throw failure("SPEC_MAPPING_INCOMPLETE", "Resolved provider specification mappings are required");
        }
        Set<UUID> activeSpecIds = currentSpecs.stream()
                .map(SalesChannelSpecMappingValidator.ProductSpecSnapshot::id)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        Map<UUID, SalesChannelListingGenerationSpec> rowsBySpec = new LinkedHashMap<>();
        for (SalesChannelListingGenerationSpec row : lockedRows) {
            if (!activeSpecIds.contains(row.getProductSpecId())) {
                throw failure("SPEC_MAPPING_INCOMPLETE",
                        "A listing specification row does not belong to an active product specification");
            }
            if (rowsBySpec.put(row.getProductSpecId(), row) != null) {
                throw failure("SPEC_MAPPING_INCOMPLETE", "A listing specification row is duplicated");
            }
        }
        if (rowsBySpec.size() != activeSpecIds.size() || resolved.size() != activeSpecIds.size()) {
            throw failure("SPEC_MAPPING_INCOMPLETE",
                    "Every active product specification needs exactly one listing mapping row");
        }
        Set<UUID> resolvedSpecIds = new HashSet<>();
        Set<String> externalSkuIds = new HashSet<>();
        for (SalesChannelSpecMappingValidator.ResolvedSpecMapping mapping : resolved) {
            if (!activeSpecIds.contains(mapping.productSpecId())
                    || !resolvedSpecIds.add(mapping.productSpecId())) {
                throw failure("SPEC_MAPPING_INCOMPLETE",
                        "Resolved provider specification mappings must cover active specs exactly once");
            }
            SalesChannelListingGenerationSpec row = rowsBySpec.get(mapping.productSpecId());
            if (row.getExternalSkuId() != null && mapping.externalSkuId() != null
                    && !row.getExternalSkuId().equals(mapping.externalSkuId())) {
                throw failure("EXTERNAL_SKU_ID_CONFLICT", "An existing external SKU identity conflicts with the provider");
            }
            if (mapping.externalSkuId() != null && !externalSkuIds.add(mapping.externalSkuId())) {
                throw failure("SPEC_MAPPING_INCOMPLETE", "External SKU identities must be unique");
            }
            if (mapping.providerSpecName() == null || mapping.providerSpecName().isBlank()
                    || mapping.providerSpecValue() == null || mapping.providerSpecValue().isBlank()) {
                throw failure("PROVIDER_SPEC_MAPPING_CONFLICT", "Provider specification mapping is incomplete");
            }
        }
        if (resolvedSpecIds.size() != activeSpecIds.size()) {
            throw failure("SPEC_MAPPING_INCOMPLETE",
                    "Resolved provider specification mappings must cover every active product specification");
        }
        return rowsBySpec;
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
            UUID generationId,
            UUID accountId,
            String providerAccountId,
            UUID productId,
            String externalItemId,
            SalesChannelListingManagementMode managementMode,
            long desiredRevision,
            List<SalesChannelSpecMappingValidator.ProductSpecSnapshot> productSpecs,
            List<SalesChannelSpecMappingValidator.ExistingMappingSnapshot> existingMappings) { }
}
