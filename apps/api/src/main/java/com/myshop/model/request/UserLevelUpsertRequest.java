package com.myshop.model.request;

import com.myshop.constant.PricingType;

import java.math.BigDecimal;

public record UserLevelUpsertRequest(
        String name,
        PricingType pricingType,
        BigDecimal markupRate,
        Boolean enabled,
        Boolean isDefault,
        Integer sortOrder,
        String remark
) {
}
