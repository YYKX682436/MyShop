package com.myshop.entity;

import com.myshop.constant.OrderStatus;
import com.myshop.constant.OrderPaymentMethod;
import com.myshop.constant.OrderType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "orders")
public class Order extends BaseEntity {

    private UUID userId;

    @Column(precision = 10, scale = 2)
    private BigDecimal totalAmount;

    @Column(precision = 10, scale = 2)
    private BigDecimal actualAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status = OrderStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, length = 32)
    private OrderType orderType = OrderType.NORMAL;

    /** Pricing context captured when the order was created; it is not a live relation. */
    @Column(name = "user_level_id_snapshot")
    private UUID userLevelIdSnapshot;

    @Column(name = "user_level_name_snapshot", length = 100)
    private String userLevelNameSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 64)
    private OrderPaymentMethod paymentMethod;

    @Column(name = "payment_channel_code", length = 128)
    private String paymentChannelCode;

    private LocalDateTime expiresAt;

    private LocalDateTime paidAt;

    private LocalDateTime deliveredAt;

    @Column(unique = true)
    private String idempotencyKey;

    /** Optional client fingerprint used to reject a changed payload on replay. */
    @Column(name = "idempotency_fingerprint", length = 64)
    private String idempotencyFingerprint;

    @Column(name = "is_risk_flagged")
    private boolean riskFlagged = false;

    private String clientIp;

    private String sessionToken;

}
