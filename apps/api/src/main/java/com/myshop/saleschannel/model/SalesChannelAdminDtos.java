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
            @JsonProperty("lastVerifiedAt") LocalDateTime lastVerifiedAt) {
        /** Backward-compatible constructor for callers predating Phase 4. */
        public AccountSummary(String id, String providerAccountId, String displayName,
                              String accountType, String status, boolean enabled,
                              Map<?, ?> capabilities, LocalDateTime lastVerifiedAt) {
            this(id, providerAccountId, displayName, accountType, status, enabled, false,
                    capabilities == null ? Map.of() : capabilities.entrySet().stream()
                            .collect(java.util.stream.Collectors.toMap(
                                    entry -> String.valueOf(entry.getKey()), Map.Entry::getValue)),
                    lastVerifiedAt);
        }
    }

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
            @JsonProperty("specMappings") List<ListingSpecMappingSummary> specMappings) {
        /** Backward-compatible constructor for callers predating Phase 4. */
        public ListingSummary(String id, String providerAccountId, String displayName,
                              String accountType, boolean isEnabled, String status,
                              String externalItemId, long desiredRevision, LocalDateTime lastSyncedAt,
                              String lastErrorCode, String lastErrorMessage,
                              XianyuProviderConfig providerConfig) {
            this(id, providerAccountId, displayName, accountType, isEnabled, false, status,
                    externalItemId, desiredRevision, lastSyncedAt, lastErrorCode,
                    lastErrorMessage, providerConfig, List.of());
        }
    }

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
