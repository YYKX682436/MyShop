package com.myshop.service;

import com.myshop.constant.ErrorCode;
import com.myshop.constant.SalesChannelAccountStatus;
import com.myshop.constant.SalesChannelListingDesiredState;
import com.myshop.constant.SalesChannelListingManagementMode;
import com.myshop.constant.SalesChannelListingStatus;
import com.myshop.constant.SalesChannelProvider;
import com.myshop.entity.Product;
import com.myshop.entity.ProductSpec;
import com.myshop.entity.SalesChannelAccount;
import com.myshop.entity.SalesChannelListing;
import com.myshop.entity.SalesChannelListingSpec;
import com.myshop.exception.BusinessException;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.ProductSpecRepository;
import com.myshop.repository.SalesChannelAccountRepository;
import com.myshop.repository.SalesChannelListingRepository;
import com.myshop.repository.SalesChannelListingSpecRepository;
import com.myshop.repository.SalesChannelOperationRepository;
import com.myshop.repository.SalesChannelOrderRepository;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.XianyuTakeoverResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Local lock boundaries for the Phase 2C ownership handoff. */
@Service
@RequiredArgsConstructor
public class XianyuItemTakeoverTransactionService {

    private final SalesChannelAccountRepository accountRepository;
    private final ProductRepository productRepository;
    private final ProductSpecRepository productSpecRepository;
    private final SalesChannelListingRepository listingRepository;
    private final SalesChannelListingSpecRepository listingSpecRepository;
    private final SalesChannelOperationRepository operationRepository;
    private final SalesChannelOrderRepository orderRepository;
    private final SalesChannelSpecMappingPersistence mappingPersistence;

    /**
     * Locks Account -> Product -> Listing/ListingSpecs and only then moves a
     * LINKED_ONLY row to TAKEOVER_PENDING.  No provider operation is queued.
     */
    @Transactional
    public BeginResult begin(TakeoverPreflight preflight) {
        SalesChannelAccount account = lockAccount(preflight.accountId());
        requireAccount(account);
        Product product = lockProduct(preflight.productId());
        requireProduct(product, preflight);
        SalesChannelListing listing = lockListing(preflight.listingId());
        List<SalesChannelListingSpec> mappings = listingSpecRepository
                .findBySalesChannelListingIdForUpdate(preflight.listingId());

        if (listing.getManagementMode() == SalesChannelListingManagementMode.MANAGED) {
            return new BeginResult(SalesChannelListingManagementMode.MANAGED, false);
        }
        if (listing.getManagementMode() != preflight.managementMode()) {
            throw failure(ErrorCode.TAKEOVER_NOT_ALLOWED,
                    "接管期间 listing management mode 发生变化");
        }
        validateIdentity(preflight, account, product, listing, mappings, preflight.mappings());
        if (preflight.specEnabled()) {
            mappingPersistence.applyLocked(
                    preflight.mappingSnapshot(preflight.providerAccountId()),
                    preflight.resolvedMappings(), account, product, listing, mappings);
        }
        if (listing.getManagementMode() == SalesChannelListingManagementMode.TAKEOVER_PENDING) {
            return new BeginResult(SalesChannelListingManagementMode.TAKEOVER_PENDING, false);
        }
        if (operationRepository.existsBySalesChannelListingId(listing.getId())
                || orderRepository.existsBySalesChannelListingId(listing.getId())) {
            throw failure(ErrorCode.TAKEOVER_NOT_ALLOWED,
                    "初次接管要求 LINKED_ONLY listing 没有历史渠道操作或订单");
        }
        listing.setManagementMode(SalesChannelListingManagementMode.TAKEOVER_PENDING);
        listingRepository.saveAndFlush(listing);
        return new BeginResult(SalesChannelListingManagementMode.TAKEOVER_PENDING, true);
    }

