package com.myshop.entity;

import com.myshop.constant.AccountPaymentStatus;
import com.myshop.constant.AccountPaymentType;
import com.myshop.constant.OrderPaymentMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "account_payment_transactions")
public class AccountPaymentTransaction extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "subsite_id")
    private UUID subsiteId;

    @Column(name = "subsite_owner_id_snapshot")
    private UUID subsiteOwnerIdSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private AccountPaymentType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 16)
    private OrderPaymentMethod paymentMethod;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private AccountPaymentStatus status = AccountPaymentStatus.PENDING;

    @Column(name = "current_level_id")
    private UUID currentLevelId;

    @Column(name = "target_level_id")
    private UUID targetLevelId;

    @Column(name = "current_level_rank_snapshot")
    private Integer currentLevelRankSnapshot;

    @Column(name = "target_level_rank_snapshot")
    private Integer targetLevelRankSnapshot;

    @Column(name = "current_level_price_snapshot", precision = 12, scale = 2)
    private BigDecimal currentLevelPriceSnapshot;

    @Column(name = "target_level_price_snapshot", precision = 12, scale = 2)
    private BigDecimal targetLevelPriceSnapshot;

    @Column(name = "channel_code_snapshot", length = 128)
    private String channelCodeSnapshot;

    @Column(name = "recovery_reason", length = 500)
    private String recoveryReason;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}
