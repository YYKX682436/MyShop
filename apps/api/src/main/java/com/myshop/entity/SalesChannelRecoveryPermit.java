package com.myshop.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "sales_channel_recovery_permits")
@Getter
@NoArgsConstructor
public class SalesChannelRecoveryPermit extends BaseEntity {

    @Column(nullable = false)
    private long generation;

    @Column(name = "sales_channel_listing_id", nullable = false)
    private UUID salesChannelListingId;

    @Column(name = "myshop_operation_id", nullable = false, unique = true)
    private UUID myshopOperationId;

    @Column(name = "agent_operation_id", nullable = false)
    private long agentOperationId;

    @Column(name = "account_id", nullable = false, length = 80)
    private String accountId;

    @Column(name = "request_id", nullable = false, unique = true, length = 255)
    private String requestId;

    @Column(name = "desired_revision", nullable = false)
    private long desiredRevision;

    @Column(name = "desired_fingerprint", nullable = false, length = 64)
    private String desiredFingerprint;

    @Column(name = "expected_old_item_id", nullable = false, length = 64)
    private String expectedOldItemId;

    @Column(nullable = false, length = 32)
    private String state;

    @Column(nullable = false, length = 128)
    private String operator;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "preconditions_json", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> preconditions = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "agent_preconditions_json", columnDefinition = "jsonb")
    private Map<String, Object> agentPreconditions = new LinkedHashMap<>();

    @Column(name = "execution_outcome", length = 64)
    @Setter
    private String executionOutcome;

    @Column(name = "execution_fence", nullable = false)
    private long executionFence;

    @Column(name = "prepared_at", nullable = false)
    private LocalDateTime preparedAt;

    @Column(name = "armed_at")
    private LocalDateTime armedAt;

    @Column(name = "go_operator", length = 128)
    private String goOperator;

    @Column(name = "go_at")
    private LocalDateTime goAt;

    @Column(name = "execution_started_at")
    private LocalDateTime executionStartedAt;

    @Column(name = "terminal_at")
    private LocalDateTime terminalAt;

    public SalesChannelRecoveryPermit(long generation, UUID listingId, UUID operationId,
                                      long agentOperationId, String accountId, String requestId,
                                      long desiredRevision, String desiredFingerprint,
                                      String expectedOldItemId, String operator, String reason,
                                      Map<String, Object> preconditions, LocalDateTime preparedAt) {
        this.generation = generation;
        this.salesChannelListingId = listingId;
        this.myshopOperationId = operationId;
        this.agentOperationId = agentOperationId;
        this.accountId = accountId;
        this.requestId = requestId;
        this.desiredRevision = desiredRevision;
        this.desiredFingerprint = desiredFingerprint;
        this.expectedOldItemId = expectedOldItemId;
        this.state = "PREPARED";
        this.operator = operator;
        this.reason = reason;
        this.preconditions = new LinkedHashMap<>(preconditions);
        this.preparedAt = preparedAt;
    }

    public Map<String, Object> getPreconditions() {
        return new LinkedHashMap<>(preconditions == null ? Map.of() : preconditions);
    }

    public Map<String, Object> getAgentPreconditions() {
        return new LinkedHashMap<>(agentPreconditions == null ? Map.of() : agentPreconditions);
    }

    public void arm(Map<String, Object> agentPreconditions, LocalDateTime now) {
        if (!"PREPARED".equals(state)) throw new IllegalStateException("Only PREPARED recovery permits can be armed");
        this.agentPreconditions = new LinkedHashMap<>(agentPreconditions);
        this.state = "ARMED";
        this.armedAt = now;
    }

    /** Advance only this logical permit after the previous pre-GO generation was proven cancelled. */
    public void prepareNextGeneration(long nextGeneration, String nextOperator, String nextReason,
                                      Map<String, Object> nextPreconditions, LocalDateTime now) {
        if (!"CANCELLED".equals(state) || nextGeneration != generation + 1) {
            throw new IllegalStateException("Only the exact next generation of a cancelled permit can be prepared");
        }
        generation = nextGeneration;
        state = "PREPARED";
        operator = nextOperator;
        reason = nextReason;
        preconditions = new LinkedHashMap<>(nextPreconditions == null ? Map.of() : nextPreconditions);
        agentPreconditions = new LinkedHashMap<>();
        executionOutcome = null;
        executionFence = 0;
        preparedAt = now;
        armedAt = null;
        goOperator = null;
        goAt = null;
        executionStartedAt = null;
        terminalAt = null;
    }

    public void activate(String operator, LocalDateTime now, Map<String, Object> goPreconditions) {
        if (!"ARMED".equals(state)) throw new IllegalStateException("Only ARMED recovery permits can be activated");
        this.state = "GO_PENDING";
        this.goOperator = operator;
        this.goAt = now;
        this.executionOutcome = "GO_ACCEPTED_AGENT_ACTIVATION_PENDING";
        Map<String, Object> snapshot = new LinkedHashMap<>(preconditions == null ? Map.of() : preconditions);
        snapshot.put("go", new LinkedHashMap<>(goPreconditions));
        this.preconditions = snapshot;
    }

    public void completeGo(LocalDateTime now) {
        if (!"GO_PENDING".equals(state)) throw new IllegalStateException("Only GO_PENDING recovery permits can become ACTIVE");
        this.state = "ACTIVE";
        this.executionOutcome = "GO_ACCEPTED";
        Map<String, Object> snapshot = new LinkedHashMap<>(preconditions == null ? Map.of() : preconditions);
        snapshot.put("agent_activated_at", now.toString());
        this.preconditions = snapshot;
    }

    public long beginExecution(LocalDateTime now) {
        if (!"ACTIVE".equals(state) && !"RECOVERY_REQUIRED".equals(state)) {
            throw new IllegalStateException("Recovery permit is not claimable");
        }
        this.executionFence++;
        this.state = "EXECUTING";
        this.executionOutcome = "IN_PROGRESS";
        this.executionStartedAt = now;
        return this.executionFence;
    }

    public void recoveryRequired(String outcome) {
        if ("TERMINAL".equals(state) || "CANCELLED".equals(state)) return;
        this.state = "RECOVERY_REQUIRED";
        this.executionOutcome = outcome;
    }

    public void terminal(String outcome, LocalDateTime now) {
        this.state = "TERMINAL";
        this.executionOutcome = outcome;
        this.terminalAt = now;
    }

    public void finishCancel() {
        if (!"CANCEL_PENDING".equals(state)) throw new IllegalStateException("Recovery cancellation is not pending");
        this.state = "CANCELLED";
        this.executionOutcome = "CANCELLED";
    }

    public void beginCancel(String operator, String reason, LocalDateTime now) {
        if ("CANCEL_PENDING".equals(state)) return;
        if (!("PREPARED".equals(state) || "ARMED".equals(state) || "TERMINAL".equals(state))) {
            throw new IllegalStateException("Only a pre-GO or terminal permit can be cancelled");
        }
        Map<String, Object> snapshot = new LinkedHashMap<>(preconditions == null ? Map.of() : preconditions);
        snapshot.put("cancellation", Map.of(
                "operator", operator, "reason", reason, "timestamp", now.toString()));
        this.preconditions = snapshot;
        this.state = "CANCEL_PENDING";
        this.executionOutcome = "CANCEL_PENDING";
    }
}
