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
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "sales_channel_operations")
@Getter
@NoArgsConstructor
public class SalesChannelOperation extends BaseEntity {

    @Column(name = "sales_channel_listing_id", nullable = false)
    private UUID salesChannelListingId;

    /** Frozen provider generation target. Null is permitted only for PUBLISH. */
    @Column(name = "sales_channel_listing_generation_id")
    private UUID salesChannelListingGenerationId;

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
        this(listingId, null, operationType, desiredRevision, idempotencyKey, payload);
    }

    public SalesChannelOperation(UUID listingId, UUID generationId,
                                 SalesChannelOperationType operationType,
                                 long desiredRevision, String idempotencyKey,
                                 Map<String, Object> payload) {
        this.salesChannelListingId = listingId;
        this.salesChannelListingGenerationId = generationId;
        this.operationType = operationType;
        this.desiredRevision = desiredRevision;
        this.idempotencyKey = idempotencyKey;
        setPayload(payload);
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = deepCopyMap(payload);
    }

    public Map<String, Object> getPayload() {
        return deepCopyMap(payload);
    }

    public void setResult(Map<String, Object> result) {
        this.result = result == null ? null : deepCopyMap(result);
    }

    public Map<String, Object> getResult() {
        return result == null ? null : deepCopyMap(result);
    }

    private static Map<String, Object> deepCopyMap(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((key, value) -> copy.put(key, deepCopyValue(value)));
        }
        return copy;
    }

    private static Object deepCopyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nestedValue) -> copy.put(key, deepCopyValue(nestedValue)));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(nestedValue -> copy.add(deepCopyValue(nestedValue)));
            return copy;
        }
        if (value instanceof Set<?> set) {
            Set<Object> copy = new LinkedHashSet<>();
            set.forEach(nestedValue -> copy.add(deepCopyValue(nestedValue)));
            return copy;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> copy = new ArrayList<>(collection.size());
            collection.forEach(nestedValue -> copy.add(deepCopyValue(nestedValue)));
            return copy;
        }
        return value;
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

    public boolean reschedule(String errorCode, String errorMessage, LocalDateTime nextAttemptAt) {
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt is required");
        if (status != SalesChannelOperationStatus.RUNNING) return false;
        status = SalesChannelOperationStatus.PENDING;
        this.nextAttemptAt = nextAttemptAt;
        startedAt = null;
        finishedAt = null;
        lastErrorCode = errorCode;
        lastErrorMessage = errorMessage;
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

    public boolean resolveUnknownAsSucceeded(Map<String, Object> result, LocalDateTime now) {
        if (status != SalesChannelOperationStatus.UNKNOWN) return false;
        setResult(result);
        status = SalesChannelOperationStatus.SUCCEEDED;
        finishedAt = now;
        lastErrorCode = null;
        lastErrorMessage = null;
        return true;
    }

    public boolean resolveUnknownAsFailed(Map<String, Object> result, String errorCode,
                                          String errorMessage, LocalDateTime now) {
        if (status != SalesChannelOperationStatus.UNKNOWN) return false;
        setResult(result);
        status = SalesChannelOperationStatus.FAILED;
        lastErrorCode = errorCode;
        lastErrorMessage = errorMessage;
        finishedAt = now;
        return true;
    }

    /** The only permitted UNKNOWN -> PENDING transition; used after reconciliation proves no side effect occurred. */
    public boolean resolveUnknownAsRetryable(String errorCode, String errorMessage,
                                             LocalDateTime nextAttemptAt) {
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt is required");
        if (status != SalesChannelOperationStatus.UNKNOWN) return false;
        status = SalesChannelOperationStatus.PENDING;
        this.nextAttemptAt = nextAttemptAt;
        startedAt = null;
        finishedAt = null;
        lastErrorCode = errorCode;
        lastErrorMessage = errorMessage;
        return true;
    }

    public boolean supersede(LocalDateTime now) {
        if (status != SalesChannelOperationStatus.PENDING) return false;
        status = SalesChannelOperationStatus.SUPERSEDED;
        finishedAt = now;
        return true;
    }
}
