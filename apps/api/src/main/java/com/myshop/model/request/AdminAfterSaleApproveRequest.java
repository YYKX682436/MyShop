package com.myshop.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.AssertTrue;

/** Explicit approval and safe card-key handling choice for an after-sale refund. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record AdminAfterSaleApproveRequest(
        @AssertTrue(message = "必须明确确认退款")
        Boolean confirmed,
        @JsonProperty("reclaim_card_keys") Boolean reclaimCardKeys
) {
    public AdminAfterSaleApproveRequest {
        reclaimCardKeys = Boolean.TRUE.equals(reclaimCardKeys);
    }
}
