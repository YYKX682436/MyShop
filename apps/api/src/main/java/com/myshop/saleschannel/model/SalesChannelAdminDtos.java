package com.myshop.saleschannel.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.myshop.saleschannel.xianyu.model.XianyuProviderConfig;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
    public record ListingSummary(
            @JsonProperty("id") String id,
            @JsonProperty("providerAccountId") String providerAccountId,
            @JsonProperty("displayName") String displayName,
            @JsonProperty("accountType") String accountType,
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
