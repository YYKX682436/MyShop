package com.myshop.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.UUID;

/** A category node. Only root nodes are returned by the category endpoints. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CategoryResponse(
        UUID id,
        String name,
        @JsonProperty("sort_order") int sortOrder,
        @JsonProperty("parent_id") UUID parentId,
        List<CategoryResponse> children
) {
}
