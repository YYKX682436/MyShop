package com.myshop.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** Safe, explicit row contract for the ordinary-user administration list. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AdminUserListResponse(
        UUID id,
        Long userNo,
        String username,
        String email,
        UUID userLevelId,
        String userLevelName,
        BigDecimal balance,
        long orderCount,
        BigDecimal totalSpent,
        int isDeleted,
        LocalDateTime createdAt
) {
    public AdminUserListResponse(UUID id, Long userNo, String username, String email,
                                 BigDecimal balance, long orderCount, BigDecimal totalSpent,
                                 int isDeleted, LocalDateTime createdAt) {
        this(id, userNo, username, email, null, null, balance, orderCount, totalSpent,
                isDeleted, createdAt);
    }
}
