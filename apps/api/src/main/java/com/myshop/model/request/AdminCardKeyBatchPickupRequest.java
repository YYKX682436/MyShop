package com.myshop.model.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

/** The explicit administrator batch-pickup command. */
public record AdminCardKeyBatchPickupRequest(
        @NotNull(message = "商品不能为空")
        @JsonProperty("product_id") UUID productId,
        @JsonProperty("spec_id") UUID specId,
        @NotNull(message = "提卡数量不能为空")
        @Positive(message = "提卡数量必须大于 0")
        Integer quantity
) {
}
