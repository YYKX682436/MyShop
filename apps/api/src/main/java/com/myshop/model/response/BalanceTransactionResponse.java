package com.myshop.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.myshop.constant.BalanceTransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record BalanceTransactionResponse(
        UUID id,
        BalanceTransactionType type,
        BigDecimal amount,
        BigDecimal delta,
        BigDecimal balanceBefore,
        BigDecimal balanceAfter,
        String reason,
        UUID operatorId,
        @JsonProperty("operator_username") String operatorUsername,
        UUID orderId,
        LocalDateTime createdAt
) {
}
