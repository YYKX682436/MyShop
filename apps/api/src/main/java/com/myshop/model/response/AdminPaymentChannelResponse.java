package com.myshop.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Admin payment channel view. configData contains only masked sensitive
 * values, as produced by the admin service.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminPaymentChannelResponse(
        UUID id,
        String channelCode,
        String channelName,
        String providerType,
        String paymentType,
        Map<String, Object> configData,
        String paymentHint,
        String badgeText,
        boolean isEnabled,
        int sortOrder,
        LocalDateTime createdAt
) {
}