    @Transactional
    public XianyuTakeoverResponse finalizeTakeover(TakeoverPreflight preflight) {
        SalesChannelAccount account = lockAccount(preflight.accountId());
        requireAccountIdentity(account, preflight);
        Product product = lockProduct(preflight.productId());
        requireProduct(product, preflight);
        SalesChannelListing listing = lockListing(preflight.listingId());
        List<SalesChannelListingSpec> mappings = listingSpecRepository
                .findBySalesChannelListingIdForUpdate(preflight.listingId());
        validateIdentity(preflight, account, product, listing, mappings, preflight.persistedMappings());

        if (listing.getManagementMode() == SalesChannelListingManagementMode.MANAGED) {
            return response(preflight.itemId(), listing, product, "ALREADY_MANAGED");
        }
        if (listing.getManagementMode() != SalesChannelListingManagementMode.TAKEOVER_PENDING) {
            throw failure(ErrorCode.TAKEOVER_NOT_ALLOWED,
                    "只有 TAKEOVER_PENDING listing 才能完成接管");
        }
        listing.setManagementMode(SalesChannelListingManagementMode.MANAGED);
        listing.setStatus(SalesChannelListingStatus.DRAFT);
        listing.setDesiredState(SalesChannelListingDesiredState.OFFLINE);
        listing.setEnabled(false);
        listing.setAutoFulfillmentEnabled(false);
        listing.setLastErrorCode(null);
        listing.setLastErrorMessage(null);
        SalesChannelListing saved = listingRepository.saveAndFlush(listing);
        return response(preflight.itemId(), saved, product, "TAKEOVER_COMPLETED");
    }

    /** Keep a failed/unknown claim pending; never roll the ownership state back. */
    @Transactional
    public void recordClaimFailure(TakeoverPreflight preflight, String code, String message) {
        SalesChannelAccount account = lockAccount(preflight.accountId());
        requireAccountIdentity(account, preflight);
        Product product = lockProduct(preflight.productId());
        requireProduct(product, preflight);
        SalesChannelListing listing = lockListing(preflight.listingId());
        List<SalesChannelListingSpec> mappings = listingSpecRepository
                .findBySalesChannelListingIdForUpdate(preflight.listingId());
        validateIdentity(preflight, account, product, listing, mappings, preflight.persistedMappings());
        if (listing.getManagementMode() == SalesChannelListingManagementMode.TAKEOVER_PENDING) {
            listing.setLastErrorCode(code == null || code.isBlank() ? "TAKEOVER_CLAIM_FAILED" : code);
            listing.setLastErrorMessage(message == null || message.isBlank()
                    ? "The Agent ownership claim did not complete" : message);
            listingRepository.saveAndFlush(listing);
        }
    }

