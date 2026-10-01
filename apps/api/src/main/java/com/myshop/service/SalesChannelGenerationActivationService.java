package com.myshop.service;

import com.myshop.constant.SalesChannelGenerationState;
import com.myshop.constant.SalesChannelListingDesiredState;
import com.myshop.constant.SalesChannelListingManagementMode;
import com.myshop.constant.SalesChannelListingStatus;
import com.myshop.constant.SalesChannelOperationStatus;
import com.myshop.constant.SalesChannelOperationType;
import com.myshop.entity.Product;
import com.myshop.entity.ProductSpec;
import com.myshop.entity.SalesChannelListing;
import com.myshop.entity.SalesChannelListingGeneration;
import com.myshop.entity.SalesChannelListingGenerationSpec;
import com.myshop.entity.SalesChannelOperation;
import com.myshop.exception.SalesChannelSpecMappingException;
import com.myshop.repository.ProductSpecRepository;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.SalesChannelAccountRepository;
import com.myshop.repository.SalesChannelListingGenerationRepository;
import com.myshop.repository.SalesChannelListingGenerationSpecRepository;
import com.myshop.repository.SalesChannelListingRepository;
import com.myshop.repository.SalesChannelOperationRepository;
import com.myshop.saleschannel.model.SalesChannelDtos.ListingSpecMappingsData;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The only local transaction that turns a provider result into a CURRENT
 * generation.  Provider reads happen before this method; no HTTP call is made
 * while its locks are held.
 */
@Service
@RequiredArgsConstructor
public class SalesChannelGenerationActivationService {

    private final SalesChannelOperationRepository operationRepository;
    private final SalesChannelListingRepository listingRepository;
    private final SalesChannelListingGenerationRepository generationRepository;
    private final SalesChannelListingGenerationSpecRepository generationSpecRepository;
    private final ProductSpecRepository productSpecRepository;
    private final SalesChannelAccountRepository accountRepository;
    private final ProductRepository productRepository;
    private final SalesChannelSpecMappingValidator mappingValidator;

    @Transactional
    public void persistCandidateMappings(UUID operationId, ListingSpecMappingsData provider) {
        SalesChannelOperation operation = operationRepository.findByIdForUpdate(operationId)
                .orElseThrow(() -> new IllegalArgumentException("Sales channel operation does not exist"));
        UUID candidateId = operation.getResultGenerationId();
        if (candidateId == null || provider == null) throw mappingFailure("REPLACEMENT_MAPPING_ERROR", "Candidate mapping data is missing");
        SalesChannelListing listing = listingRepository.findByIdForUpdate(operation.getSalesChannelListingId())
                .orElseThrow(() -> new IllegalArgumentException("Sales channel listing does not exist"));
        if (operation.getStatus() != SalesChannelOperationStatus.SUCCEEDED
                || (operation.getOperationType() != SalesChannelOperationType.PUBLISH
                && operation.getOperationType() != SalesChannelOperationType.REPUBLISH)) {
            throw mappingFailure("REPLACEMENT_MAPPING_ERROR",
                    "Candidate mapping can only be persisted for a succeeded publish operation");
        }
        if (listing.getManagementMode() != SalesChannelListingManagementMode.MANAGED) {
            throw mappingFailure("REPLACEMENT_MAPPING_ERROR",
                    "Candidate activation requires a MyShop-managed listing");
        }
        SalesChannelListingGeneration candidate = generationRepository.findById(candidateId)
                .orElseThrow(() -> new IllegalStateException("Candidate generation is missing"));
        if (candidate.getGenerationState() != SalesChannelGenerationState.CANDIDATE
                || !Objects.equals(candidate.getSalesChannelListingId(), listing.getId())
                || !Objects.equals(candidate.getSalesChannelAccountId(), listing.getSalesChannelAccountId())
                || !Objects.equals(provider.itemId(), candidate.getExternalItemId())) {
            throw mappingFailure("REPLACEMENT_MAPPING_ERROR", "Provider mapping identity does not match the candidate");
        }
        List<ProductSpec> active = productSpecRepository.findByProductIdAndIsDeletedOrderBySortOrderAsc(listing.getProductId(), 0);
        Product product = productRepository.findById(listing.getProductId())
                .orElseThrow(() -> mappingFailure("REPLACEMENT_MAPPING_ERROR", "Product does not exist"));
        requireCostReadiness(product, active);
        List<SalesChannelListingGenerationSpec> existing = generationSpecRepository
                .findBySalesChannelListingGenerationIdForUpdate(candidateId);
        if (active.isEmpty()) {
            if (!Boolean.TRUE.equals(provider.specDimensionsVerified())
                    || (provider.specifications() != null && !provider.specifications().isEmpty())
                    || (provider.skuRows() != null && !provider.skuRows().isEmpty())) {
                throw mappingFailure("REPLACEMENT_MAPPING_ERROR", "Provider did not prove a single-SKU candidate");
            }
            return;
        }
        List<SalesChannelSpecMappingValidator.ProductSpecSnapshot> activeSnapshots = active.stream()
                .map(SalesChannelSpecMappingValidator::snapshot)
                .toList();
        Map<UUID, SalesChannelListingGenerationSpec> bySpec = new LinkedHashMap<>();
        existing.forEach(row -> {
            if (bySpec.put(row.getProductSpecId(), row) != null) {
                throw mappingFailure("REPLACEMENT_MAPPING_ERROR", "Candidate specification mappings are duplicated");
            }
        });
        for (ProductSpec spec : active) {
            SalesChannelListingGenerationSpec row = bySpec.get(spec.getId());
            if (row == null) {
                row = new SalesChannelListingGenerationSpec();
                row.setSalesChannelListingGenerationId(candidateId);
                row.setProductSpecId(spec.getId());
                bySpec.put(spec.getId(), row);
            }
        }
        List<SalesChannelSpecMappingValidator.ResolvedSpecMapping> resolved;
        try {
            resolved = mappingValidator.resolve(activeSnapshots,
                    bySpec.values().stream().map(SalesChannelSpecMappingValidator::snapshot).toList(), provider);
        } catch (SalesChannelSpecMappingException exception) {
            throw exception;
        }
        Map<UUID, SalesChannelListingGenerationSpec> resolvedRows = new LinkedHashMap<>(bySpec);
        for (SalesChannelSpecMappingValidator.ResolvedSpecMapping mapping : resolved) {
            SalesChannelListingGenerationSpec row = resolvedRows.get(mapping.productSpecId());
            row.setExternalSkuId(mapping.externalSkuId());
            Map<String, Object> providerMapping = new LinkedHashMap<>();
            providerMapping.put("provider_spec_name", mapping.providerSpecName());
            providerMapping.put("provider_spec_value", mapping.providerSpecValue());
            row.setProviderMapping(providerMapping);
        }
        generationSpecRepository.saveAllAndFlush(bySpec.values());
    }

