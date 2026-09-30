package com.myshop.service;

import com.myshop.constant.ErrorCode;
import com.myshop.constant.SalesChannelAccountStatus;
import com.myshop.constant.SalesChannelListingManagementMode;
import com.myshop.constant.SalesChannelProvider;
import com.myshop.entity.Product;
import com.myshop.entity.ProductSpec;
import com.myshop.entity.SalesChannelAccount;
import com.myshop.entity.SalesChannelListing;
import com.myshop.entity.SalesChannelListingSpec;
import com.myshop.exception.BusinessException;
import com.myshop.exception.SalesChannelSpecMappingException;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.ProductSpecRepository;
import com.myshop.repository.SalesChannelAccountRepository;
import com.myshop.repository.SalesChannelListingRepository;
import com.myshop.repository.SalesChannelListingSpecRepository;
import com.myshop.repository.SalesChannelOperationRepository;
import com.myshop.repository.SalesChannelOrderRepository;
import com.myshop.saleschannel.SalesChannelProviderClient;
import com.myshop.saleschannel.SalesChannelProviderClientException;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.XianyuTakeoverResponse;
import com.myshop.saleschannel.model.SalesChannelDtos.IntegrationEnvelope;
import com.myshop.saleschannel.model.SalesChannelDtos.ListingSpecMappingsData;
import com.myshop.saleschannel.model.SalesChannelDtos.OwnershipClaimCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.OwnershipClaimData;
import com.myshop.saleschannel.model.SalesChannelDtos.Outcome;
import com.myshop.saleschannel.model.SalesChannelDtos.SpecMappingsCommand;
import com.myshop.service.XianyuItemTakeoverTransactionService.TakeoverPreflight;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Orchestrates the Phase 2C read -> pending -> claim -> finalize handoff. */
@Service
@RequiredArgsConstructor
public class XianyuItemTakeoverService {

    private final SalesChannelProviderClient providerClient;
    private final SalesChannelAccountRepository accountRepository;
    private final SalesChannelListingRepository listingRepository;
    private final SalesChannelOperationRepository operationRepository;
    private final SalesChannelOrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final ProductSpecRepository productSpecRepository;
    private final SalesChannelListingSpecRepository listingSpecRepository;
    private final XianyuItemBindingService bindingService;
    private final SalesChannelSpecMappingValidator specMappingValidator;
    private final XianyuItemTakeoverTransactionService transactionService;

    public XianyuTakeoverResponse takeover(UUID accountId, String itemId) {
        validateItemId(itemId);
        SalesChannelAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> failure(ErrorCode.NOT_FOUND, "闲鱼账号不存在", HttpStatus.NOT_FOUND));
        requireAccount(account);

        SalesChannelListing listing = listingRepository
                .findBySalesChannelAccountIdAndExternalItemId(accountId, itemId)
                .orElseThrow(() -> failure(ErrorCode.ITEM_NOT_LINKED,
                        "该闲鱼商品尚未关联 MyShop 商品", HttpStatus.CONFLICT));
        Product product = productRepository.findById(listing.getProductId())
                .orElseThrow(() -> failure(ErrorCode.TAKEOVER_NOT_ALLOWED, "关联商品不存在"));

        String requestId = deterministicClaimRequestId(accountId, listing.getId(), itemId);
        if (listing.getManagementMode() == SalesChannelListingManagementMode.MANAGED) {
            return claimAlreadyManaged(account, listing, product, requestId);
        }

        requireTakeoverListingLocalState(account, listing, product);
        TakeoverPreflight preflight = preparePreflight(account, listing, product, itemId);

        // Every remote read has succeeded before this transaction starts. The
        // transaction service re-locks Account -> Product -> Listing/Specs and
        // repeats identity/revision validation before changing the mode.
        XianyuItemTakeoverTransactionService.BeginResult begun = transactionService.begin(preflight);
        if (begun.managementMode() == SalesChannelListingManagementMode.MANAGED) {
            return claimAlreadyManaged(account, listing, product, requestId);
        }

