package com.myshop.model.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.UUID;

/** One CardKey returned by an administrator pickup. */
public record AdminCardKeyPickupCardResponse(
        UUID id,
        @JsonProperty("content_parts") List<String> contentParts
) {
}
