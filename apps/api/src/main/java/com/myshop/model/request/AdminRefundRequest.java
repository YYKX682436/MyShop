package com.myshop.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

/** The client can confirm, explain, and explicitly request safe key reclaim. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record AdminRefundRequest(
        @NotNull(message = "必须明确确认退款")
        Boolean confirmed,
        String reason,
        @JsonProperty("reclaim_card_keys") Boolean reclaimCardKeys
) {
    public AdminRefundRequest {
        reclaimCardKeys = Boolean.TRUE.equals(reclaimCardKeys);
    }

    @JsonAnySetter
    public void rejectUnknownProperty(String ignoredName, Object ignoredValue) {
        throw new IllegalArgumentException("退款请求包含不支持的字段");
    }
}
