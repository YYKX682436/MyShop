package com.myshop.saleschannel.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.myshop.saleschannel.xianyu.model.XianyuProviderConfig;
import jakarta.validation.Valid;

import java.time.LocalDateTime;
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
}
