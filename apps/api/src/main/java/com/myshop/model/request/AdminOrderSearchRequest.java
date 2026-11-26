package com.myshop.model.request;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Search/filter contract for the administrator order table. */
public record AdminOrderSearchRequest(
        Integer page,
        @JsonProperty("page_size") Integer pageSize,
        String keyword,
        String status,
        @JsonProperty("created_from") String createdFrom,
        @JsonProperty("created_to") String createdTo,
        @JsonProperty("sort_direction") String sortDirection,
        @JsonProperty("attention_filter") String attentionFilter
) {
    public AdminOrderSearchRequest(Integer page, Integer pageSize, String keyword, String status,
                                   String createdFrom, String createdTo, String sortDirection) {
        this(page, pageSize, keyword, status, createdFrom, createdTo, sortDirection, null);
    }
}
