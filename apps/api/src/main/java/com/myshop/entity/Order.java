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
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "orders")
public class Order extends BaseEntity {

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
    @Column(nullable = false)
    private OrderType orderType;

    private String paymentMethod;

    private int pointsDeducted = 0;

    @Column(precision = 10, scale = 2)
    private BigDecimal pointsDiscount = BigDecimal.ZERO;

    private LocalDateTime expiresAt;

    private LocalDateTime paidAt;

    private LocalDateTime deliveredAt;

    @Column(unique = true)
    private String idempotencyKey;

    /** SHA-256 of the one-time guest lookup token. The plaintext token is never persisted. */
    @Column(name = "lookup_token_hash", length = 64)
    private String lookupTokenHash;

    @Column(name = "is_risk_flagged")
    private boolean riskFlagged = false;

    private String clientIp;

    private String sessionToken;

    @Column(columnDefinition = "TEXT")
    private String paymentUrl;

    @Column(columnDefinition = "TEXT")
    private String qrcodeUrl;

    private String epayTradeNo;

}
