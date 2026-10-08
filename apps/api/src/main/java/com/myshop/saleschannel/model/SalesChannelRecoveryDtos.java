package com.myshop.saleschannel.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

public final class SalesChannelRecoveryDtos {
    private SalesChannelRecoveryDtos() { }

    public record RecoveryReasonRequest(@NotBlank @Size(max = 2000) String reason) { }

    public record RecoveryPlan(
            boolean eligible,
            UUID listingId,
            UUID myshopOperationId,
            long agentOperationId,
            String accountId,
            String requestId,
            long desiredRevision,
            String desiredFingerprint,
            String expectedOldItemId,
            Map<String, Object> myshopPreconditions,
            Map<String, Object> agentPreconditions) { }

    public record RecoveryPermitSummary(
            UUID permitId,
            long generation,
            String state,
            UUID listingId,
            UUID myshopOperationId,
            long agentOperationId,
            String accountId,
            String requestId,
            long desiredRevision,
            String desiredFingerprint,
            String expectedOldItemId,
            String operator,
            String reason,
            String goOperator,
            LocalDateTime preparedAt,
            LocalDateTime armedAt,
            LocalDateTime goAt,
            LocalDateTime executionStartedAt,
            LocalDateTime terminalAt,
            String executionOutcome,
            long executionFence,
            Map<String, Object> preconditions,
            Map<String, Object> agentPreconditions) { }
}
