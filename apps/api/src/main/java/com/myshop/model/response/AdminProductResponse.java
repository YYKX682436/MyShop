package com.myshop.model.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.myshop.model.CustomerInputField;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record AdminProductResponse(
        UUID id,
        @JsonProperty("product_no")
        Long productNo,
        String title,
        String description,
        String detailMd,
        String coverUrl,
        BigDecimal costPrice,
        BigDecimal retailPrice,
        UUID categoryId,
        String categoryName,
        UUID primaryCategoryId,
        String primaryCategoryName,
        UUID secondaryCategoryId,
        String secondaryCategoryName,
        Long stockAvailable,
        boolean unlimited,
        boolean hasSpecs,
        String deliveryType,
        List<CustomerInputField> customerInputFields,
        String stockMode,
        long salesCount,
        int initialSales,
        boolean specEnabled,
        @JsonProperty("is_enabled") boolean enabled,
        @JsonProperty("sort_weight")
        int sortWeight,
        int lowStockThreshold,
        List<AdminProductSpecResponse> specs,
        List<AdminPriceOverrideResponse> overrides,
        List<AdminPricingMatrixResponse> pricingMatrix,
        List<String> warnings,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
