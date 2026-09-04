package com.myshop.entity;

import com.myshop.constant.OrderStatus;
import com.myshop.constant.OrderType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "orders")
public class Order extends BaseEntity {

    /** Public business identifier. The UUID inherited from BaseEntity remains the internal primary key. */
    @Column(name = "order_no", nullable = false, unique = true, updatable = false)
    private Long orderNo;

    private UUID userId;

    private String email;

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

    private String paymentMethod;

    private LocalDateTime expiresAt;

    private LocalDateTime paidAt;

    private LocalDateTime deliveredAt;

    @Column(unique = true)
    private String idempotencyKey;

    /** SHA-256 of the guest lookup token used for normal constant-time authorization. */
    @Column(name = "lookup_token_hash", length = 64)
    private String lookupTokenHash;

    /**
     * Recoverable guest credential for this self-hosted, low-volume deployment.
     * It is deliberately never serialized or logged; the hash above remains the
     * authorization primitive. See the deployment documentation for handling
     * backups containing this sensitive field.
     */
    @Column(name = "guest_lookup_token", length = 128)
    private String guestLookupToken;

    /** Optional client fingerprint used to reject a changed payload on replay. */
    @Column(name = "idempotency_fingerprint", length = 64)
    private String idempotencyFingerprint;

    @Column(name = "is_risk_flagged")
    private boolean riskFlagged = false;

    private String clientIp;

    private String sessionToken;

    @Column(columnDefinition = "TEXT")
    private String paymentUrl;

    @Column(columnDefinition = "TEXT")
    private String qrcodeUrl;

    private String epayTradeNo;

    /** The visible business number is assigned once and must never change. */
    public void setOrderNo(Long value) {
        if (orderNo != null && !Objects.equals(orderNo, value)) {
            throw new IllegalStateException("order_no is immutable");
        }
        orderNo = value;
    }

}
