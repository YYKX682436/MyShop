package com.myshop.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record AdminOperationLogResponse(
        UUID id,
        UUID userId,
        String username,
        String action,
        String targetType,
        String targetId,
        String detail,
        String ipAddress,
        LocalDateTime createdAt
) {
}
