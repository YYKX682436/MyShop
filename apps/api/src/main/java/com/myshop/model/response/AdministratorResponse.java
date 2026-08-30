package com.myshop.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.myshop.constant.UserRole;

import java.time.LocalDateTime;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record AdministratorResponse(
        UUID id,
        String username,
        String email,
        String phone,
        UserRole role,
        int isDeleted,
        LocalDateTime createdAt
) {
}
