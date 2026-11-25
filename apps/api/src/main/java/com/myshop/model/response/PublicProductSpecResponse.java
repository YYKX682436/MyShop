package com.myshop.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.List;
import com.myshop.model.CustomerInputField;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicProductSpecResponse(
        UUID id,
        String name,
        BigDecimal price,
        boolean zeroPricePurchaseAllowed,
        BigDecimal retailPrice,
        Long stockAvailable,
        boolean unlimited,
        String stockStatus,
        Integer minQuantity,
        Integer maxQuantity,
        String stockMode,
        List<CustomerInputField> supplierInputFields
) {
}
