package com.myshop.controller;

import com.myshop.annotation.LogOperation;
import com.myshop.common.ApiResponse;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.AccountSummary;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.AddressSearchRequest;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.CategoryRecommendRequest;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.ListingSummary;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.UpdateXianyuListingRequest;
import com.myshop.service.SalesChannelAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class AdminSalesChannelController {

    private final SalesChannelAdminService salesChannelAdminService;

    @GetMapping("/admin/sales-channels/accounts")
    public ApiResponse<List<AccountSummary>> accounts() {
        return ApiResponse.success(salesChannelAdminService.accounts());
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
}
