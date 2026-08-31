package com.myshop.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.myshop.model.CustomerInputField;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicProductResponse(
        UUID id,
        String title,
        String description,
        String detailMd,
        String coverUrl,
        BigDecimal price,
        BigDecimal retailPrice,
        UUID categoryId,
        String categoryName,
        Long stockAvailable,
        boolean unlimited,
        boolean hasSpecs,
        String deliveryType,
        List<CustomerInputField> customerInputFields,
        String stockMode,
        long salesCount,
        int initialSales,
        boolean specEnabled,
        List<PublicProductSpecResponse> specs,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
