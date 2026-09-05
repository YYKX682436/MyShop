package com.myshop.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * Storefront-safe payment channel data. Provider configuration is deliberately
 * not part of this DTO; in particular, a merchant key can never cross this
 * public API boundary.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicPaymentChannelResponse(
        UUID id,
        String channelCode,
        String name,
        String paymentHint,
        String badgeText,
        String type
) {
}