    @Transactional
    public boolean activate(UUID operationId) {
        SalesChannelOperation operationSnapshot = operationRepository.findById(operationId)
                .orElseThrow(() -> new IllegalArgumentException("Sales channel operation does not exist"));
        SalesChannelListing listingSnapshot = listingRepository.findById(operationSnapshot.getSalesChannelListingId())
                .orElseThrow(() -> new IllegalArgumentException("Sales channel listing does not exist"));
        accountRepository.findByIdForUpdate(listingSnapshot.getSalesChannelAccountId())
                .orElseThrow(() -> new IllegalArgumentException("Sales channel account does not exist"));
        Product product = productRepository.findByIdForUpdate(listingSnapshot.getProductId())
                .orElseThrow(() -> new IllegalArgumentException("Product does not exist"));
        SalesChannelListing listing = listingRepository.findByIdForUpdate(listingSnapshot.getId())
                .orElseThrow(() -> new IllegalArgumentException("Sales channel listing does not exist"));
        SalesChannelOperation operation = operationRepository.findByIdForUpdate(operationId)
                .orElseThrow(() -> new IllegalArgumentException("Sales channel operation does not exist"));
        if (operation.getStatus() != SalesChannelOperationStatus.SUCCEEDED
                || (operation.getOperationType() != SalesChannelOperationType.PUBLISH
                && operation.getOperationType() != SalesChannelOperationType.REPUBLISH)) {
            return false;
        }
        if (listing.getManagementMode() != SalesChannelListingManagementMode.MANAGED) {
            return markMappingError(listing, "Candidate activation requires a MyShop-managed listing");
        }
        UUID candidateId = operation.getResultGenerationId();
        if (candidateId == null) return false;
        String desiredFingerprint = operation.getPayload().get("desired_fingerprint") == null
                ? null : operation.getPayload().get("desired_fingerprint").toString();
        SalesChannelListingGeneration source = null;
        if (operation.getOperationType() == SalesChannelOperationType.REPUBLISH) {
            UUID sourceId = operation.getTargetGenerationId();
            source = sourceId == null ? null : generationRepository.findByIdForUpdate(sourceId).orElse(null);
            if (source == null || source.getGenerationState() != SalesChannelGenerationState.CURRENT) {
                return markMappingError(listing, "Replacement source generation is not the frozen CURRENT generation");
            }
        } else if (operation.getTargetGenerationId() != null) {
            return markMappingError(listing, "Initial publish cannot have a provider generation target");
        } else if (generationRepository.findCurrentByListingIdForUpdate(listing.getId()).isPresent()) {
            return markMappingError(listing, "Initial publish candidate cannot activate while a CURRENT generation exists");
        }
        SalesChannelListingGeneration candidate = generationRepository.findByIdForUpdate(candidateId)
                .orElseThrow(() -> new IllegalStateException("Candidate generation is missing"));
        if (candidate.getGenerationState() != SalesChannelGenerationState.CANDIDATE
                || !Objects.equals(candidate.getSalesChannelListingId(), listing.getId())
                || !Objects.equals(candidate.getSalesChannelAccountId(), listing.getSalesChannelAccountId())
                || (source != null && !Objects.equals(candidate.getReplacesGenerationId(), source.getId()))
                || listing.getDesiredState() != SalesChannelListingDesiredState.ONLINE
                || operation.getDesiredRevision() != listing.getDesiredRevision()
                || !Objects.equals(desiredFingerprint, listing.getDesiredFingerprint())) {
            return markMappingError(listing, "Candidate generation no longer matches the listing intent");
        }
        List<ProductSpec> activeSpecs = productSpecRepository
                .findByProductIdAndIsDeletedOrderBySortOrderAsc(listing.getProductId(), 0);
        try {
            requireCostReadiness(product, activeSpecs);
        } catch (SalesChannelSpecMappingException exception) {
            return markMappingError(listing, exception.getMessage());
        }
        List<SalesChannelListingGenerationSpec> candidateSpecs = generationSpecRepository
                .findBySalesChannelListingGenerationIdForUpdate(candidate.getId());
        Set<UUID> activeSpecIds = activeSpecs.stream().map(ProductSpec::getId).collect(java.util.stream.Collectors.toSet());
        Set<UUID> candidateSpecIds = candidateSpecs.stream()
                .map(SalesChannelListingGenerationSpec::getProductSpecId)
                .collect(java.util.stream.Collectors.toSet());
        if ((!activeSpecs.isEmpty() && candidateSpecs.size() != activeSpecs.size())
                || !candidateSpecIds.equals(activeSpecIds)) {
            return markMappingError(listing, "Candidate provider specification mappings are incomplete");
        }
        LocalDateTime now = LocalDateTime.now();
        if (source != null) {
            source.setGenerationState(SalesChannelGenerationState.RETIRED);
            source.setRetiredAt(now);
        }
        candidate.setGenerationState(SalesChannelGenerationState.CURRENT);
        candidate.setActivatedAt(now);
        candidate.setRetiredAt(null);
        listing.setStatus(SalesChannelListingStatus.ONLINE);
        listing.advanceAppliedRevision(operation.getDesiredRevision());
        Object fingerprint = operation.getPayload().get("desired_fingerprint");
        listing.setAppliedFingerprint(fingerprint == null ? null : fingerprint.toString());
        listing.setLastSyncedAt(now);
        listing.setLastErrorCode(null);
        listing.setLastErrorMessage(null);
        generationRepository.save(candidate);
        if (source != null) generationRepository.save(source);
        return true;
    }

