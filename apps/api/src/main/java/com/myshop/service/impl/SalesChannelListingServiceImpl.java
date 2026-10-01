package com.myshop.service.impl;

import com.myshop.constant.ErrorCode;
import com.myshop.constant.SalesChannelListingDesiredState;
import com.myshop.constant.SalesChannelListingManagementMode;
import com.myshop.entity.Product;
import com.myshop.entity.SalesChannelListing;
import com.myshop.exception.BusinessException;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.SalesChannelAccountRepository;
import com.myshop.repository.SalesChannelListingRepository;
import com.myshop.repository.SalesChannelOperationRepository;
import com.myshop.repository.SalesChannelListingGenerationRepository;
import com.myshop.service.SalesChannelListingManagementPolicy;
import com.myshop.constant.SalesChannelOperationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SalesChannelListingServiceImpl {

    private final SalesChannelAccountRepository accountRepository;
    private final ProductRepository productRepository;
    private final SalesChannelListingRepository listingRepository;
    private final SalesChannelOperationRepository operationRepository;
    private final SalesChannelListingGenerationRepository generationRepository;

    @Transactional
    public SalesChannelListing ensureDraft(UUID accountId, UUID productId) {
        accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Sales channel account does not exist"));
        Product product = productRepository.findByIdForUpdate(productId)
                .filter(candidate -> candidate.getIsDeleted() == 0)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "商品不存在"));

        SalesChannelListing listing = listingRepository
                .findBySalesChannelAccountIdAndProductId(accountId, product.getId())
                .orElseGet(() -> {
                    SalesChannelListing created = new SalesChannelListing();
                    created.setSalesChannelAccountId(accountId);
                    created.setProductId(product.getId());
                    return listingRepository.saveAndFlush(created);
                });

        return listing;
    }

    @Transactional
    public SalesChannelListing updateProviderConfig(UUID listingId, Map<String, Object> providerConfig) {
        SalesChannelListing listing = lockListing(listingId);
        requireNoCandidate(listing);
        Map<String, Object> normalized = providerConfig == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(providerConfig);
        if (Objects.equals(listing.getProviderConfig(), normalized)) return listing;

        listing.setProviderConfig(normalized);
        if (!SalesChannelListingManagementPolicy.providerWritesAllowed(listing)) {
            return listingRepository.saveAndFlush(listing);
        }
        listing.incrementDesiredRevision();
        return saveRevisionAndSupersedePending(listing);
    }

    @Transactional
    public SalesChannelListing setDesiredState(UUID listingId, SalesChannelListingDesiredState desiredState) {
        Objects.requireNonNull(desiredState, "desiredState is required");
        SalesChannelListing listing = lockListing(listingId);
        requireNoCandidate(listing);
        SalesChannelListingManagementPolicy.requireProviderWritesAllowed(listing);
        if (listing.getDesiredState() == desiredState) return listing;

        listing.setDesiredState(desiredState);
        listing.incrementDesiredRevision();
        return saveRevisionAndSupersedePending(listing);
    }

    private SalesChannelListing lockListing(UUID listingId) {
        return listingRepository.findByIdForUpdate(listingId)
                .orElseThrow(() -> new IllegalArgumentException("Sales channel listing does not exist"));
    }

    private void requireNoCandidate(SalesChannelListing listing) {
        if (generationRepository.findCandidateByListingId(listing.getId()).isPresent()) {
            throw new IllegalStateException("REPLACEMENT_PENDING: candidate generation activation is required first");
        }
    }

    private SalesChannelListing saveRevisionAndSupersedePending(SalesChannelListing listing) {
        listingRepository.saveAndFlush(listing);
        operationRepository.supersedePendingBeforeRevision(
                listing.getId(), listing.getDesiredRevision(),
                SalesChannelOperationStatus.PENDING, SalesChannelOperationStatus.SUPERSEDED,
                LocalDateTime.now());
        return listing;
    }
}
