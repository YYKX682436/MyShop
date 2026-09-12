package com.myshop.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.myshop.constant.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** Facts produced by the atomic administrator refund transition. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AdminOrderRefundResponse(
        @JsonProperty("order_id") UUID orderId,
        @JsonProperty("order_no") Long orderNo,
        OrderStatus status,
        @JsonProperty("refund_amount") BigDecimal refundAmount,
        @JsonProperty("refunded_at") LocalDateTime refundedAt,
        @JsonProperty("refund_destination") String refundDestination,
        @JsonProperty("balance_before") BigDecimal balanceBefore,
        @JsonProperty("balance_after") BigDecimal balanceAfter,
        @JsonProperty("reclaimed_card_key_count") int reclaimedCardKeyCount
) {
}
