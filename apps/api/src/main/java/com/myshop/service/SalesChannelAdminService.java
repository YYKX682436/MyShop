package com.myshop.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.constant.SalesChannelAccountStatus;
import com.myshop.constant.SalesChannelProvider;
import com.myshop.constant.ErrorCode;
import com.myshop.entity.SalesChannelAccount;
import com.myshop.entity.SalesChannelListing;
import com.myshop.entity.Product;
import com.myshop.entity.ProductSpec;
import com.myshop.entity.SalesChannelListingGeneration;
import com.myshop.entity.SalesChannelListingGenerationSpec;
import com.myshop.entity.SalesChannelOrderCursor;
import com.myshop.entity.SalesChannelOrder;
import com.myshop.entity.Order;
import com.myshop.entity.OrderItem;
import com.myshop.entity.SalesChannelDeliveryOperation;
import com.myshop.constant.StockMode;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.ProductSpecRepository;
import com.myshop.repository.SalesChannelOrderCursorRepository;
import com.myshop.repository.SalesChannelOrderRepository;
import com.myshop.repository.SalesChannelDeliveryOperationRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.SalesChannelAccountRepository;
import com.myshop.repository.SalesChannelListingRepository;
import com.myshop.repository.SalesChannelListingGenerationRepository;
import com.myshop.repository.SalesChannelListingGenerationSpecRepository;
import com.myshop.repository.SalesChannelOperationRepository;
import com.myshop.saleschannel.SalesChannelProviderClient;
import com.myshop.saleschannel.SalesChannelProviderClientException;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.AccountSummary;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.CategoryRecommendRequest;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.ListingSummary;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.ListingSpecMappingSummary;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.SpecMappingSyncResult;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.XianyuCatalogItemSummary;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.XianyuItemBinding;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.XianyuItemBindingResponse;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.XianyuTakeoverResponse;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.XianyuLinkCandidate;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.XianyuLinkCandidatesPage;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.XianyuItemsPage;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.XianyuItemSyncSummary;
import com.myshop.saleschannel.model.SalesChannelDtos.AccountCapabilities;
import com.myshop.saleschannel.model.SalesChannelDtos.AccountsData;
import com.myshop.saleschannel.model.SalesChannelDtos.AddressMetadataResult;
import com.myshop.saleschannel.model.SalesChannelDtos.CapabilityData;
import com.myshop.saleschannel.model.SalesChannelDtos.CategoryRecommendCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.CategoryRecommendResult;
import com.myshop.saleschannel.model.SalesChannelDtos.QrLoginStartCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.QrLoginReauthenticationStartCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.QrLoginStartData;
import com.myshop.saleschannel.model.SalesChannelDtos.QrLoginStatusCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.QrLoginStatusData;
import com.myshop.saleschannel.model.SalesChannelDtos.QrLoginVerificationCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.OrderCursor;
import com.myshop.saleschannel.model.SalesChannelDtos.WatermarkCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.WatermarkData;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.SalesChannelOrderSummary;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.SalesChannelOrderDetail;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.SalesChannelDeliveryOperationSummary;
import com.myshop.saleschannel.model.SalesChannelDtos.IntegrationEnvelope;
import com.myshop.saleschannel.model.SalesChannelDtos.CatalogItem;
import com.myshop.saleschannel.model.SalesChannelDtos.ItemsQueryCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.ItemsQueryData;
import com.myshop.saleschannel.model.SalesChannelDtos.ItemsSyncCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.ItemsSyncData;
import com.myshop.saleschannel.model.SalesChannelDtos.Outcome;
import com.myshop.exception.BusinessException;
import com.myshop.saleschannel.xianyu.model.XianyuProviderConfig;
import com.myshop.service.impl.SalesChannelAccountServiceImpl;
import com.myshop.service.impl.SalesChannelListingServiceImpl;
import com.myshop.service.SalesChannelListingManagementPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Locale;
import java.util.stream.Collectors;

/** Admin-only orchestration. Agent credentials and session data never cross this API boundary. */
@Service
@RequiredArgsConstructor
public class SalesChannelAdminService {

    private final SalesChannelProviderClient providerClient;
    private final SalesChannelAccountServiceImpl accountService;
    private final SalesChannelListingServiceImpl listingService;
    private final SalesChannelDesiredStatePlanner planner;
    private final SalesChannelAccountRepository accountRepository;
    private final SalesChannelListingRepository listingRepository;
    private final SalesChannelOperationRepository operationRepository;
    private final ObjectMapper objectMapper;
    private final ProductRepository productRepository;
    private final ProductSpecRepository productSpecRepository;
    private final SalesChannelListingGenerationRepository generationRepository;
    private final SalesChannelListingGenerationSpecRepository generationSpecRepository;
    private final SalesChannelSpecMappingValidator specMappingValidator;
    private final SalesChannelSpecMappingSyncService specMappingSyncService;
    private final SalesChannelOrderCursorRepository cursorRepository;
    private final SalesChannelOrderRepository channelOrderRepository;
    private final SalesChannelDeliveryOperationRepository deliveryOperationRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final XianyuItemBindingService itemBindingService;
    private final XianyuItemTakeoverService itemTakeoverService;

    public List<AccountSummary> accounts() {
        List<SalesChannelAccount> localAccounts = accountRepository.findAll().stream()
                .filter(account -> account.getProvider() == SalesChannelProvider.XIANYU).toList();
        return accountSummariesWithAgentState(localAccounts, readAgentAccountsForAdminView());
    }

