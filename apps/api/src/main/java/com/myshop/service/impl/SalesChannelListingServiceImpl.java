package com.myshop.service.impl;

import com.myshop.constant.ErrorCode;
import com.myshop.entity.Product;
import com.myshop.entity.SalesChannelListing;
import com.myshop.exception.BusinessException;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.SalesChannelAccountRepository;
import com.myshop.repository.SalesChannelListingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Draft identity creation only; desired-state mutations go through SalesChannelDesiredStatePlanner. */
@Service
@RequiredArgsConstructor
public class SalesChannelListingServiceImpl {

    private final SalesChannelAccountRepository accountRepository;
    private final ProductRepository productRepository;
    private final SalesChannelListingRepository listingRepository;

    @Transactional
    public SalesChannelListing ensureDraft(UUID accountId, UUID productId) {
        accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Sales channel account does not exist"));
        Product product = productRepository.findByIdForUpdate(productId)
                .filter(candidate -> candidate.getIsDeleted() == 0)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "商品不存在"));

        return listingRepository.findBySalesChannelAccountIdAndProductId(accountId, product.getId())
                .orElseGet(() -> {
                    SalesChannelListing created = new SalesChannelListing();
                    created.setSalesChannelAccountId(accountId);
                    created.setProductId(product.getId());
                    return listingRepository.saveAndFlush(created);
                });
    }
}
