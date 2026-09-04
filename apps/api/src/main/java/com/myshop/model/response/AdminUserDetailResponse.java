package com.myshop.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** Safe account overview used by the administrator's user detail center. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AdminUserDetailResponse(
        UUID id,
        Long userNo,
        String username,
        String email,
        String phone,
        UUID userLevelId,
        String userLevelName,
        BigDecimal balance,
        long successfulOrderCount,
        BigDecimal totalSpent,
        int isDeleted,
        LocalDateTime createdAt,
        String lastLoginIp,
        LocalDateTime lastLoginAt,
        IpLocationResponse lastLoginIpLocation
    ) {
}
