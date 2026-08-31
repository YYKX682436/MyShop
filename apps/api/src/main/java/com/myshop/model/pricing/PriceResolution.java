package com.myshop.model.pricing;

import com.myshop.constant.PricingSource;

import java.math.BigDecimal;
import java.util.UUID;

/** Server-owned price result shared by public products, admin previews, and orders. */
public record PriceResolution(
        BigDecimal price,
        BigDecimal costPrice,
        BigDecimal retailPrice,
        PricingSource pricingSource,
        boolean retailCapped,
        BigDecimal calculatedPrice,
        UUID userLevelId,
        String userLevelName
) {
}
