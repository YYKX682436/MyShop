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
            @JsonProperty("capabilities") Map<String, Object> capabilities,
            @JsonProperty("lastVerifiedAt") LocalDateTime lastVerifiedAt) { }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record ListingSummary(
            @JsonProperty("id") String id,
            @JsonProperty("providerAccountId") String providerAccountId,
            @JsonProperty("displayName") String displayName,
            @JsonProperty("accountType") String accountType,
            @JsonProperty("isEnabled") Boolean isEnabled,
            @JsonProperty("status") String status,
            @JsonProperty("externalItemId") String externalItemId,
            @JsonProperty("desiredRevision") Long desiredRevision,
            @JsonProperty("lastSyncedAt") LocalDateTime lastSyncedAt,
            @JsonProperty("lastErrorCode") String lastErrorCode,
            @JsonProperty("lastErrorMessage") String lastErrorMessage,
            @JsonProperty("providerConfig") XianyuProviderConfig providerConfig) { }

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
}
