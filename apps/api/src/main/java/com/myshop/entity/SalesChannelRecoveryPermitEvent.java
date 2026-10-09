package com.myshop.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Append-only evidence for each recovery permit generation and transition. */
@Entity
@Table(name = "sales_channel_recovery_permit_events")
public class SalesChannelRecoveryPermitEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_id")
    private Long eventId;

    @Column(name = "permit_id", nullable = false)
    private UUID permitId;

    @Column(nullable = false)
    private long generation;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(name = "previous_generation")
    private Long previousGeneration;

    @Column(name = "previous_state", length = 32)
    private String previousState;

    @Column(name = "new_state", nullable = false, length = 32)
    private String newState;

    @Column(nullable = false, length = 128)
    private String operator;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(name = "event_at")
    private LocalDateTime eventAt;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    @Column(name = "execution_fence", nullable = false)
    private long executionFence;

    @Column(name = "identity_fingerprint", nullable = false, length = 64)
    private String identityFingerprint;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_json", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> evidence = new LinkedHashMap<>();

    protected SalesChannelRecoveryPermitEvent() { }

    public SalesChannelRecoveryPermitEvent(UUID permitId, long generation, String eventType,
                                           Long previousGeneration, String previousState,
                                           String newState, String operator, String reason,
                                           LocalDateTime eventAt, long executionFence,
                                           String identityFingerprint, Map<String, Object> evidence) {
        this.permitId = permitId;
        this.generation = generation;
        this.eventType = eventType;
        this.previousGeneration = previousGeneration;
        this.previousState = previousState;
        this.newState = newState;
        this.operator = operator;
        this.reason = reason;
        this.eventAt = eventAt;
        this.recordedAt = LocalDateTime.now();
        this.executionFence = executionFence;
        this.identityFingerprint = identityFingerprint;
        this.evidence = new LinkedHashMap<>(evidence == null ? Map.of() : evidence);
    }
}
