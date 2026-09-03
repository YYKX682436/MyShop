package com.myshop.model.response;

import com.myshop.constant.PricingPreviewStatus;
import com.myshop.constant.PricingType;

import java.math.BigDecimal;
import java.util.UUID;

public record AdminPricingPreviewRowResponse(
        UUID userLevelId,
        String userLevelName,
        PricingType pricingType,
        BigDecimal markupRate,
        UUID specId,
        BigDecimal rulePrice,
        BigDecimal overridePrice,
        BigDecimal finalPrice,
        PricingPreviewStatus pricingStatus,
        boolean retailCapped,
        boolean belowCost
) {
}
