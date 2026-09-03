package com.myshop.model.request;

import java.math.BigDecimal;
import java.util.UUID;

public record AdminPricingPreviewOverrideRequest(
        UUID userLevelId,
        UUID specId,
        BigDecimal price
) {
}