        return claimAndFinalize(account, product, preflight, requestId);
    }

    private XianyuTakeoverResponse claimAlreadyManaged(SalesChannelAccount account,
                                                        SalesChannelListing listing,
                                                        Product product,
                                                        String requestId) {
        IntegrationEnvelope<OwnershipClaimData> response;
        try {
            response = providerClient.claimOwnership(new OwnershipClaimCommand(
                    requestId, account.getProviderAccountId(), listing.getExternalItemId()));
        } catch (SalesChannelProviderClientException exception) {
            throw exception;
        }
        OwnershipClaimData data;
        try {
            data = validateOwnershipClaimSuccess(response, requestId,
                    account.getProviderAccountId(), listing.getExternalItemId());
        } catch (SalesChannelProviderClientException exception) {
            if ("OWNERSHIP_CONFLICT".equals(exception.getErrorCode())) {
                throw failure(ErrorCode.OWNERSHIP_CONFLICT,
                        "闲鱼商品 ownership 已被其它主体占用", HttpStatus.CONFLICT);
            }
            throw exception;
        }
        String result = data.result();
        if ("ALREADY_CLAIMED".equals(result)) {
            return new XianyuTakeoverResponse(listing.getExternalItemId(), listing.getId().toString(),
                    product.getId().toString(), listing.getManagementMode().name(), "ALREADY_MANAGED");
        }
        if ("CLAIMED".equals(result)) {
            return new XianyuTakeoverResponse(listing.getExternalItemId(), listing.getId().toString(),
                    product.getId().toString(), listing.getManagementMode().name(), "OWNERSHIP_REPAIRED");
        }
        throw failure(ErrorCode.OWNERSHIP_CONFLICT, "Agent ownership claim returned an invalid result",
                HttpStatus.CONFLICT);
    }

    private XianyuTakeoverResponse claimAndFinalize(SalesChannelAccount account, Product product,
                                                    TakeoverPreflight preflight, String requestId) {
        IntegrationEnvelope<OwnershipClaimData> response;
        try {
            response = providerClient.claimOwnership(new OwnershipClaimCommand(
                    requestId, account.getProviderAccountId(), preflight.itemId()));
        } catch (SalesChannelProviderClientException exception) {
            return claimFailure(preflight, exception.getErrorCode(), exception.getMessage(), exception);
        } catch (RuntimeException exception) {
            return claimFailure(preflight, "TAKEOVER_CLAIM_UNKNOWN",
                    "Agent ownership claim outcome is unknown", exception);
        }

        if (response == null || response.outcome() == Outcome.UNKNOWN) {
            return claimFailure(preflight, "TAKEOVER_CLAIM_UNKNOWN",
                    "Agent ownership claim outcome is unknown", null);
        }
        if (response.outcome() == Outcome.FAILED) {
            String code = response.error() == null ? "TAKEOVER_CLAIM_FAILED" : response.error().code();
            String message = response.error() == null ? "Agent ownership claim failed" : response.error().message();
            if ("OWNERSHIP_CONFLICT".equals(code)) {
                transactionService.recordClaimFailure(preflight, code, message);
                throw failure(ErrorCode.OWNERSHIP_CONFLICT,
                        "闲鱼商品 ownership 已被其它主体占用", HttpStatus.CONFLICT);
            }
            if ("XIANYU_ITEM_NOT_FOUND".equals(code) || "ITEM_NOT_FOUND".equals(code)) {
                transactionService.recordClaimFailure(preflight, code, message);
                throw failure(ErrorCode.XIANYU_ITEM_NOT_FOUND,
                        "闲鱼商品不存在或不属于当前账号", HttpStatus.NOT_FOUND);
            }
            return claimFailure(preflight, code, message, null);
        }

        try {
            validateOwnershipClaimSuccess(response, requestId,
                    account.getProviderAccountId(), preflight.itemId());
        } catch (SalesChannelProviderClientException exception) {
            return claimFailure(preflight, exception.getErrorCode(), exception.getMessage(), exception);
        }
        return transactionService.finalizeTakeover(preflight);
    }

    private XianyuTakeoverResponse claimFailure(TakeoverPreflight preflight, String code,
                                                String message, RuntimeException cause) {
        transactionService.recordClaimFailure(preflight, code, message);
        if ("OWNERSHIP_CONFLICT".equals(code)) {
            throw failure(ErrorCode.OWNERSHIP_CONFLICT,
                    "闲鱼商品 ownership 已被其它主体占用", HttpStatus.CONFLICT);
        }
        if (cause instanceof SalesChannelProviderClientException providerException
                && providerException.getDisposition()
                == SalesChannelProviderClientException.Disposition.SAFE_PERMANENT
                && "XIANYU_ITEM_NOT_FOUND".equals(providerException.getErrorCode())) {
            throw failure(ErrorCode.XIANYU_ITEM_NOT_FOUND,
                    "闲鱼商品不存在或不属于当前账号", HttpStatus.NOT_FOUND);
        }
        return pendingResponse(preflight);
    }

    private XianyuTakeoverResponse pendingResponse(TakeoverPreflight preflight) {
        return new XianyuTakeoverResponse(preflight.itemId(), preflight.listingId().toString(),
                preflight.productId().toString(),
                SalesChannelListingManagementMode.TAKEOVER_PENDING.name(), "TAKEOVER_PENDING");
    }

    /**
     * One strict success-contract validator shared by new claims and the
     * MANAGED idempotent/repair path.  A successful envelope is not enough:
     * the request, account, item, owner, and result must all match exactly.
     */
    private OwnershipClaimData validateOwnershipClaimSuccess(
            IntegrationEnvelope<OwnershipClaimData> response,
            String expectedRequestId,
            String expectedProviderAccountId,
            String expectedItemId) {
        if (response == null || response.outcome() == Outcome.UNKNOWN) {
            throw new SalesChannelProviderClientException(
                    SalesChannelProviderClientException.Disposition.SAFE_RETRYABLE,
                    "TAKEOVER_CLAIM_UNKNOWN", "Agent ownership claim outcome is unknown");
        }
        if (response.outcome() != Outcome.SUCCEEDED || response.data() == null) {
            String code = response.error() == null ? "TAKEOVER_CLAIM_FAILED" : response.error().code();
            if ("OWNERSHIP_CONFLICT".equals(code)) {
                throw failure(ErrorCode.OWNERSHIP_CONFLICT,
                        "闲鱼商品 ownership 已被其它主体占用", HttpStatus.CONFLICT);
            }
            throw new SalesChannelProviderClientException(
                    response.error() != null && Boolean.TRUE.equals(response.error().retryable())
                            ? SalesChannelProviderClientException.Disposition.SAFE_RETRYABLE
                            : SalesChannelProviderClientException.Disposition.SAFE_PERMANENT,
                    code, response.error() == null ? "Agent ownership claim failed" : response.error().message());
        }
        if (response.requestId() == null || !expectedRequestId.equals(response.requestId())) {
            throw new SalesChannelProviderClientException(
                    SalesChannelProviderClientException.Disposition.SAFE_PERMANENT,
                    "TAKEOVER_CLAIM_REQUEST_ID_MISMATCH", "Agent ownership claim request id did not match");
        }
        OwnershipClaimData data = response.data();
        if (data == null
                || !Objects.equals(expectedProviderAccountId, data.accountId())
                || !Objects.equals(expectedItemId, data.itemId())
                || !"MYSHOP".equals(data.managedBy())
                || !("CLAIMED".equals(data.result()) || "ALREADY_CLAIMED".equals(data.result()))) {
            throw new SalesChannelProviderClientException(
                    SalesChannelProviderClientException.Disposition.SAFE_PERMANENT,
                    "TAKEOVER_CLAIM_RESPONSE_INVALID",
                    "Agent ownership claim response did not match the exact account, item, owner, and result identity");
        }
        return data;
    }

    private TakeoverPreflight preparePreflight(SalesChannelAccount account,
                                               SalesChannelListing listing,
                                               Product product,
                                               String itemId) {
        if (listing.getExternalItemId() == null || !itemId.equals(listing.getExternalItemId())) {
            throw failure(ErrorCode.TAKEOVER_NOT_ALLOWED, "listing 没有稳定的闲鱼商品 identity");
        }
        List<ProductSpec> activeSpecs = productSpecRepository
                .findByProductIdAndIsDeletedOrderBySortOrderAsc(product.getId(), 0);
        if (!product.isSpecEnabled() && product.getCostPrice() == null) {
            throw failure(ErrorCode.TAKEOVER_NOT_ORDER_READY,
                    "无规格商品必须先设置非空 cost price");
        }
        if (product.isSpecEnabled()) {
            if (activeSpecs.isEmpty() || activeSpecs.stream().anyMatch(spec -> spec.getCostPrice() == null)) {
                throw failure(ErrorCode.TAKEOVER_NOT_ORDER_READY,
                        "规格商品的每个 active ProductSpec 必须有非空 cost price");
            }
        }

        List<SalesChannelListingSpec> mappings = listingSpecRepository
                .findBySalesChannelListingIdOrderByCreatedAtAsc(listing.getId());
        List<SalesChannelSpecMappingValidator.ProductSpecSnapshot> validatorSpecs = activeSpecs.stream()
                .map(SalesChannelSpecMappingValidator::snapshot).toList();
        List<SalesChannelSpecMappingValidator.ExistingMappingSnapshot> validatorMappings = mappings.stream()
                .map(SalesChannelSpecMappingValidator::snapshot).toList();

        bindingService.verifyExactCatalogItem(account, itemId);
        IntegrationEnvelope<ListingSpecMappingsData> specResponse = providerClient.inspectItemSpecMappings(
                new SpecMappingsCommand(deterministicSpecRequestId(account.getId(), listing.getId(), itemId),
                        account.getProviderAccountId(), itemId));
        ListingSpecMappingsData providerSpecs = requireSpecInspection(specResponse, itemId);
        List<SalesChannelSpecMappingValidator.ResolvedSpecMapping> resolvedMappings = List.of();
        if (product.isSpecEnabled()) {
            try {
                resolvedMappings = specMappingValidator.resolve(validatorSpecs, validatorMappings, providerSpecs);
            } catch (SalesChannelSpecMappingException exception) {
                throw failure(ErrorCode.TAKEOVER_NOT_ALLOWED,
                        "闲鱼规格与 MyShop active ProductSpec 无法完成 exact mapping: " + exception.getMessage());
            }
        } else if (!Boolean.TRUE.equals(providerSpecs.specDimensionsVerified())
                || providerSpecs.specifications() == null || !providerSpecs.specifications().isEmpty()
                || !mappings.isEmpty()
                || providerSpecs.skuRows() == null || providerSpecs.skuRows().stream()
                .anyMatch(row -> row != null && row.specs() != null && !row.specs().isEmpty())
                || providerSpecs.skuRows().size() > 1) {
            throw failure(ErrorCode.TAKEOVER_NOT_ALLOWED,
                    "无规格商品未能由 Agent 只读详情可靠证明为单规格商品");
        }

        List<XianyuItemTakeoverTransactionService.ProductSpecIdentity> specs = activeSpecs.stream()
                .map(spec -> new XianyuItemTakeoverTransactionService.ProductSpecIdentity(
                        spec.getId(), spec.getName(), spec.getCostPrice())).toList();
        List<XianyuItemTakeoverTransactionService.MappingIdentity> mappingIdentities = mappings.stream()
                .map(mapping -> new XianyuItemTakeoverTransactionService.MappingIdentity(
                        mapping.getProductSpecId(), mapping.getExternalSkuId(), mapping.getProviderMapping())).toList();
        Map<UUID, XianyuItemTakeoverTransactionService.MappingIdentity> existingMappingsBySpec = mappingIdentities
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        XianyuItemTakeoverTransactionService.MappingIdentity::productSpecId,
                        mapping -> mapping, (left, right) -> left, LinkedHashMap::new));
        List<XianyuItemTakeoverTransactionService.MappingIdentity> persistedMappings = product.isSpecEnabled()
                ? resolvedMappings.stream().map(mapping -> {
                    XianyuItemTakeoverTransactionService.MappingIdentity existing = existingMappingsBySpec
                            .get(mapping.productSpecId());
                    Map<String, Object> providerMapping = new LinkedHashMap<>(
                            existing == null ? Map.of() : existing.providerMapping());
                    providerMapping.put("provider_spec_name", mapping.providerSpecName());
                    providerMapping.put("provider_spec_value", mapping.providerSpecValue());
                    return new XianyuItemTakeoverTransactionService.MappingIdentity(
                            mapping.productSpecId(), mapping.externalSkuId(), providerMapping);
                }).toList()
                : mappingIdentities;
        return new TakeoverPreflight(account.getId(), listing.getId(), product.getId(), itemId,
                account.getProviderAccountId(), listing.getManagementMode(), listing.getDesiredRevision(),
                product.isSpecEnabled(), specs, mappingIdentities, resolvedMappings, persistedMappings);
    }

    private ListingSpecMappingsData requireSpecInspection(IntegrationEnvelope<ListingSpecMappingsData> response,
                                                          String itemId) {
        if (response == null || response.outcome() == Outcome.UNKNOWN) {
            throw new SalesChannelProviderClientException(
                    SalesChannelProviderClientException.Disposition.SAFE_RETRYABLE,
                    "ITEM_SPEC_READ_UNKNOWN", "Agent item specification inspection outcome is unknown");
        }
        if (response.outcome() != Outcome.SUCCEEDED || response.data() == null) {
            String code = response.error() == null ? "ITEM_SPEC_READ_FAILED" : response.error().code();
            if ("ITEM_NOT_FOUND".equals(code) || "XIANYU_ITEM_NOT_FOUND".equals(code)) {
                throw failure(ErrorCode.XIANYU_ITEM_NOT_FOUND,
                        "闲鱼商品不存在或不属于当前账号", HttpStatus.NOT_FOUND);
            }
            throw new SalesChannelProviderClientException(
                    response.error() != null && Boolean.TRUE.equals(response.error().retryable())
                            ? SalesChannelProviderClientException.Disposition.SAFE_RETRYABLE
                            : SalesChannelProviderClientException.Disposition.SAFE_PERMANENT,
                    code, response.error() == null ? "Agent item specification inspection failed"
                            : response.error().message());
        }
        if (!itemId.equals(response.data().itemId())) {
            throw failure(ErrorCode.XIANYU_ITEM_NOT_FOUND,
                    "Agent 返回了不同的闲鱼商品 identity", HttpStatus.BAD_GATEWAY);
        }
        return response.data();
    }

    private void requireTakeoverListingLocalState(SalesChannelAccount account,
                                                   SalesChannelListing listing,
                                                   Product product) {
        if (listing.getManagementMode() != SalesChannelListingManagementMode.LINKED_ONLY
                && listing.getManagementMode() != SalesChannelListingManagementMode.TAKEOVER_PENDING) {
            throw failure(ErrorCode.TAKEOVER_NOT_ALLOWED, "当前 listing 状态不支持接管");
        }
        if (listing.isEnabled() || listing.isAutoFulfillmentEnabled()) {
            throw failure(ErrorCode.TAKEOVER_NOT_ALLOWED,
                    "接管前 listing 必须保持 disabled 且 auto fulfillment=false");
        }
        if (listing.getManagementMode() == SalesChannelListingManagementMode.LINKED_ONLY
                && (operationRepository.existsBySalesChannelListingId(listing.getId())
                || orderRepository.existsBySalesChannelListingId(listing.getId()))) {
            throw failure(ErrorCode.TAKEOVER_NOT_ALLOWED,
                    "已有渠道操作或订单的 LINKED_ONLY listing 不允许首次接管");
        }
        if (product.getIsDeleted() != 0) {
            throw failure(ErrorCode.TAKEOVER_NOT_ALLOWED, "关联商品已删除");
        }
    }

    private void requireAccount(SalesChannelAccount account) {
        if (account.getProvider() != SalesChannelProvider.XIANYU || !account.isEnabled()
                || account.getStatus() != SalesChannelAccountStatus.CONNECTED
                || !"FISH_SHOP".equals(accountType(account))) {
            throw failure(ErrorCode.TAKEOVER_NOT_ALLOWED,
                    "接管要求启用、CONNECTED 且已验证为 FISH_SHOP 的闲鱼账号");
        }
    }

    private String accountType(SalesChannelAccount account) {
        Map<String, Object> capabilities = account.getCapabilities();
        Object type = capabilities == null ? null : capabilities.get("account_type");
        return type == null ? "UNKNOWN" : type.toString().toUpperCase();
    }

    private void validateItemId(String itemId) {
        if (itemId == null || itemId.isBlank() || itemId.length() > 128) {
            throw failure(ErrorCode.BAD_REQUEST, "闲鱼商品 ID 无效");
        }
    }

    private String deterministicClaimRequestId(UUID accountId, UUID listingId, String itemId) {
        return "XIANYU_TAKEOVER_CLAIM:" + sha256(accountId + ":" + listingId + ":" + itemId);
    }

    private String deterministicSpecRequestId(UUID accountId, UUID listingId, String itemId) {
        return "XIANYU_TAKEOVER_SPEC:" + sha256(accountId + ":" + listingId + ":" + itemId);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private BusinessException failure(int code, String message) {
        return failure(code, message, HttpStatus.CONFLICT);
    }

    private BusinessException failure(int code, String message, HttpStatus status) {
        return new BusinessException(code, message, status);
    }
}
