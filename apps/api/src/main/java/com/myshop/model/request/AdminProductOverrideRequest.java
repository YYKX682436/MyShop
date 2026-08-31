package com.myshop.model.request;

import java.math.BigDecimal;
import java.util.UUID;

public record AdminProductOverrideRequest(
        UUID userLevelId,
        UUID specId,
        BigDecimal price
) {
}
