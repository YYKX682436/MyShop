package com.myshop.model.request;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/** Atomic category update used by the admin product list. */
public record AdminProductCategoryUpdateRequest(
        @JsonProperty("category_id") UUID categoryId
) {
}
