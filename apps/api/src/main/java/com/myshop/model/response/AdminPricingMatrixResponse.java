package com.myshop.model.response;

import com.myshop.constant.PricingSource;
import com.myshop.constant.PricingType;

import java.math.BigDecimal;
import java.util.UUID;

public record AdminPricingMatrixResponse(
        UUID userLevelId,
        String userLevelName,
        PricingType pricingType,
        BigDecimal markupRate,
        UUID specId,
        BigDecimal defaultPrice,
        BigDecimal overridePrice,
        BigDecimal finalPrice,
        PricingSource pricingSource,
        boolean retailCapped,
        boolean belowCost
) {
}
