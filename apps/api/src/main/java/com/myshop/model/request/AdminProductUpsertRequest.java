package com.myshop.model.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.myshop.constant.StockMode;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Complete product aggregate sent by the admin editor in one transaction. */
public record AdminProductUpsertRequest(
        String title,
        String description,
        String detailMd,
        String coverUrl,
        BigDecimal costPrice,
        BigDecimal retailPrice,
        UUID categoryId,
        Integer lowStockThreshold,
        Boolean specEnabled,
        Boolean isEnabled,
        Integer initialSales,
        @JsonProperty("sort_weight")
        Integer sortWeight,
        String deliveryType,
        List<?> customerInputFields,
        StockMode stockMode,
        Integer manualStock,
        List<AdminProductSpecRequest> specs,
        List<AdminProductOverrideRequest> overrides
) {
}