    /** Records a permanent exact-mapping failure while retaining the candidate for review. */
    @Transactional
    public void markCandidateMappingError(UUID operationId, String code, String message) {
        SalesChannelOperation operation = operationRepository.findById(operationId).orElse(null);
        if (operation == null || operation.getResultGenerationId() == null) return;
        SalesChannelListing listing = listingRepository.findByIdForUpdate(operation.getSalesChannelListingId())
                .orElse(null);
        SalesChannelListingGeneration candidate = generationRepository.findById(operation.getResultGenerationId())
                .orElse(null);
        if (listing == null || candidate == null || candidate.getGenerationState() != SalesChannelGenerationState.CANDIDATE) {
            return;
        }
        markMappingError(listing, message == null || message.isBlank() ? "Candidate provider specification mapping failed" : message);
    }

    private boolean markMappingError(SalesChannelListing listing, String message) {
        listing.setStatus(SalesChannelListingStatus.ERROR);
        listing.setLastErrorCode("REPLACEMENT_MAPPING_ERROR");
        listing.setLastErrorMessage(message);
        return false;
    }

    private SalesChannelSpecMappingException mappingFailure(String code, String message) {
        return new SalesChannelSpecMappingException(code, message);
    }

    private void requireCostReadiness(Product product, List<ProductSpec> activeSpecs) {
        if (activeSpecs.isEmpty()) {
            if (product.isSpecEnabled() || product.getCostPrice() == null) {
                throw mappingFailure("REPLACEMENT_MAPPING_ERROR",
                        "A non-spec product must have a non-null cost price before activation");
            }
            return;
        }
        if (activeSpecs.stream().anyMatch(spec -> spec.getCostPrice() == null)) {
            throw mappingFailure("REPLACEMENT_MAPPING_ERROR",
                    "Every active product specification must have a non-null cost price before activation");
        }
    }
}
