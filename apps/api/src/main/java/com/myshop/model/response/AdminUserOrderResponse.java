package com.myshop.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.myshop.constant.OrderStatus;
import com.myshop.constant.OrderType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record AdminUserOrderResponse(
        UUID id,
        @JsonProperty("order_no") Long orderNo,
        BigDecimal totalAmount,
        BigDecimal actualAmount,
        OrderStatus status,
        OrderType orderType,
        String paymentMethod,
        String email,
        LocalDateTime createdAt,
        LocalDateTime paidAt,
        LocalDateTime deliveredAt,
        List<AdminUserOrderItemResponse> items
) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AdminUserOrderItemResponse(
            UUID id,
            @JsonProperty("product_no") Long productNo,
            String productTitle,
            String specName,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal subtotal
    ) {
    }
}
