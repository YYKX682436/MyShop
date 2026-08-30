package com.myshop.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record UserNumberConfigResponse(
        Long startUserNo,
        Long nextUserNo,
        boolean hasExistingUsers
) {
}
