package com.myshop.model.request;

import java.math.BigDecimal;
import java.util.UUID;

public record AdminProductSpecRequest(
        UUID id,
        String name,
        BigDecimal costPrice,
        BigDecimal retailPrice,
        Boolean visible,
        Integer sortOrder,
        Integer manualStock
) {
}
