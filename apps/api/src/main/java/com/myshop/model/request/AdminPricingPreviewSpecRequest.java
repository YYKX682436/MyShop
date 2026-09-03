package com.myshop.model.request;

import java.math.BigDecimal;
import java.util.UUID;

public record AdminPricingPreviewSpecRequest(
        UUID id,
        BigDecimal costPrice,
        BigDecimal retailPrice
) {
}