    private SalesChannelAccount lockAccount(UUID accountId) {
        return accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> failure(ErrorCode.NOT_FOUND, "闲鱼账号不存在", HttpStatus.NOT_FOUND));
    }

    private Product lockProduct(UUID productId) {
        return productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> failure(ErrorCode.PRODUCT_NOT_FOUND, "商品不存在", HttpStatus.NOT_FOUND));
    }

    private SalesChannelListing lockListing(UUID listingId) {
        return listingRepository.findByIdForUpdate(listingId)
                .orElseThrow(() -> failure(ErrorCode.TAKEOVER_NOT_ALLOWED, "闲鱼 listing 不存在"));
    }

    private void requireAccount(SalesChannelAccount account) {
        if (account.getProvider() != SalesChannelProvider.XIANYU
                || !account.isEnabled()
                || account.getStatus() != SalesChannelAccountStatus.CONNECTED
                || !"FISH_SHOP".equals(accountType(account))) {
            throw failure(ErrorCode.TAKEOVER_NOT_ALLOWED,
                    "接管要求启用、CONNECTED 且已验证为 FISH_SHOP 的闲鱼账号");
        }
    }

    /**
     * Finalize and failure persistence must still work after a transient Agent
     * auth/status change.  The preflight already proved operational state for
     * a new claim; these phases only require the same XIANYU account identity.
     */
    private void requireAccountIdentity(SalesChannelAccount account, TakeoverPreflight preflight) {
        if (account.getProvider() != SalesChannelProvider.XIANYU
                || !Objects.equals(account.getId(), preflight.accountId())
                || !Objects.equals(account.getProviderAccountId(), preflight.providerAccountId())) {
            throw failure(ErrorCode.TAKEOVER_NOT_ALLOWED, "接管账号身份发生变化");
        }
    }

    private void requireProduct(Product product, TakeoverPreflight preflight) {
        if (product.getIsDeleted() != 0 || !Objects.equals(product.getId(), preflight.productId())) {
            throw failure(ErrorCode.TAKEOVER_NOT_ALLOWED, "接管商品已删除或身份发生变化");
        }
    }

    private void validateIdentity(TakeoverPreflight preflight, SalesChannelAccount account,
                                  Product product, SalesChannelListing listing,
                                  List<SalesChannelListingSpec> mappings,
                                  List<MappingIdentity> expectedMappings) {
        if (!Objects.equals(account.getId(), preflight.accountId())
                || !Objects.equals(account.getProviderAccountId(), preflight.providerAccountId())
                || !Objects.equals(product.getId(), preflight.productId())
                || !Objects.equals(listing.getSalesChannelAccountId(), preflight.accountId())
                || !Objects.equals(listing.getProductId(), preflight.productId())
                || !Objects.equals(listing.getId(), preflight.listingId())
                || !Objects.equals(listing.getExternalItemId(), preflight.itemId())
                || listing.isEnabled() || listing.isAutoFulfillmentEnabled()
                || listing.getDesiredRevision() != preflight.desiredRevision()) {
            throw failure(ErrorCode.TAKEOVER_NOT_ALLOWED,
                    "接管前后 listing、账号、商品或 revision 身份发生变化");
        }
        List<ProductSpecIdentity> currentSpecs = productSpecRepository
                .findByProductIdAndIsDeletedOrderBySortOrderAsc(product.getId(), 0).stream()
                .map(this::specIdentity).toList();
        List<MappingIdentity> currentMappings = mappings.stream()
                .map(this::mappingIdentity).toList();
        if (!Objects.equals(preflight.specs(), currentSpecs)
                || !Objects.equals(expectedMappings, currentMappings)) {
            throw failure(ErrorCode.TAKEOVER_NOT_ALLOWED,
                    "接管期间商品规格或 listing 规格映射发生变化");
        }
    }

    private ProductSpecIdentity specIdentity(ProductSpec spec) {
        return new ProductSpecIdentity(spec.getId(), spec.getName(), spec.getCostPrice());
    }

    private MappingIdentity mappingIdentity(SalesChannelListingSpec mapping) {
        return new MappingIdentity(mapping.getProductSpecId(), mapping.getExternalSkuId(),
                mapping.getProviderMapping() == null
                        ? Map.of() : new LinkedHashMap<>(mapping.getProviderMapping()));
    }

    private String accountType(SalesChannelAccount account) {
        Object type = account.getCapabilities().get("account_type");
        return type == null ? "UNKNOWN" : type.toString().toUpperCase();
    }

    private XianyuTakeoverResponse response(String itemId, SalesChannelListing listing,
                                            Product product, String result) {
        return new XianyuTakeoverResponse(itemId, listing.getId().toString(),
                product.getId().toString(), listing.getManagementMode().name(), result);
    }

    private BusinessException failure(int code, String message) {
        return failure(code, message, HttpStatus.CONFLICT);
    }

    private BusinessException failure(int code, String message, HttpStatus status) {
        return new BusinessException(code, message, status);
    }

    public record BeginResult(SalesChannelListingManagementMode managementMode, boolean changed) { }

    public record TakeoverPreflight(
            UUID accountId,
            UUID listingId,
            UUID productId,
            String itemId,
            String providerAccountId,
            SalesChannelListingManagementMode managementMode,
            long desiredRevision,
            boolean specEnabled,
            List<ProductSpecIdentity> specs,
            List<MappingIdentity> mappings,
            List<SalesChannelSpecMappingValidator.ResolvedSpecMapping> resolvedMappings,
            List<MappingIdentity> persistedMappings) {
        public TakeoverPreflight {
            specs = List.copyOf(specs == null ? List.of() : specs);
            mappings = List.copyOf(mappings == null ? List.of() : mappings);
            resolvedMappings = List.copyOf(resolvedMappings == null ? List.of() : resolvedMappings);
            persistedMappings = List.copyOf(persistedMappings == null ? List.of() : persistedMappings);
        }

        public SalesChannelSpecMappingPersistence.ListingSpecSyncSnapshot mappingSnapshot(
                String currentProviderAccountId) {
            return new SalesChannelSpecMappingPersistence.ListingSpecSyncSnapshot(
                    listingId, accountId, currentProviderAccountId, productId, itemId,
                    managementMode, desiredRevision,
                    specs.stream()
                            .map(spec -> new SalesChannelSpecMappingValidator.ProductSpecSnapshot(
                                    spec.id(), spec.name()))
                            .toList(),
                    mappings.stream()
                            .map(mapping -> new SalesChannelSpecMappingValidator.ExistingMappingSnapshot(
                                    mapping.productSpecId(), mapping.externalSkuId(), mapping.providerMapping()))
                            .toList());
        }
    }

    public record ProductSpecIdentity(UUID id, String name, BigDecimal costPrice) { }

    public record MappingIdentity(UUID productSpecId, String externalSkuId,
                                  Map<String, Object> providerMapping) {
        public MappingIdentity {
            providerMapping = providerMapping == null
                    ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(providerMapping));
        }
    }
}
