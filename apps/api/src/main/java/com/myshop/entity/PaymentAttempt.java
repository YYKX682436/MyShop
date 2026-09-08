package com.myshop.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.myshop.payment.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Immutable-at-creation snapshot of one provider transaction. It deliberately
 * has no relation to the live PaymentChannel configuration.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "payment_attempts")
public class PaymentAttempt extends BaseEntity {

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "channel_id", nullable = false)
    private UUID channelId;

    @Column(name = "channel_code_snapshot", nullable = false, length = 128)
    private String channelCodeSnapshot;

    @Column(name = "provider_type", nullable = false, length = 128)
    private String providerType;

    @Column(name = "payment_type", nullable = false, length = 32)
    private String paymentType;

    /** Never serialize this field; it is only passed to the selected provider. */
    @JsonIgnore
    @Column(name = "provider_config_snapshot", nullable = false, columnDefinition = "TEXT")
    private String providerConfigSnapshot;

    @Column(name = "merchant_trade_no", nullable = false, unique = true, length = 255)
    private String merchantTradeNo;

    @Column(name = "provider_trade_no", length = 255)
    private String providerTradeNo;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(name = "next_action", nullable = false, columnDefinition = "TEXT")
    private String nextAction;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;
}
