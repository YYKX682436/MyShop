package com.myshop.model.response;

import java.math.BigDecimal;
import java.util.UUID;

public record AdminPriceOverrideResponse(
        UUID id,
        UUID userLevelId,
        UUID specId,
        BigDecimal price
) {
}