    public XianyuItemsPage queryXianyuItems(UUID accountId, int page, int pageSize, String keyword) {
        SalesChannelAccount account = findEnabledXianyuAccount(accountId);
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "闲鱼商品分页参数无效");
        }
        IntegrationEnvelope<ItemsQueryData> response = providerClient.queryItems(
                new ItemsQueryCommand(account.getProviderAccountId(), page, pageSize,
                        keyword == null ? "" : keyword.trim()));
        ItemsQueryData data = requireCatalogData(response, "闲鱼商品目录读取失败");
        List<CatalogItem> remoteItems = data.items() == null ? List.of() : data.items();
        if (remoteItems.isEmpty()) {
            return new XianyuItemsPage(account.getId().toString(), List.of(),
                    safeInt(data.page(), page), safeInt(data.pageSize(), pageSize),
                    safeLong(data.total()), safeInt(data.totalPages(), 0));
        }

        Set<String> itemIds = new LinkedHashSet<>();
        remoteItems.stream().map(CatalogItem::itemId).filter(value -> !blank(value)).forEach(itemIds::add);
        List<SalesChannelListingGeneration> generations = itemIds.isEmpty() ? List.of()
                : generationRepository.findAllBySalesChannelAccountIdAndExternalItemIdIn(account.getId(), itemIds);
        Map<String, List<SalesChannelListingGeneration>> generationsByItem = new HashMap<>();
        Set<UUID> listingIds = generations.stream()
                .map(SalesChannelListingGeneration::getSalesChannelListingId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, SalesChannelListing> listingsById = listingIds.isEmpty() ? Map.of()
                : listingRepository.findAllById(listingIds).stream()
                .collect(Collectors.toMap(SalesChannelListing::getId, value -> value));
        Set<UUID> productIds = new LinkedHashSet<>();
        for (SalesChannelListingGeneration generation : generations) {
            generationsByItem.computeIfAbsent(generation.getExternalItemId(), ignored -> new ArrayList<>()).add(generation);
            SalesChannelListing listing = listingsById.get(generation.getSalesChannelListingId());
            if (listing != null) productIds.add(listing.getProductId());
        }
        Map<UUID, Product> products = new HashMap<>();
        if (!productIds.isEmpty()) {
            productRepository.findAllById(productIds).forEach(product -> products.put(product.getId(), product));
        }

        List<XianyuCatalogItemSummary> annotated = remoteItems.stream()
                .map(item -> annotateCatalogItem(item, generationsByItem.getOrDefault(item.itemId(), List.of()),
                        listingsById, products))
                .toList();
        return new XianyuItemsPage(account.getId().toString(), annotated,
                safeInt(data.page(), page), safeInt(data.pageSize(), pageSize),
                safeLong(data.total()), safeInt(data.totalPages(), 0));
    }

    public XianyuItemSyncSummary syncXianyuItems(UUID accountId, String requestId) {
        SalesChannelAccount account = findEnabledXianyuAccount(accountId);
        IntegrationEnvelope<ItemsSyncData> response = providerClient.syncItems(
                new ItemsSyncCommand(requestId, account.getProviderAccountId()));
        ItemsSyncData data = requireCatalogData(response, "闲鱼商品同步失败");
        if (!account.getProviderAccountId().equals(data.accountId()) || Boolean.TRUE.equals(data.skipped())) {
            throw new SalesChannelProviderClientException(
                    SalesChannelProviderClientException.Disposition.SAFE_PERMANENT,
                    "AGENT_RESPONSE_INVALID", "Xianyu Agent returned an invalid catalog sync summary");
        }
        return new XianyuItemSyncSummary(account.getId().toString(),
                safeInt(data.fetchedCount(), 0), safeInt(data.savedCount(), 0),
                safeInt(data.pages(), 0), Boolean.TRUE.equals(data.skipped()));
    }

    public XianyuLinkCandidatesPage queryXianyuLinkCandidates(UUID accountId, String itemId,
                                                               int page, int pageSize, String keyword) {
        SalesChannelAccount account = findEnabledXianyuAccount(accountId);
        if (blank(itemId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "闲鱼商品 ID 不能为空");
        }
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "商品候选分页参数无效");
        }

        String normalizedKeyword = keyword == null ? null : keyword.trim();
        Long productNo = parseProductNo(normalizedKeyword);
        PageRequest pageable = PageRequest.of(page - 1, pageSize,
                ProductListSort.resolve("sort_weight", "desc"));
        Page<Product> productPage = normalizedKeyword != null && !normalizedKeyword.isBlank()
                ? productRepository.findAdminProductsByKeyword(null,
                        "%" + normalizedKeyword.toLowerCase(Locale.ROOT) + "%",
                        productNo, null, pageable)
                : productRepository.findAdminProducts(null, null, pageable);

        List<UUID> productIds = productPage.getContent().stream().map(Product::getId).toList();
        List<SalesChannelListing> listings = productIds.isEmpty() ? List.of()
                : listingRepository.findBySalesChannelAccountIdAndProductIdIn(accountId, productIds);
        Map<UUID, SalesChannelListing> listingByProduct = listings.stream()
                .collect(java.util.stream.Collectors.toMap(SalesChannelListing::getProductId,
                        value -> value, (left, right) -> left));
        Set<UUID> listingIds = listings.stream().map(SalesChannelListing::getId)
                .collect(java.util.stream.Collectors.toSet());
        Set<UUID> operationListingIds = listingIds.isEmpty() ? Set.of()
                : operationRepository.findListingIdsWithOperations(listingIds);
        Set<UUID> orderListingIds = listingIds.isEmpty() ? Set.of()
                : channelOrderRepository.findListingIdsWithOrders(listingIds);

        List<XianyuLinkCandidate> candidates = productPage.getContent().stream()
                .map(product -> linkCandidate(product, itemId, listingByProduct.get(product.getId()),
                        operationListingIds, orderListingIds))
                .toList();
        return new XianyuLinkCandidatesPage(account.getId().toString(), itemId, candidates,
                productPage.getNumber() + 1, productPage.getSize(), productPage.getTotalElements(),
                productPage.getTotalPages());
    }

    public XianyuItemBindingResponse linkXianyuItem(UUID accountId, String itemId, UUID productId) {
        return itemBindingService.link(accountId, itemId, productId);
    }

    public XianyuTakeoverResponse takeoverXianyuItem(UUID accountId, String itemId) {
        return itemTakeoverService.takeover(accountId, itemId);
    }

    private SalesChannelAccount findEnabledXianyuAccount(UUID accountId) {
        SalesChannelAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "闲鱼账号不存在", HttpStatus.NOT_FOUND));
        if (account.getProvider() != SalesChannelProvider.XIANYU) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "所选账号不是闲鱼账号");
        }
        if (!account.isEnabled()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "闲鱼账号已禁用");
        }
        return account;
    }

    private <T> T requireCatalogData(IntegrationEnvelope<T> response, String fallbackMessage) {
        if (response == null || response.outcome() != Outcome.SUCCEEDED || response.data() == null) {
            String code = response == null || response.error() == null
                    ? "AGENT_REQUEST_FAILED" : response.error().code();
            String message = response == null || response.error() == null || blank(response.error().message())
                    ? fallbackMessage : response.error().message();
            boolean retryable = response != null && response.error() != null
                    && Boolean.TRUE.equals(response.error().retryable());
            throw new SalesChannelProviderClientException(
                    retryable ? SalesChannelProviderClientException.Disposition.SAFE_RETRYABLE
                            : SalesChannelProviderClientException.Disposition.SAFE_PERMANENT,
                    blank(code) ? "AGENT_REQUEST_FAILED" : code,
                    message);
        }
        return response.data();
    }

    private XianyuCatalogItemSummary annotateCatalogItem(
            CatalogItem item, List<SalesChannelListingGeneration> matches,
            Map<UUID, SalesChannelListing> listingsById, Map<UUID, Product> products) {
        List<XianyuItemBinding> bindings = matches.stream().map(generation -> {
            SalesChannelListing listing = listingsById.get(generation.getSalesChannelListingId());
            if (listing == null) throw new IllegalStateException("Generation listing is missing");
            Product product = products.get(listing.getProductId());
            return new XianyuItemBinding(listing.getId().toString(), listing.getProductId().toString(),
                    product == null ? null : product.getTitle(), listing.getManagementMode().name(),
                    generation.getId().toString(), generation.getGenerationNo(),
                    generation.getGenerationState() == com.myshop.constant.SalesChannelGenerationState.CURRENT);
        }).toList();
        boolean linked = matches.size() == 1;
        boolean conflict = matches.size() > 1;
        XianyuItemBinding binding = linked ? bindings.get(0) : null;
        return new XianyuCatalogItemSummary(
                item.accountId(), item.itemId(), item.title(), item.price(), item.quantity(), item.statusDesc(),
                safeInt(item.skuCount(), 0), Boolean.TRUE.equals(item.multiSpec()),
                Boolean.TRUE.equals(item.sellerItem()), item.catalogCreatedAt(), item.catalogUpdatedAt(),
                conflict ? "CONFLICT" : linked ? "LINKED" : "UNLINKED",
                binding == null ? null : binding.managementMode(),
                binding == null ? null : binding.listingId(),
                binding == null ? null : binding.productId(),
                binding == null ? null : binding.productTitle(),
                bindings);
    }

    private XianyuLinkCandidate linkCandidate(Product product, String itemId,
                                               SalesChannelListing listing,
                                               Set<UUID> operationListingIds,
                                               Set<UUID> orderListingIds) {
        if (listing == null) {
            return new XianyuLinkCandidate(product.getId().toString(), product.getProductNo(),
                    product.getTitle(), product.isEnabled(), "AVAILABLE", null, null, null, null);
        }
        String existingListingId = listing.getId().toString();
        SalesChannelListingGeneration generation = generationRepository
                .findCurrentByListingId(listing.getId()).orElse(null);
        String existingExternalItemId = generation == null ? null : generation.getExternalItemId();
        String managementMode = listing.getManagementMode().name();
        if (itemId.equals(existingExternalItemId)) {
            return new XianyuLinkCandidate(product.getId().toString(), product.getProductNo(),
                    product.getTitle(), product.isEnabled(), "ALREADY_LINKED_THIS_ITEM",
                    existingListingId, existingExternalItemId, managementMode,
                    "ALREADY_LINKED_THIS_ITEM");
        }
        if (!blank(existingExternalItemId)) {
            return new XianyuLinkCandidate(product.getId().toString(), product.getProductNo(),
                    product.getTitle(), product.isEnabled(), "LINKED_TO_OTHER_ITEM",
                    existingListingId, existingExternalItemId, managementMode,
                    "LINKED_TO_OTHER_ITEM");
        }
        boolean safe = !listing.isEnabled() && !listing.isAutoFulfillmentEnabled()
                && listing.getDesiredState() == com.myshop.constant.SalesChannelListingDesiredState.OFFLINE
                && (listing.getStatus() == com.myshop.constant.SalesChannelListingStatus.DRAFT
                || listing.getStatus() == com.myshop.constant.SalesChannelListingStatus.OFFLINE)
                && !operationListingIds.contains(listing.getId())
                && !orderListingIds.contains(listing.getId());
        return new XianyuLinkCandidate(product.getId().toString(), product.getProductNo(),
                product.getTitle(), product.isEnabled(), safe ? "AVAILABLE" : "LISTING_NOT_LINKABLE",
                existingListingId, existingExternalItemId, managementMode,
                safe ? null : "LISTING_NOT_LINKABLE");
    }

    private Long parseProductNo(String keyword) {
        if (keyword == null || !keyword.matches("\\d+")) return null;
        try {
            return Long.valueOf(keyword);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private int safeInt(Integer value, int fallback) { return value == null ? fallback : value; }

    private long safeLong(Long value) { return value == null ? 0 : value; }

    public QrLoginStartData startQrLogin(String requestId, String loginIntentId, String operatorIdentity) {
        IntegrationEnvelope<QrLoginStartData> response = providerClient.startQrLogin(
                new QrLoginStartCommand(requestId, loginIntentId, operatorIdentity));
        requireSuccess(response.outcome(), response.error() == null ? null : response.error().code());
        return validateQrLoginStart(response.data(), loginIntentId);
    }

    public QrLoginStartData startQrReauthentication(
            UUID accountRowId, String requestId, String loginIntentId, String operatorIdentity) {
        SalesChannelAccount account = findAccount(accountRowId);
        if (account.getProvider() != SalesChannelProvider.XIANYU || blank(account.getProviderAccountId())) {
            throw new IllegalArgumentException("QR_REAUTHENTICATION_TARGET_INVALID");
        }
        IntegrationEnvelope<QrLoginStartData> response = providerClient.startQrReauthentication(
                new QrLoginReauthenticationStartCommand(
                        requestId, loginIntentId, account.getProviderAccountId(), operatorIdentity));
        requireSuccess(response.outcome(), response.error() == null ? null : response.error().code());
        return validateQrLoginStart(response.data(), loginIntentId);
    }

    public QrLoginStatusData getQrLoginStatus(
            String requestId, String loginSessionId, String loginIntentId, String operatorIdentity) {
        return advanceQrLoginStatus(requestId, loginSessionId, loginIntentId, operatorIdentity, null);
    }

    public QrLoginStatusData getQrReauthenticationStatus(
            UUID accountRowId, String requestId, String loginSessionId,
            String loginIntentId, String operatorIdentity) {
        SalesChannelAccount target = findAccount(accountRowId);
        if (target.getProvider() != SalesChannelProvider.XIANYU || blank(target.getProviderAccountId())) {
            return qrLoginFailure(loginIntentId, loginSessionId, "REAUTHENTICATION_TARGET_INVALID");
        }
        return advanceQrLoginStatus(requestId, loginSessionId, loginIntentId, operatorIdentity, target);
    }

    private QrLoginStatusData advanceQrLoginStatus(
            String requestId, String loginSessionId, String loginIntentId,
            String operatorIdentity, SalesChannelAccount reauthenticationTarget) {
        IntegrationEnvelope<QrLoginStatusData> response = providerClient.getQrLoginStatus(
                new QrLoginStatusCommand(requestId, loginSessionId, loginIntentId, operatorIdentity));
        if (response == null || response.outcome() != Outcome.SUCCEEDED || response.data() == null) {
            String code = response == null || response.error() == null ? "QR_LOGIN_STATUS_UNAVAILABLE"
                    : response.error().code();
            return qrLoginFailure(loginIntentId, loginSessionId, code);
        }
        QrLoginStatusData status = response.data();
        if (!loginIntentId.equals(status.loginIntentId())
                || !loginSessionId.equals(status.loginSessionId())) {
            return qrLoginFailure(loginIntentId, loginSessionId, "LOGIN_INTENT_IDENTITY_CONFLICT");
        }
        if (blank(status.status())) return qrLoginFailure(loginIntentId, loginSessionId, "QR_LOGIN_STATUS_UNAVAILABLE");
        if ("FAILED".equals(status.status()) || "EXPIRED".equals(status.status())
                || "CANCELLED".equals(status.status())
                || "PROVIDER_AUTH_VERIFICATION_FAILED".equals(status.status())
                || "PROVIDER_AUTH_VERIFIED".equals(status.status())) {
            return status;
        }
        if (!"COOKIE_PERSISTED".equals(status.status())) return status;
        if (blank(status.accountId())) {
            return finishQrVerification(status, loginSessionId, loginIntentId, operatorIdentity,
                    "COOKIE_PERSISTED_ACCOUNT_ID_MISSING", false);
        }

        SalesChannelAccount target;
        if (reauthenticationTarget == null) {
            try {
                syncXianyuAccounts();
                target = accountRepository.findByProviderAndProviderAccountId(
                                SalesChannelProvider.XIANYU, status.accountId())
                        .orElse(null);
            } catch (RuntimeException syncFailure) {
                target = null;
            }
            if (target == null) {
                return finishQrVerification(status, loginSessionId, loginIntentId, operatorIdentity,
                        "MYSHOP_ACCOUNT_SYNC_FAILED", false);
            }
        } else {
            target = reauthenticationTarget;
            if (!status.accountId().equals(target.getProviderAccountId())) {
                return finishQrVerification(status, loginSessionId, loginIntentId, operatorIdentity,
                        "LOGIN_INTENT_TARGET_MISMATCH", false);
            }
        }

        try {
            AccountSummary verified = reauthenticationTarget == null
                    ? verifyAccount(target.getId())
                    : verifyExistingQrReauthenticationTarget(target, status.accountId());
            if (verified == null || !status.accountId().equals(verified.providerAccountId())) {
                return finishQrVerification(status, loginSessionId, loginIntentId, operatorIdentity,
                        "MYSHOP_PROVIDER_ACCOUNT_ID_CHANGED", false);
            }
        } catch (RuntimeException verificationFailure) {
            return finishQrVerification(status, loginSessionId, loginIntentId, operatorIdentity,
                    verificationErrorCode(verificationFailure), false);
        }
        return finishQrVerification(status, loginSessionId, loginIntentId, operatorIdentity, null, true);
    }

    private AccountSummary verifyExistingQrReauthenticationTarget(
            SalesChannelAccount target, String expectedProviderAccountId) {
        IntegrationEnvelope<CapabilityData> response = providerClient.verifyAccount(expectedProviderAccountId);
        if (response == null) {
            throw new IllegalStateException("PROVIDER_AUTH_VERIFICATION_FAILED");
        }
        requireSuccess(response.outcome(), response.error() == null ? null : response.error().code());
        if (response.data() == null || response.data().capabilities() == null) {
            throw new IllegalStateException("PROVIDER_AUTH_VERIFICATION_FAILED");
        }
        Map<String, Object> capabilities = objectMapper.convertValue(response.data().capabilities(),
                new TypeReference<>() { });
        SalesChannelAccount updated = accountService.markExistingXianyuQrReauthenticationVerified(
                target.getId(), expectedProviderAccountId, capabilities, LocalDateTime.now());
        return accountSummary(updated);
    }

    private QrLoginStatusData finishQrVerification(
            QrLoginStatusData persisted, String loginSessionId, String loginIntentId,
            String operatorIdentity, String errorCode, boolean verified) {
        String requestId = UUID.randomUUID().toString();
        IntegrationEnvelope<QrLoginStatusData> response = providerClient.completeQrLoginVerification(
                new QrLoginVerificationCommand(
                        requestId, loginSessionId, loginIntentId, operatorIdentity, verified, errorCode));
        if (response == null || response.outcome() != Outcome.SUCCEEDED || response.data() == null) {
            return verified
                    ? new QrLoginStatusData("COOKIE_PERSISTED", loginIntentId, loginSessionId,
                        persisted.accountId(), persisted.isNewAccount(), "PROVIDER_AUTH_RESULT_NOT_RECORDED", null, null)
                    : new QrLoginStatusData("PROVIDER_AUTH_VERIFICATION_FAILED", loginIntentId, loginSessionId,
                        persisted.accountId(), persisted.isNewAccount(), errorCode, null, null);
        }
        QrLoginStatusData result = response.data();
        String expectedStatus = verified ? "PROVIDER_AUTH_VERIFIED" : "PROVIDER_AUTH_VERIFICATION_FAILED";
        if (!loginIntentId.equals(result.loginIntentId()) || !loginSessionId.equals(result.loginSessionId())
                || !expectedStatus.equals(result.status())
                || !java.util.Objects.equals(persisted.accountId(), result.accountId())) {
            return qrLoginFailure(loginIntentId, loginSessionId, "LOGIN_INTENT_IDENTITY_CONFLICT");
        }
        return result;
    }

    private String verificationErrorCode(RuntimeException error) {
        if (error instanceof SalesChannelProviderClientException providerError
                && !blank(providerError.getErrorCode())) {
            return safeVerificationCode(providerError.getErrorCode());
        }
        String message = error.getMessage();
        if (message != null && message.matches(".*: [A-Z][A-Z0-9_]{2,127}$")) {
            return safeVerificationCode(message.substring(message.lastIndexOf(": ") + 2));
        }
        return "PROVIDER_AUTH_VERIFICATION_FAILED";
    }

    private String safeVerificationCode(String code) {
        return code != null && code.matches("[A-Z][A-Z0-9_]{2,127}")
                ? code : "PROVIDER_AUTH_VERIFICATION_FAILED";
    }

    private QrLoginStartData validateQrLoginStart(QrLoginStartData data, String loginIntentId) {
        if (data == null || !loginIntentId.equals(data.loginIntentId()) || blank(data.loginSessionId())
                || blank(data.qrCodeDataUrl()) || !data.qrCodeDataUrl().startsWith("data:image/png;base64,")
                || data.expiresInSeconds() == null || data.expiresInSeconds() <= 0) {
            throw new IllegalStateException("Xianyu Agent returned an incomplete or mismatched QR login response");
        }
        return data;
    }

    private QrLoginStatusData qrLoginFailure(String loginIntentId, String loginSessionId, String errorCode) {
        return new QrLoginStatusData("FAILED", loginIntentId, loginSessionId, null, null,
                blank(errorCode) ? "QR_LOGIN_FAILED" : errorCode, null, null);
    }

    public AddressMetadataResult searchAddress(String keywords, String city) {
        return providerClient.searchAddress(keywords, city);
    }

    public CategoryRecommendResult recommendCategory(CategoryRecommendRequest request) {
        if (request == null || blank(request.accountId()) || blank(request.title())) {
            throw new IllegalArgumentException("Account and listing title are required for category recommendation");
        }
        SalesChannelAccount account = accountRepository
                .findByProviderAndProviderAccountId(SalesChannelProvider.XIANYU, request.accountId())
                .orElseThrow(() -> new IllegalArgumentException("The selected Xianyu account does not exist"));
        return providerClient.recommendCategory(new CategoryRecommendCommand(UUID.randomUUID().toString(),
                account.getProviderAccountId(), request.title(), request.description(), request.cardList(),
                request.selectedCategory()));
    }

    public List<AccountSummary> syncXianyuAccounts() {
        IntegrationEnvelope<AccountsData> response = providerClient.listAccounts();
        requireSuccess(response.outcome(), response.error() == null ? null : response.error().code());
        List<com.myshop.saleschannel.model.SalesChannelDtos.SalesChannelAccount> remote =
                response.data() == null || response.data().accounts() == null
                        ? List.of() : response.data().accounts();
        for (var account : remote) {
            if (blank(account.providerAccountId())) continue;
            Map<String, Object> capabilities = account.capabilities() == null
                    ? accountRepository.findByProviderAndProviderAccountId(
                            SalesChannelProvider.XIANYU, account.providerAccountId())
                            .map(SalesChannelAccount::getCapabilities).orElse(Map.of())
                    : objectMapper.convertValue(account.capabilities(), new TypeReference<>() { });
            SalesChannelAccountStatus status = mapStatus(account.status(), account.enabled());
            accountService.upsert(SalesChannelProvider.XIANYU, account.providerAccountId(),
                    account.displayName(), status, capabilities, parseProviderTimestamp(account.lastLoginAt()), null);
        }
        List<SalesChannelAccount> localAccounts = accountRepository.findAll().stream()
                .filter(local -> local.getProvider() == SalesChannelProvider.XIANYU).toList();
        return accountSummariesWithAgentState(localAccounts, remote);
    }

    public AccountSummary verifyAccount(UUID accountId) {
        SalesChannelAccount account = findAccount(accountId);
        IntegrationEnvelope<CapabilityData> response = providerClient.verifyAccount(account.getProviderAccountId());
        requireSuccess(response.outcome(), response.error() == null ? null : response.error().code());
        if (response.data() == null || response.data().capabilities() == null) {
            throw new IllegalStateException("Xianyu Agent returned no verified account capabilities");
        }
        Map<String, Object> capabilities = objectMapper.convertValue(response.data().capabilities(),
                new TypeReference<>() { });
        SalesChannelAccount updated = accountService.upsert(account.getProvider(), account.getProviderAccountId(),
                account.getDisplayName(), SalesChannelAccountStatus.CONNECTED, capabilities,
                account.getLastLoginAt(), LocalDateTime.now());
        return accountSummary(updated);
    }

    public List<ListingSummary> productListings(UUID productId) {
        return listingRepository.findByProductIdOrderByCreatedAtAsc(productId).stream()
                .map(this::listingSummary).toList();
    }

    public ListingSummary updateXianyuListing(UUID productId, UUID accountId, XianyuProviderConfig config) {
        SalesChannelAccount account = findAccount(accountId);
        if (account.getProvider() != SalesChannelProvider.XIANYU) {
            throw new IllegalArgumentException("The selected account is not a Xianyu account");
        }
        SalesChannelListing listing = listingService.ensureDraft(accountId, productId);
        planner.saveConfig(listing.getId(), config);
        return listingSummary(planner.refresh(listing.getId()));
    }

    public ListingSummary enableListing(UUID listingId) {
        return listingSummary(planner.setEnabled(listingId, true));
    }

    public ListingSummary disableListing(UUID listingId) {
        return listingSummary(planner.setEnabled(listingId, false));
    }

    public ListingSummary retryListing(UUID listingId) {
        planner.retry(listingId);
        SalesChannelListing listing = listingRepository.findById(listingId)
                .orElseThrow(() -> new IllegalArgumentException("Sales channel listing does not exist"));
        return listingSummary(listing);
    }

    public SpecMappingSyncResult syncSpecMappings(UUID listingId) {
        return specMappingSyncService.sync(listingId);
    }

    public List<SalesChannelOrderSummary> salesChannelOrders(String provider, UUID accountId,
                                                              String externalStatus, String ingestStatus,
                                                              String deliveryStatus, String keyword,
                                                              LocalDateTime from, LocalDateTime to) {
        return channelOrderRepository.findAll().stream()
                .filter(row -> blank(provider) || row.getProvider().name().equalsIgnoreCase(provider))
                .filter(row -> accountId == null || accountId.equals(row.getSalesChannelAccountId()))
                .filter(row -> blank(externalStatus) || row.getExternalStatus().equalsIgnoreCase(externalStatus))
                .filter(row -> blank(ingestStatus) || row.getIngestStatus().name().equalsIgnoreCase(ingestStatus))
                .filter(row -> blank(deliveryStatus) || row.getDeliveryStatus().name().equalsIgnoreCase(deliveryStatus))
                .filter(row -> from == null || !row.getSourceUpdatedAt().isBefore(from))
                .filter(row -> to == null || !row.getSourceUpdatedAt().isAfter(to))
                .filter(row -> blank(keyword) || matchesKeyword(row, keyword))
                .sorted(java.util.Comparator.comparing(SalesChannelOrder::getSourceUpdatedAt).reversed())
                .map(this::orderSummary)
                .toList();
    }

    public SalesChannelOrderDetail salesChannelOrder(UUID id) {
        SalesChannelOrder row = channelOrderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Sales-channel order does not exist"));
        List<SalesChannelDeliveryOperationSummary> operations = deliveryOperationRepository
                .findBySalesChannelOrderIdOrderByCreatedAtAsc(id).stream().map(operation ->
                        new SalesChannelDeliveryOperationSummary(operation.getId().toString(),
                                operation.getOperationType().name(), operation.getUnitIndex(),
                                operation.getCardKeyId() == null ? null : operation.getCardKeyId().toString(),
                                operation.getStatus().name(), operation.getRequestId(), operation.getAttemptCount(),
                                operation.getStartedAt(), operation.getFinishedAt(), operation.getLastErrorCode(),
                                operation.getLastErrorMessage())).toList();
        return new SalesChannelOrderDetail(orderSummary(row), operations);
    }

    private SalesChannelOrderSummary orderSummary(SalesChannelOrder row) {
        Order order = row.getOrderId() == null ? null : orderRepository.findById(row.getOrderId()).orElse(null);
        OrderItem item = order == null ? null : orderItemRepository.findByOrderId(order.getId()).stream().findFirst().orElse(null);
        SalesChannelAccount account = accountRepository.findById(row.getSalesChannelAccountId()).orElse(null);
        return new SalesChannelOrderSummary(row.getId().toString(), row.getProvider().name(),
                account == null ? row.getSalesChannelAccountId().toString() : account.getProviderAccountId(),
                row.getExternalOrderNo(), order == null || order.getOrderNo() == null ? null : order.getOrderNo().toString(),
                item == null ? null : item.getProductTitle(), item == null ? null : item.getSpecName(), row.getQuantity(),
                row.getAmount(), row.getCurrency(), row.getExternalStatus(), row.getIngestStatus().name(),
                row.getDeliveryStatus().name(), row.getLastErrorCode(), row.getLastErrorMessage(),
                row.getSourceUpdatedAt(), row.isAutoFulfillmentRequested());
    }

    private boolean matchesKeyword(SalesChannelOrder row, String keyword) {
        String lower = keyword.toLowerCase();
        if (row.getExternalOrderNo().toLowerCase().contains(lower)) return true;
        if (row.getOrderId() == null) return false;
        return orderItemRepository.findByOrderId(row.getOrderId()).stream()
                .anyMatch(item -> item.getProductTitle() != null && item.getProductTitle().toLowerCase().contains(lower));
    }

    private AccountSummary accountSummary(SalesChannelAccount account) {
        return accountSummary(account, null);
    }

    private AccountSummary accountSummary(SalesChannelAccount account,
            com.myshop.saleschannel.model.SalesChannelDtos.SalesChannelAccount agentAccount) {
        return new AccountSummary(account.getId().toString(), account.getProviderAccountId(),
                account.getDisplayName(), accountType(account), account.getStatus().name(), account.isEnabled(),
                account.isOrderSyncEnabled(),
                account.getCapabilities(), account.getLastVerifiedAt(),
                agentAccount == null || blank(agentAccount.lastLoginAt())
                        ? account.getLastLoginAt() : parseProviderTimestamp(agentAccount.lastLoginAt()),
                agentAccount == null ? null : parseProviderTimestamp(agentAccount.lastRefreshAt()),
                agentAccount == null ? null : agentAccount.operationalStatus(),
                agentAccount == null ? null : agentAccount.statusSource(),
                normalizeAuthState(agentAccount == null ? null : agentAccount.authState()),
                agentAccount == null ? null : agentAccount.authStateSource(),
                agentAccount == null ? null : parseProviderTimestamp(agentAccount.authStateObservedAt()));
    }

    private List<AccountSummary> accountSummariesWithAgentState(
            List<SalesChannelAccount> localAccounts,
            List<com.myshop.saleschannel.model.SalesChannelDtos.SalesChannelAccount> agentAccounts) {
        Map<String, com.myshop.saleschannel.model.SalesChannelDtos.SalesChannelAccount> byProviderId =
                new HashMap<>();
        for (var agentAccount : agentAccounts) {
            if (agentAccount != null && !blank(agentAccount.providerAccountId())) {
                byProviderId.put(agentAccount.providerAccountId(), agentAccount);
            }
        }
        return localAccounts.stream()
                .map(account -> accountSummary(account, byProviderId.get(account.getProviderAccountId())))
                .toList();
    }

    private List<com.myshop.saleschannel.model.SalesChannelDtos.SalesChannelAccount>
            readAgentAccountsForAdminView() {
        try {
            IntegrationEnvelope<AccountsData> response = providerClient.listAccounts();
            if (response == null || response.outcome() != Outcome.SUCCEEDED
                    || response.data() == null || response.data().accounts() == null) {
                return List.of();
            }
            return response.data().accounts();
        } catch (RuntimeException unavailable) {
            return List.of();
        }
    }

    private String normalizeAuthState(String value) {
        if ("VALID".equals(value) || "AUTH_REQUIRED".equals(value)
                || "VERIFICATION_REQUIRED".equals(value)) {
            return value;
        }
        return "UNKNOWN";
    }

    private LocalDateTime parseProviderTimestamp(String value) {
        if (blank(value)) return null;
        try {
            return OffsetDateTime.parse(value).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(value);
            } catch (DateTimeParseException invalid) {
                return null;
            }
        }
    }

    private ListingSummary listingSummary(SalesChannelListing listing) {
        SalesChannelAccount account = findAccount(listing.getSalesChannelAccountId());
        XianyuProviderConfig config = objectMapper.convertValue(listing.getProviderConfig(), XianyuProviderConfig.class);
        List<SalesChannelListingGeneration> generations = generationRepository
                .findAllBySalesChannelListingIdOrderByGenerationNoAsc(listing.getId());
        SalesChannelListingGeneration current = generations.stream()
                .filter(generation -> generation.getGenerationState()
                        == com.myshop.constant.SalesChannelGenerationState.CURRENT)
                .findFirst().orElse(null);
        return new ListingSummary(listing.getId().toString(), account.getProviderAccountId(),
                account.getDisplayName(), accountType(account), listing.getManagementMode().name(),
                listing.isEnabled(), listing.isAutoFulfillmentEnabled(), listing.getStatus().name(),
                current == null ? null : current.getExternalItemId(), listing.getDesiredRevision(),
                listing.getLastSyncedAt(), listing.getLastErrorCode(), listing.getLastErrorMessage(), config,
                specMappingSummaries(listing, current),
                current == null ? null : current.getExternalItemId(),
                current == null ? null : current.getId().toString(),
                current == null ? null : current.getGenerationNo(), generations.size(), listing.getAppliedRevision(),
                listing.getConvergenceCutoverState(),
                listing.getConvergenceCertificationId() == null ? null : listing.getConvergenceCertificationId().toString(),
                listing.getConvergenceActivatedAt(), listing.getConvergencePausedAt());
    }

    private List<ListingSpecMappingSummary> specMappingSummaries(SalesChannelListing listing,
                                                                  SalesChannelListingGeneration current) {
        Map<UUID, SalesChannelListingGenerationSpec> mappings = current == null ? Map.of()
                : generationSpecRepository
                .findBySalesChannelListingGenerationIdOrderByCreatedAtAsc(current.getId()).stream()
                .collect(java.util.stream.Collectors.toMap(SalesChannelListingGenerationSpec::getProductSpecId,
                        value -> value, (left, right) -> left));
        return productSpecRepository.findByProductIdAndIsDeletedOrderBySortOrderAsc(listing.getProductId(), 0).stream()
                .map(spec -> {
                    SalesChannelListingGenerationSpec mapping = mappings.get(spec.getId());
                    Map<String, Object> provider = mapping == null ? Map.of() : mapping.getProviderMapping();
                    Object providerName = provider.get("provider_spec_name");
                    Object providerValue = provider.get("provider_spec_value");
                    return new ListingSpecMappingSummary(spec.getId().toString(), spec.getName(),
                            providerName == null ? null : String.valueOf(providerName),
                            providerValue == null ? null : String.valueOf(providerValue),
                            mapping == null ? null : mapping.getExternalSkuId());
                })
                .toList();
    }

    @org.springframework.transaction.annotation.Transactional
    public void initializeOrderCursor(UUID accountId) {
        SalesChannelAccount account = findAccount(accountId);
        var response = providerClient.getOrderWatermark(new WatermarkCommand(
                UUID.randomUUID().toString(), List.of(account.getProviderAccountId())));
        requireSuccess(response.outcome(), response.error() == null ? null : response.error().code());
        SalesChannelOrderCursor cursor = cursorRepository.findByProviderAndSalesChannelAccountId(
                account.getProvider(), account.getId()).orElseGet(SalesChannelOrderCursor::new);
        cursor.setProvider(account.getProvider());
        cursor.setSalesChannelAccountId(account.getId());
        OrderCursor value = response.data() == null ? null : response.data().cursor();
        // An empty watermark is still a real initialized boundary. Use the
        // initialization instant so enabling sync later cannot replay history.
        cursor.setCursorUpdatedAt(value == null ? LocalDateTime.now() : parseCursorTime(value));
        cursor.setCursorId(value == null ? 0L : value.id());
        cursor.setInitializedAt(LocalDateTime.now());
        cursorRepository.save(cursor);
    }

    @org.springframework.transaction.annotation.Transactional
    public AccountSummary setOrderSyncEnabled(UUID accountId, boolean enabled) {
        SalesChannelAccount account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Sales channel account does not exist"));
        if (enabled && cursorRepository.findByProviderAndSalesChannelAccountId(
                account.getProvider(), account.getId()).isEmpty()) {
            throw new IllegalStateException("Initialize the order cursor before enabling order sync");
        }
        account.setOrderSyncEnabled(enabled);
        return accountSummary(accountRepository.save(account));
    }

    @org.springframework.transaction.annotation.Transactional
    public ListingSummary setAutoFulfillmentEnabled(UUID listingId, boolean enabled) {
        SalesChannelListing listing = listingRepository.findByIdForUpdate(listingId)
                .orElseThrow(() -> new IllegalArgumentException("Sales channel listing does not exist"));
        if (generationRepository.findCandidateByListingId(listingId).isPresent()) {
            throw new IllegalStateException("REPLACEMENT_PENDING: candidate generation activation is required first");
        }
        SalesChannelAccount account = findAccount(listing.getSalesChannelAccountId());
        Product product = productRepository.findById(listing.getProductId())
                .orElseThrow(() -> new IllegalArgumentException("Product does not exist"));
        if (enabled) {
            SalesChannelListingManagementPolicy.requireProviderWritesAllowed(listing);
            if (!SalesChannelListingManagementPolicy.autoFulfillmentAllowed(listing)) {
                throw new BusinessException(ErrorCode.LINKED_ITEM_NOT_MANAGED,
                        "该闲鱼商品尚未完成 MyShop 接管，不能开启自动发货", HttpStatus.CONFLICT);
            }
            if (listing.getStatus() != com.myshop.constant.SalesChannelListingStatus.ONLINE
                    || listing.getAppliedRevision() != listing.getDesiredRevision()) {
                throw new IllegalStateException("Auto fulfillment requires an applied ONLINE desired listing revision");
            }
            if (account.getStatus() != com.myshop.constant.SalesChannelAccountStatus.CONNECTED
                    || account.getLastVerifiedAt() == null || !"FISH_SHOP".equals(accountType(account))) {
                throw new IllegalStateException("Auto fulfillment requires a verified FISH_SHOP account");
            }
            if (!product.isEnabled() || !Integer.valueOf(0).equals(product.getIsDeleted())) {
                throw new IllegalStateException("Auto fulfillment requires an enabled, non-deleted product");
            }
            if (!"AUTO".equalsIgnoreCase(product.getDeliveryType()) || product.getStockMode() != StockMode.CARD_KEY) {
                throw new IllegalStateException("Phase 4 auto fulfillment requires deliveryType=AUTO and stockMode=CARD_KEY");
            }
            if (!product.isSpecEnabled() && product.getCostPrice() == null) {
                throw new IllegalStateException(
                        "Phase 4 auto fulfillment requires a non-null product cost price for spec-disabled products");
            }
            if (product.isSpecEnabled()) {
                List<ProductSpec> activeSpecs = productSpecRepository
                        .findByProductIdAndIsDeletedOrderBySortOrderAsc(product.getId(), 0);
                if (activeSpecs.isEmpty() || activeSpecs.stream().anyMatch(spec -> spec.getCostPrice() == null)) {
                    throw new IllegalStateException("Auto fulfillment requires a cost price for every active product specification");
                }
            }
        }
        listing.setAutoFulfillmentEnabled(enabled);
        return listingSummary(listingRepository.save(listing));
    }

    private LocalDateTime parseCursorTime(OrderCursor cursor) {
        if (cursor == null || cursor.updatedAt() == null || cursor.updatedAt().isBlank()) return null;
        return java.time.OffsetDateTime.parse(cursor.updatedAt().replace("Z", "+00:00")).toLocalDateTime();
    }

    private SalesChannelAccount findAccount(UUID id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Sales channel account does not exist"));
    }

    private String accountType(SalesChannelAccount account) {
        Object type = account.getCapabilities().get("account_type");
        return type == null ? "UNKNOWN" : type.toString().toUpperCase();
    }

    private SalesChannelAccountStatus mapStatus(String status, Boolean enabled) {
        if (Boolean.FALSE.equals(enabled)) return SalesChannelAccountStatus.DISABLED;
        String normalized = status == null ? "" : status.trim().toLowerCase();
        return switch (normalized) {
            case "active", "connected", "online", "ready", "verified" -> SalesChannelAccountStatus.CONNECTED;
            case "auth_required", "login_required", "expired", "logged_out" -> SalesChannelAccountStatus.AUTH_REQUIRED;
            case "suspended", "blocked", "banned" -> SalesChannelAccountStatus.SUSPENDED;
            case "disabled" -> SalesChannelAccountStatus.DISABLED;
            default -> SalesChannelAccountStatus.ERROR;
        };
    }

    private void requireSuccess(Outcome outcome, String code) {
        if (outcome != Outcome.SUCCEEDED) {
            throw new IllegalStateException("Xianyu Agent admin request failed" + (blank(code) ? "" : ": " + code));
        }
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
}
