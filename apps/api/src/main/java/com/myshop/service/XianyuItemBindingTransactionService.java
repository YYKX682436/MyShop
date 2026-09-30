package com.myshop.service;

import com.myshop.constant.ErrorCode;
import com.myshop.constant.SalesChannelListingDesiredState;
import com.myshop.constant.SalesChannelListingManagementMode;
import com.myshop.constant.SalesChannelListingStatus;
import com.myshop.constant.SalesChannelProvider;
import com.myshop.entity.Product;
import com.myshop.entity.SalesChannelAccount;
import com.myshop.entity.SalesChannelListing;
import com.myshop.entity.SalesChannelListingGeneration;
import com.myshop.entity.SalesChannelListingGenerationSpec;
import com.myshop.exception.BusinessException;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.ProductSpecRepository;
import com.myshop.repository.SalesChannelAccountRepository;
import com.myshop.repository.SalesChannelListingRepository;
import com.myshop.repository.SalesChannelListingGenerationRepository;
import com.myshop.repository.SalesChannelListingGenerationSpecRepository;
import com.myshop.repository.SalesChannelOperationRepository;
import com.myshop.repository.SalesChannelOrderRepository;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.XianyuItemBindingResponse;
import com.myshop.service.impl.SalesChannelListingServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Local Phase 2B transition. Network calls must be completed before entry. */
@Service
@RequiredArgsConstructor
public class XianyuItemBindingTransactionService {

    private final SalesChannelAccountRepository accountRepository;
    private final ProductRepository productRepository;
    private final ProductSpecRepository productSpecRepository;
    private final SalesChannelListingRepository listingRepository;
    private final SalesChannelListingGenerationRepository generationRepository;
    private final SalesChannelListingGenerationSpecRepository generationSpecRepository;
    private final SalesChannelOperationRepository operationRepository;
    private final SalesChannelOrderRepository orderRepository;
    private final SalesChannelListingServiceImpl listingService;

    @Transactional
    public XianyuItemBindingResponse link(UUID accountId, String itemId, UUID productId) {
        // Deliberately keep this lock order: account first, product second.
        SalesChannelAccount account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "闲鱼账号不存在", HttpStatus.NOT_FOUND));
        if (account.getProvider() != SalesChannelProvider.XIANYU || !account.isEnabled()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "闲鱼账号不可用于关联");
        }

        Product product = productRepository.findByIdForUpdate(productId)
                .filter(candidate -> candidate.getIsDeleted() == 0)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND,
                        "商品不存在", HttpStatus.NOT_FOUND));

        SalesChannelListingGeneration itemGeneration = generationRepository
                .findBySalesChannelAccountIdAndExternalItemId(accountId, itemId)
                .orElse(null);
        SalesChannelListing productListing = listingRepository
                .findBySalesChannelAccountIdAndProductId(accountId, productId)
                .orElse(null);

        if (itemGeneration != null) {
            SalesChannelListing itemListing = listingRepository.findById(itemGeneration.getSalesChannelListingId())
                    .orElseThrow(() -> new IllegalStateException("Generation listing is missing"));
            if (!productId.equals(itemListing.getProductId())) {
                throw conflict(ErrorCode.ITEM_ALREADY_LINKED,
                        "该闲鱼商品已关联其它 MyShop 商品");
            }
            return response(itemId, itemListing, product, "ALREADY_LINKED");
        }

        if (productListing != null) {
            if (generationRepository.findBySalesChannelListingIdAndCurrentTrue(productListing.getId()).isPresent()) {
                throw conflict(ErrorCode.PRODUCT_ALREADY_LINKED,
                        "该 MyShop 商品已关联其它闲鱼商品");
            }
            if (!isSafeNullIdentityListing(productListing)) {
                throw conflict(ErrorCode.LISTING_NOT_LINKABLE,
                        "该 MyShop 渠道 listing 当前不可复用作人工关联");
            }
            productListing.setManagementMode(SalesChannelListingManagementMode.LINKED_ONLY);
            SalesChannelListing saved = listingRepository.saveAndFlush(productListing);
            createGeneration(saved, itemId, product);
            return response(itemId, saved, product, "LINKED");
        }

        SalesChannelListing created = listingService.ensureDraft(accountId, productId);
        created.setManagementMode(SalesChannelListingManagementMode.LINKED_ONLY);
        SalesChannelListing saved = listingRepository.saveAndFlush(created);
        createGeneration(saved, itemId, product);
        return response(itemId, saved, product, "LINKED");
    }

    private SalesChannelListingGeneration createGeneration(SalesChannelListing listing, String itemId,
                                                           Product product) {
        SalesChannelListingGeneration generation = new SalesChannelListingGeneration(
                listing.getId(), listing.getSalesChannelAccountId(),
                generationRepository.nextGenerationNumber(listing.getId()), itemId, true, "LINK");
        generation.setActivatedAt(java.time.LocalDateTime.now());
        SalesChannelListingGeneration saved = generationRepository.saveAndFlush(generation);
        productSpecRepository.findByProductIdAndIsDeletedOrderBySortOrderAsc(product.getId(), 0).forEach(spec -> {
            SalesChannelListingGenerationSpec mapping = new SalesChannelListingGenerationSpec();
            mapping.setSalesChannelListingGenerationId(saved.getId());
            mapping.setProductSpecId(spec.getId());
            generationSpecRepository.save(mapping);
        });
        generationSpecRepository.flush();
        return saved;
    }

    private boolean isSafeNullIdentityListing(SalesChannelListing listing) {
        if (listing.isEnabled() || listing.isAutoFulfillmentEnabled()) return false;
        if (listing.getDesiredState() != SalesChannelListingDesiredState.OFFLINE) return false;
        if (listing.getStatus() != SalesChannelListingStatus.DRAFT
                && listing.getStatus() != SalesChannelListingStatus.OFFLINE) return false;
        if (operationRepository.existsBySalesChannelListingId(listing.getId())) return false;
        return !orderRepository.existsBySalesChannelListingId(listing.getId());
    }

    private XianyuItemBindingResponse response(String itemId, SalesChannelListing listing,
                                                Product product, String result) {
        return new XianyuItemBindingResponse(itemId, listing.getId().toString(),
                product.getId().toString(), product.getTitle(),
                listing.getManagementMode().name(), result);
    }

    private BusinessException conflict(int code, String message) {
        return new BusinessException(code, message, HttpStatus.CONFLICT);
    }
}
