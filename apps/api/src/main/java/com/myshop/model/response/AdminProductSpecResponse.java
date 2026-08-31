package com.myshop.model.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.myshop.constant.PricingSource;

import java.math.BigDecimal;
import java.util.UUID;

public record AdminProductSpecResponse(
        UUID id,
        String name,
        BigDecimal costPrice,
        BigDecimal retailPrice,
        BigDecimal margin,
        BigDecimal marginRate,
        @JsonProperty("is_visible") boolean visible,
        int sortOrder,
        int isDeleted,
        Long stockAvailable,
        boolean unlimited,
        Integer manualStock,
        long cardKeyCount
) {
}
