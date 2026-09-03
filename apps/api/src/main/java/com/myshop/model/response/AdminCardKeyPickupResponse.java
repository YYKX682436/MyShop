package com.myshop.model.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.UUID;

/** The common result for both single-card and batch administrator pickup. */
public record AdminCardKeyPickupResponse(
        @JsonProperty("order_id") UUID orderId,
        int count,
        List<AdminCardKeyPickupCardResponse> cards
) {
}
