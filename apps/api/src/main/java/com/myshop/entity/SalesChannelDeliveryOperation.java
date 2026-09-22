package com.myshop.entity;

import com.myshop.constant.SalesChannelDeliveryOperationStatus;
import com.myshop.constant.SalesChannelDeliveryOperationType;
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
@Table(name = "sales_channel_delivery_operations")
@Getter
@Setter
@NoArgsConstructor
public class SalesChannelDeliveryOperation extends BaseEntity {
    @Column(name = "sales_channel_order_id", nullable = false)
    private UUID salesChannelOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, length = 32)
    private SalesChannelDeliveryOperationType operationType;

    @Column(name = "unit_index")
    private Integer unitIndex;

    @Column(name = "card_key_id")
    private UUID cardKeyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SalesChannelDeliveryOperationStatus status = SalesChannelDeliveryOperationStatus.PENDING;

    @Column(name = "request_id", nullable = false, unique = true, length = 255)
    private String requestId;

    @Column(name = "payload_fingerprint", nullable = false, length = 64)
    private String payloadFingerprint;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_json", columnDefinition = "jsonb")
    private Map<String, Object> resultJson = new LinkedHashMap<>();

    @Column(name = "last_error_code", length = 128)
    private String lastErrorCode;

    @Column(name = "last_error_message", columnDefinition = "TEXT")
    private String lastErrorMessage;
}
