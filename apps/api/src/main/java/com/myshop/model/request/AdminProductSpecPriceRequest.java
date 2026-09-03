package com.myshop.model.request;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.UUID;

/** Price-only fields for one existing product specification. */
public record AdminProductSpecPriceRequest(
        @JsonProperty("spec_id") UUID specId,
        @JsonProperty("cost_price") BigDecimal costPrice,
        @JsonProperty("retail_price") BigDecimal retailPrice
) {
}
