package com.myshop.entity;

import com.myshop.constant.BalanceTransactionType;
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
import java.util.UUID;

/** Immutable accounting record for every change to a user's balance. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "balance_transactions")
public class BalanceTransaction extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private BalanceTransactionType type;

    /** Absolute amount of this transaction. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /** Signed amount: positive for credit, negative for debit. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal delta;

    @Column(name = "balance_before", nullable = false, precision = 12, scale = 2)
    private BigDecimal balanceBefore;

    @Column(name = "balance_after", nullable = false, precision = 12, scale = 2)
    private BigDecimal balanceAfter;

    @Column(length = 500)
    private String reason;

    @Column(name = "operator_id", nullable = false)
    private UUID operatorId;

    @Column(name = "order_id")
    private UUID orderId;
}
