package com.myshop.model.request;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

/**
 * Price-only product update. Root prices are used for products without
 * specifications; specification products must send the current specification
 * price set and never use the root cost price as a flattened replacement.
 */
public record AdminProductPricesUpdateRequest(
        @JsonProperty("cost_price") BigDecimal costPrice,
        @JsonProperty("retail_price") BigDecimal retailPrice,
        List<AdminProductSpecPriceRequest> specs
) {
}
