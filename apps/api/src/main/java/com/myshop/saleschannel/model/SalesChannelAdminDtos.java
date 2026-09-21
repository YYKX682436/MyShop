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
            String id,
            String providerAccountId,
            String displayName,
            String accountType,
            String status,
            Boolean enabled,
            Map<String, Object> capabilities,
            LocalDateTime lastVerifiedAt) { }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record ListingSummary(
            String id,
            String providerAccountId,
            String displayName,
            String accountType,
            @JsonProperty("isEnabled") Boolean isEnabled,
            String status,
            String externalItemId,
            Long desiredRevision,
            LocalDateTime lastSyncedAt,
            String lastErrorCode,
            String lastErrorMessage,
            XianyuProviderConfig providerConfig) { }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record UpdateXianyuListingRequest(UUID accountId, @Valid XianyuProviderConfig config) { }
}
