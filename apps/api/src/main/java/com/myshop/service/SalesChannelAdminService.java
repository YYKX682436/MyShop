package com.myshop.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.constant.SalesChannelAccountStatus;
import com.myshop.constant.SalesChannelProvider;
import com.myshop.entity.SalesChannelAccount;
import com.myshop.entity.SalesChannelListing;
import com.myshop.entity.Product;
import com.myshop.entity.SalesChannelOrderCursor;
import com.myshop.entity.SalesChannelOrder;
import com.myshop.entity.Order;
import com.myshop.entity.OrderItem;
import com.myshop.entity.SalesChannelDeliveryOperation;
import com.myshop.constant.StockMode;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.SalesChannelOrderCursorRepository;
import com.myshop.repository.SalesChannelOrderRepository;
import com.myshop.repository.SalesChannelDeliveryOperationRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.SalesChannelAccountRepository;
import com.myshop.repository.SalesChannelListingRepository;
import com.myshop.saleschannel.SalesChannelProviderClient;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.AccountSummary;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.CategoryRecommendRequest;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.ListingSummary;
import com.myshop.saleschannel.model.SalesChannelDtos.AccountCapabilities;
import com.myshop.saleschannel.model.SalesChannelDtos.AccountsData;
import com.myshop.saleschannel.model.SalesChannelDtos.AddressMetadataResult;
import com.myshop.saleschannel.model.SalesChannelDtos.CapabilityData;
import com.myshop.saleschannel.model.SalesChannelDtos.CategoryRecommendCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.CategoryRecommendResult;
import com.myshop.saleschannel.model.SalesChannelDtos.OrderCursor;
import com.myshop.saleschannel.model.SalesChannelDtos.WatermarkCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.WatermarkData;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.SalesChannelOrderSummary;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.SalesChannelOrderDetail;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.SalesChannelDeliveryOperationSummary;
import com.myshop.saleschannel.model.SalesChannelDtos.IntegrationEnvelope;
import com.myshop.saleschannel.model.SalesChannelDtos.Outcome;
import com.myshop.saleschannel.xianyu.model.XianyuProviderConfig;
import com.myshop.service.impl.SalesChannelAccountServiceImpl;
import com.myshop.service.impl.SalesChannelListingServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    private final ObjectMapper objectMapper;
    private final ProductRepository productRepository;
    private final SalesChannelOrderCursorRepository cursorRepository;
    private final SalesChannelOrderRepository channelOrderRepository;
    private final SalesChannelDeliveryOperationRepository deliveryOperationRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;

    public List<AccountSummary> accounts() {
        return accountRepository.findAll().stream().filter(account -> account.getProvider() == SalesChannelProvider.XIANYU)
                .map(this::accountSummary).toList();
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
                    ? Map.of() : objectMapper.convertValue(account.capabilities(), new TypeReference<>() { });
            SalesChannelAccountStatus status = mapStatus(account.status(), account.enabled());
            accountService.upsert(SalesChannelProvider.XIANYU, account.providerAccountId(),
                    account.displayName(), status, capabilities, null);
        }
        return accounts();
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
                account.getDisplayName(), SalesChannelAccountStatus.CONNECTED, capabilities, LocalDateTime.now());
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
        return new AccountSummary(account.getId().toString(), account.getProviderAccountId(),
                account.getDisplayName(), accountType(account), account.getStatus().name(), account.isEnabled(),
                account.isOrderSyncEnabled(),
                account.getCapabilities(), account.getLastVerifiedAt());
    }

    private ListingSummary listingSummary(SalesChannelListing listing) {
        SalesChannelAccount account = findAccount(listing.getSalesChannelAccountId());
        XianyuProviderConfig config = objectMapper.convertValue(listing.getProviderConfig(), XianyuProviderConfig.class);
        return new ListingSummary(listing.getId().toString(), account.getProviderAccountId(),
                account.getDisplayName(), accountType(account), listing.isEnabled(), listing.isAutoFulfillmentEnabled(), listing.getStatus().name(),
                listing.getExternalItemId(), listing.getDesiredRevision(), listing.getLastSyncedAt(),
                listing.getLastErrorCode(), listing.getLastErrorMessage(), config);
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
        SalesChannelAccount account = findAccount(listing.getSalesChannelAccountId());
        Product product = productRepository.findById(listing.getProductId())
                .orElseThrow(() -> new IllegalArgumentException("Product does not exist"));
        if (enabled) {
            if (listing.getExternalItemId() == null || listing.getExternalItemId().isBlank()) {
                throw new IllegalStateException("Only a MyShop-managed listing with an external item can enable auto fulfillment");
            }
            if (!"FISH_SHOP".equals(accountType(account))) {
                throw new IllegalStateException("Auto fulfillment requires a verified FISH_SHOP account");
            }
            if (!"AUTO".equalsIgnoreCase(product.getDeliveryType()) || product.getStockMode() != StockMode.CARD_KEY) {
                throw new IllegalStateException("Phase 4 auto fulfillment requires deliveryType=AUTO and stockMode=CARD_KEY");
            }
            if (!product.isSpecEnabled() && product.getCostPrice() == null) {
                throw new IllegalStateException(
                        "Phase 4 auto fulfillment requires a non-null product cost price for spec-disabled products");
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
