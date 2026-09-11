package com.myshop.model.request;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/** The one supported category write contract for both create and update. */
public record CategoryUpsertRequest(
        String name,
        @JsonProperty("sort_order") Integer sortOrder,
        @JsonProperty("parent_id") UUID parentId
) {
}
