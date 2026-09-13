package com.myshop.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/** Required administrator explanation when an after-sale request is denied. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record AdminAfterSaleRejectRequest(
        @NotBlank(message = "驳回说明不能为空")
        @JsonProperty("rejection_reason")
        String rejectionReason
) {
}
