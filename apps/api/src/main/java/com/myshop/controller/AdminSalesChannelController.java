package com.myshop.controller;

import com.myshop.annotation.LogOperation;
import com.myshop.common.ApiResponse;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.AccountSummary;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.AddressSearchRequest;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.CategoryRecommendRequest;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.ListingSummary;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.SpecMappingSyncResult;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.UpdateXianyuListingRequest;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.SalesChannelOrderSummary;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.SalesChannelOrderDetail;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.XianyuQrLoginStartRequest;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.XianyuQrLoginStatusRequest;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.XianyuQrLoginStartResponse;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.XianyuQrLoginStatusResponse;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.XianyuItemsPage;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.XianyuItemSyncSummary;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.BindXianyuItemRequest;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.XianyuItemBindingResponse;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.XianyuLinkCandidatesPage;
import com.myshop.service.SalesChannelAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import java.time.LocalDateTime;

@RestController
@RequiredArgsConstructor
public class AdminSalesChannelController {

    private final SalesChannelAdminService salesChannelAdminService;

    @GetMapping("/admin/sales-channels/accounts")
    public ApiResponse<List<AccountSummary>> accounts() {
        return ApiResponse.success(salesChannelAdminService.accounts());
    }

    @GetMapping("/admin/xianyu/accounts")
    public ApiResponse<List<AccountSummary>> xianyuAccounts() {
        return ApiResponse.success(salesChannelAdminService.accounts());
    }

