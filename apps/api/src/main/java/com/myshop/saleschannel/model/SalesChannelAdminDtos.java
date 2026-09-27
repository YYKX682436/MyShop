package com.myshop.saleschannel.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.myshop.saleschannel.xianyu.model.XianyuProviderConfig;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SalesChannelAdminDtos {
    private SalesChannelAdminDtos() { }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record AccountSummary(
            @JsonProperty("id") String id,
            @JsonProperty("providerAccountId") String providerAccountId,
            @JsonProperty("displayName") String displayName,
            @JsonProperty("accountType") String accountType,
            @JsonProperty("status") String status,
            @JsonProperty("enabled") Boolean enabled,
            @JsonProperty("orderSyncEnabled") Boolean orderSyncEnabled,
            @JsonProperty("capabilities") Map<String, Object> capabilities,
            @JsonProperty("lastVerifiedAt") LocalDateTime lastVerifiedAt,
            @JsonProperty("lastLoginAt") LocalDateTime lastLoginAt) { }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record XianyuItemsPage(
            @JsonProperty("accountId") String accountId,
            @JsonProperty("items") List<XianyuCatalogItemSummary> items,
            @JsonProperty("page") int page,
            @JsonProperty("pageSize") int pageSize,
            @JsonProperty("total") long total,
            @JsonProperty("totalPages") int totalPages) { }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record XianyuCatalogItemSummary(
            @JsonProperty("accountId") String accountId,
            @JsonProperty("itemId") String itemId,
            @JsonProperty("title") String title,
            @JsonProperty("price") String price,
            @JsonProperty("quantity") Integer quantity,
            @JsonProperty("statusDesc") String statusDesc,
            @JsonProperty("skuCount") int skuCount,
            @JsonProperty("isMultiSpec") boolean multiSpec,
            @JsonProperty("isSellerItem") boolean sellerItem,
            @JsonProperty("catalogCreatedAt") String catalogCreatedAt,
            @JsonProperty("catalogUpdatedAt") String catalogUpdatedAt,
            @JsonProperty("bindingState") String bindingState,
            @JsonProperty("managementMode") String managementMode,
            @JsonProperty("listingId") String listingId,
            @JsonProperty("productId") String productId,
            @JsonProperty("productTitle") String productTitle,
            @JsonProperty("bindings") List<XianyuItemBinding> bindings) { }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record XianyuItemBinding(
            @JsonProperty("listingId") String listingId,
            @JsonProperty("productId") String productId,
            @JsonProperty("productTitle") String productTitle,
            @JsonProperty("managementMode") String managementMode) { }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record XianyuLinkCandidate(
            @JsonProperty("productId") String productId,
            @JsonProperty("productNo") Long productNo,
            @JsonProperty("title") String title,
            @JsonProperty("isEnabled") boolean enabled,
            @JsonProperty("eligibility") String eligibility,
            @JsonProperty("existingListingId") String existingListingId,
            @JsonProperty("existingExternalItemId") String existingExternalItemId,
            @JsonProperty("managementMode") String managementMode,
            @JsonProperty("reasonCode") String reasonCode) { }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record XianyuLinkCandidatesPage(
            @JsonProperty("accountId") String accountId,
            @JsonProperty("itemId") String itemId,
            @JsonProperty("candidates") List<XianyuLinkCandidate> candidates,
            @JsonProperty("page") int page,
            @JsonProperty("pageSize") int pageSize,
            @JsonProperty("total") long total,
            @JsonProperty("totalPages") int totalPages) { }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record BindXianyuItemRequest(
            @JsonProperty("productId") @NotNull UUID productId) { }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record XianyuItemBindingResponse(
            @JsonProperty("itemId") String itemId,
            @JsonProperty("listingId") String listingId,
            @JsonProperty("productId") String productId,
            @JsonProperty("productTitle") String productTitle,
            @JsonProperty("managementMode") String managementMode,
            @JsonProperty("result") String result) { }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record XianyuTakeoverResponse(
            @JsonProperty("itemId") String itemId,
            @JsonProperty("listingId") String listingId,
            @JsonProperty("productId") String productId,
            @JsonProperty("managementMode") String managementMode,
            @JsonProperty("result") String result) { }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record XianyuItemSyncSummary(
            @JsonProperty("accountId") String accountId,
            @JsonProperty("fetchedCount") int fetchedCount,
            @JsonProperty("savedCount") int savedCount,
            @JsonProperty("pages") int pages,
            @JsonProperty("skipped") boolean skipped) { }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record ListingSummary(
            @JsonProperty("id") String id,
            @JsonProperty("providerAccountId") String providerAccountId,
            @JsonProperty("displayName") String displayName,
            @JsonProperty("accountType") String accountType,
            @JsonProperty("managementMode") String managementMode,
            @JsonProperty("isEnabled") Boolean isEnabled,
            @JsonProperty("autoFulfillmentEnabled") Boolean autoFulfillmentEnabled,
            @JsonProperty("status") String status,
            @JsonProperty("externalItemId") String externalItemId,
            @JsonProperty("desiredRevision") Long desiredRevision,
            @JsonProperty("lastSyncedAt") LocalDateTime lastSyncedAt,
            @JsonProperty("lastErrorCode") String lastErrorCode,
            @JsonProperty("lastErrorMessage") String lastErrorMessage,
            @JsonProperty("providerConfig") XianyuProviderConfig providerConfig,
            @JsonProperty("specMappings") List<ListingSpecMappingSummary> specMappings) { }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record ListingSpecMappingSummary(
            @JsonProperty("productSpecId") String productSpecId,
            @JsonProperty("productSpecName") String productSpecName,
            @JsonProperty("providerSpecName") String providerSpecName,
            @JsonProperty("providerSpecValue") String providerSpecValue,
            @JsonProperty("externalSkuId") String externalSkuId) { }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record SpecMappingSyncResult(
            @JsonProperty("listingId") String listingId,
            @JsonProperty("itemId") String itemId,
            @JsonProperty("matched") int matched,
            @JsonProperty("mappings") List<ListingSpecMappingSummary> mappings) { }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record UpdateXianyuListingRequest(
            @JsonProperty("accountId") UUID accountId,
            @JsonProperty("config") @Valid XianyuProviderConfig config) { }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record AddressSearchRequest(
            @JsonProperty("keywords") @NotBlank @Size(max = 100) String keywords,
            @JsonProperty("city") @Size(max = 100) String city) { }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record CategoryRecommendRequest(
            @JsonProperty("accountId") @NotBlank @Size(max = 128) String accountId,
            @JsonProperty("title") @NotBlank @Size(max = 200) String title,
            @JsonProperty("description") @Size(max = 1500) String description,
            @JsonProperty("cardList") @Size(max = 100) List<Map<String, Object>> cardList,
            @JsonProperty("selectedCategory") Map<String, Object> selectedCategory) {
        public CategoryRecommendRequest {
            cardList = cardList == null ? List.of() : List.copyOf(cardList);
        }
    }

    public record XianyuQrLoginStartRequest(
            @JsonProperty("request_id") @NotBlank @Size(max = 36) String requestId) { }

    public record XianyuQrLoginStatusRequest(
            @JsonProperty("request_id") @NotBlank @Size(max = 36) String requestId,
            @JsonProperty("login_session_id") @NotBlank @Size(max = 80) String loginSessionId) { }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record XianyuQrLoginStartResponse(
            @JsonProperty("loginSessionId") String loginSessionId,
            @JsonProperty("qrCodeDataUrl") String qrCodeDataUrl,
            @JsonProperty("expiresInSeconds") Integer expiresInSeconds) { }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record XianyuQrLoginStatusResponse(
            @JsonProperty("status") String status,
            @JsonProperty("accountId") String accountId,
            @JsonProperty("isNewAccount") Boolean isNewAccount,
            @JsonProperty("verificationMessage") String verificationMessage,
            @JsonProperty("verificationQrCodeDataUrl") String verificationQrCodeDataUrl) { }

    public record SalesChannelOrderSummary(
            String id,
            String provider,
            String accountId,
            String externalOrderNo,
            String myshopOrderNo,
            String productTitle,
            String specName,
            int quantity,
            java.math.BigDecimal amount,
            String currency,
            String externalStatus,
            String ingestStatus,
            String deliveryStatus,
            String lastErrorCode,
            String lastErrorMessage,
            java.time.LocalDateTime sourceUpdatedAt,
            boolean autoFulfillmentRequested) { }

    public record SalesChannelDeliveryOperationSummary(
            String id,
            String operationType,
            Integer unitIndex,
            String cardKeyId,
            String status,
            String requestId,
            int attemptCount,
            java.time.LocalDateTime startedAt,
            java.time.LocalDateTime finishedAt,
            String lastErrorCode,
            String lastErrorMessage) { }

    public record SalesChannelOrderDetail(
            SalesChannelOrderSummary order,
            List<SalesChannelDeliveryOperationSummary> deliveryOperations) { }
}
