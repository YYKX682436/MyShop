package com.myshop.entity;

import com.myshop.constant.SalesChannelOperationStatus;
import com.myshop.constant.SalesChannelOperationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "sales_channel_operations")
@Getter
@NoArgsConstructor
public class SalesChannelOperation extends BaseEntity {

    @Column(name = "sales_channel_listing_id", nullable = false)
    private UUID salesChannelListingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, length = 16)
    private SalesChannelOperationType operationType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SalesChannelOperationStatus status = SalesChannelOperationStatus.PENDING;

    @Column(name = "desired_revision", nullable = false)
    private long desiredRevision;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 255)
    private String idempotencyKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> result;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at")
    @Setter
    private LocalDateTime nextAttemptAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "last_error_code", length = 128)
    private String lastErrorCode;

    @Column(name = "last_error_message", columnDefinition = "TEXT")
    private String lastErrorMessage;

    public SalesChannelOperation(UUID listingId, SalesChannelOperationType operationType,
                                 long desiredRevision, String idempotencyKey, Map<String, Object> payload) {
        this.salesChannelListingId = listingId;
        this.operationType = operationType;
        this.desiredRevision = desiredRevision;
        this.idempotencyKey = idempotencyKey;
        setPayload(payload);
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
    }

    public void setResult(Map<String, Object> result) {
        this.result = result == null ? null : new LinkedHashMap<>(result);
    }

    public boolean start(LocalDateTime now) {
        if (status != SalesChannelOperationStatus.PENDING) return false;
        status = SalesChannelOperationStatus.RUNNING;
        attemptCount++;
        nextAttemptAt = null;
        startedAt = now;
        finishedAt = null;
        return true;
    }

    public boolean succeed(Map<String, Object> result, LocalDateTime now) {
        if (status != SalesChannelOperationStatus.RUNNING) return false;
        setResult(result);
        status = SalesChannelOperationStatus.SUCCEEDED;
        finishedAt = now;
        lastErrorCode = null;
        lastErrorMessage = null;
        return true;
    }

    public boolean fail(String errorCode, String errorMessage, LocalDateTime now) {
        if (status != SalesChannelOperationStatus.RUNNING) return false;
        status = SalesChannelOperationStatus.FAILED;
        lastErrorCode = errorCode;
        lastErrorMessage = errorMessage;
        finishedAt = now;
        return true;
    }

    public boolean markUnknown(String errorCode, String errorMessage, LocalDateTime now) {
        if (status != SalesChannelOperationStatus.RUNNING) return false;
        status = SalesChannelOperationStatus.UNKNOWN;
        lastErrorCode = errorCode;
        lastErrorMessage = errorMessage;
        finishedAt = now;
        return true;
    }

    public boolean supersede(LocalDateTime now) {
        if (status != SalesChannelOperationStatus.PENDING) return false;
        status = SalesChannelOperationStatus.SUPERSEDED;
        finishedAt = now;
        return true;
    }
}
