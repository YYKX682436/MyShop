package com.myshop.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;

/** The client can confirm and explain a refund, but never choose its amount or destination. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record AdminRefundRequest(
        @NotNull(message = "必须明确确认退款")
        Boolean confirmed,
        String reason
) {
}