    @GetMapping("/admin/xianyu/items")
    public ApiResponse<XianyuItemsPage> xianyuItems(
            @RequestParam UUID accountId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "") String keyword) {
        return ApiResponse.success(salesChannelAdminService.queryXianyuItems(
                accountId, page, pageSize, keyword));
    }

    @GetMapping("/admin/xianyu/accounts/{accountId}/items/{itemId}/link-candidates")
    public ApiResponse<XianyuLinkCandidatesPage> xianyuLinkCandidates(
            @PathVariable UUID accountId,
            @PathVariable String itemId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "") String keyword) {
        return ApiResponse.success(salesChannelAdminService.queryXianyuLinkCandidates(
                accountId, itemId, page, pageSize, keyword));
    }

    @LogOperation(action = "sales_channel.item_link", targetType = "SALES_CHANNEL_ITEM",
            targetId = "#itemId", detail = "'人工关联闲鱼商品与 MyShop 商品'")
    @PutMapping("/admin/xianyu/accounts/{accountId}/items/{itemId}/binding")
    public ApiResponse<XianyuItemBindingResponse> bindXianyuItem(
            @PathVariable UUID accountId,
            @PathVariable String itemId,
            @Valid @RequestBody BindXianyuItemRequest request) {
        return ApiResponse.success(salesChannelAdminService.linkXianyuItem(
                accountId, itemId, request.productId()));
    }

    @PostMapping("/admin/xianyu/accounts/{accountId}/items/sync")
    public ApiResponse<XianyuItemSyncSummary> syncXianyuItems(@PathVariable UUID accountId) {
        return ApiResponse.success(salesChannelAdminService.syncXianyuItems(
                accountId, UUID.randomUUID().toString()));
    }

    @PostMapping("/admin/xianyu/accounts/login/qr")
    public ApiResponse<XianyuQrLoginStartResponse> startXianyuQrLogin(
            @Valid @RequestBody XianyuQrLoginStartRequest request) {
        var data = salesChannelAdminService.startQrLogin(canonicalUuid(request.requestId()));
        return ApiResponse.success(new XianyuQrLoginStartResponse(
                data.loginSessionId(), data.qrCodeDataUrl(), data.expiresInSeconds()));
    }

    @PostMapping("/admin/xianyu/accounts/login/qr/{sessionId}/status")
    public ApiResponse<XianyuQrLoginStatusResponse> xianyuQrLoginStatus(
            @PathVariable UUID sessionId,
            @Valid @RequestBody XianyuQrLoginStatusRequest request) {
        UUID requestId = UUID.fromString(request.requestId());
        UUID bodySessionId = UUID.fromString(request.loginSessionId());
        if (!sessionId.equals(bodySessionId)) {
            throw new IllegalArgumentException("QR login session path and body do not match");
        }
        var data = salesChannelAdminService.getQrLoginStatus(requestId.toString(), sessionId.toString());
        return ApiResponse.success(new XianyuQrLoginStatusResponse(
                data.status(), data.accountId(), data.isNewAccount(), data.verificationMessage(),
                data.verificationQrCodeDataUrl()));
    }

    private static String canonicalUuid(String value) {
        return UUID.fromString(value).toString();
    }

    @PostMapping("/admin/sales-channels/xianyu/metadata/address/search")
    public ApiResponse<com.myshop.saleschannel.model.SalesChannelDtos.AddressMetadataResult> searchAddress(
            @Valid @RequestBody AddressSearchRequest request) {
        return ApiResponse.success(salesChannelAdminService.searchAddress(request.keywords(), request.city()));
    }

    @PostMapping("/admin/sales-channels/xianyu/metadata/category/recommend")
    public ApiResponse<com.myshop.saleschannel.model.SalesChannelDtos.CategoryRecommendResult> recommendCategory(
            @Valid @RequestBody CategoryRecommendRequest request) {
        return ApiResponse.success(salesChannelAdminService.recommendCategory(request));
    }

    @LogOperation(action = "sales_channel.account_sync", targetType = "SALES_CHANNEL", detail = "'同步闲鱼账号'")
    @PostMapping("/admin/sales-channels/xianyu/accounts/sync")
    public ApiResponse<List<AccountSummary>> syncAccounts() {
        return ApiResponse.success(salesChannelAdminService.syncXianyuAccounts());
    }

    @LogOperation(action = "sales_channel.account_verify", targetType = "SALES_CHANNEL", targetId = "#accountId", detail = "'验证闲鱼账号能力'")
    @PostMapping("/admin/sales-channels/accounts/{accountId}/verify")
    public ApiResponse<AccountSummary> verifyAccount(@PathVariable UUID accountId) {
        return ApiResponse.success(salesChannelAdminService.verifyAccount(accountId));
    }

    @LogOperation(action = "sales_channel.order_sync_initialize", targetType = "SALES_CHANNEL_ACCOUNT", targetId = "#accountId", detail = "'初始化渠道订单游标'")
    @PostMapping("/admin/sales-channels/accounts/{accountId}/order-sync/initialize")
    public ApiResponse<Void> initializeOrderCursor(@PathVariable UUID accountId) {
        salesChannelAdminService.initializeOrderCursor(accountId);
        return ApiResponse.success(null);
    }

    @LogOperation(action = "sales_channel.order_sync_enable", targetType = "SALES_CHANNEL_ACCOUNT", targetId = "#accountId", detail = "'开启渠道订单同步'")
    @PostMapping("/admin/sales-channels/accounts/{accountId}/order-sync/enable")
    public ApiResponse<AccountSummary> enableOrderSync(@PathVariable UUID accountId) {
        return ApiResponse.success(salesChannelAdminService.setOrderSyncEnabled(accountId, true));
    }

    @PostMapping("/admin/sales-channels/accounts/{accountId}/order-sync/disable")
    public ApiResponse<AccountSummary> disableOrderSync(@PathVariable UUID accountId) {
        return ApiResponse.success(salesChannelAdminService.setOrderSyncEnabled(accountId, false));
    }

    @GetMapping("/admin/products/{productId}/sales-channels")
    public ApiResponse<List<ListingSummary>> productListings(@PathVariable UUID productId) {
        return ApiResponse.success(salesChannelAdminService.productListings(productId));
    }

    @LogOperation(action = "sales_channel.listing_config", targetType = "PRODUCT", targetId = "#productId", detail = "'更新商品闲鱼渠道配置'")
    @PutMapping("/admin/products/{productId}/sales-channels/xianyu")
    public ApiResponse<ListingSummary> updateXianyuListing(@PathVariable UUID productId,
                                                          @Valid @RequestBody UpdateXianyuListingRequest request) {
        return ApiResponse.success(salesChannelAdminService.updateXianyuListing(
                productId, request.accountId(), request.config()));
    }

    @LogOperation(action = "sales_channel.listing_enable", targetType = "SALES_CHANNEL_LISTING", targetId = "#listingId", detail = "'启用闲鱼商品'")
    @PostMapping("/admin/sales-channel-listings/{listingId}/enable")
    public ApiResponse<ListingSummary> enableListing(@PathVariable UUID listingId) {
        return ApiResponse.success(salesChannelAdminService.enableListing(listingId));
    }

    @LogOperation(action = "sales_channel.listing_disable", targetType = "SALES_CHANNEL_LISTING", targetId = "#listingId", detail = "'停止闲鱼商品销售'")
    @PostMapping("/admin/sales-channel-listings/{listingId}/disable")
    public ApiResponse<ListingSummary> disableListing(@PathVariable UUID listingId) {
        return ApiResponse.success(salesChannelAdminService.disableListing(listingId));
    }

    @LogOperation(action = "sales_channel.listing_retry", targetType = "SALES_CHANNEL_LISTING", targetId = "#listingId", detail = "'重试闲鱼商品操作'")
    @PostMapping("/admin/sales-channel-listings/{listingId}/retry")
    public ApiResponse<ListingSummary> retryListing(@PathVariable UUID listingId) {
        return ApiResponse.success(salesChannelAdminService.retryListing(listingId));
    }

    @PostMapping("/admin/sales-channel-listings/{listingId}/spec-mappings/sync")
    public ApiResponse<SpecMappingSyncResult> syncSpecMappings(@PathVariable UUID listingId) {
        return ApiResponse.success(salesChannelAdminService.syncSpecMappings(listingId));
    }

    @LogOperation(action = "sales_channel.auto_fulfillment_enable", targetType = "SALES_CHANNEL_LISTING", targetId = "#listingId", detail = "'开启渠道自动发卡'")
    @PostMapping("/admin/sales-channel-listings/{listingId}/auto-fulfillment/enable")
    public ApiResponse<ListingSummary> enableAutoFulfillment(@PathVariable UUID listingId) {
        return ApiResponse.success(salesChannelAdminService.setAutoFulfillmentEnabled(listingId, true));
    }

    @PostMapping("/admin/sales-channel-listings/{listingId}/auto-fulfillment/disable")
    public ApiResponse<ListingSummary> disableAutoFulfillment(@PathVariable UUID listingId) {
        return ApiResponse.success(salesChannelAdminService.setAutoFulfillmentEnabled(listingId, false));
    }

    @GetMapping("/admin/sales-channel-orders")
    public ApiResponse<List<SalesChannelOrderSummary>> salesChannelOrders(
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) UUID account,
            @RequestParam(required = false) String externalStatus,
            @RequestParam(required = false) String ingestStatus,
            @RequestParam(required = false) String deliveryStatus,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) LocalDateTime from,
            @RequestParam(required = false) LocalDateTime to) {
        return ApiResponse.success(salesChannelAdminService.salesChannelOrders(
                provider, account, externalStatus, ingestStatus, deliveryStatus, keyword, from, to));
    }

    @GetMapping("/admin/sales-channel-orders/{id}")
    public ApiResponse<SalesChannelOrderDetail> salesChannelOrder(@PathVariable UUID id) {
        return ApiResponse.success(salesChannelAdminService.salesChannelOrder(id));
    }
}
