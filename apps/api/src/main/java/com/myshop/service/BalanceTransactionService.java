package com.myshop.service;

import com.myshop.entity.BalanceTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.UUID;

/** Owns the immutable balance-ledger persistence boundary. */
public interface BalanceTransactionService {

    /**
     * Persists an administrator adjustment inside the caller's transaction.
     * The caller must already have locked and updated the target user.
     */
    BalanceTransaction recordAdminAdjustment(UUID userId,
                                             BigDecimal amount,
                                             BigDecimal delta,
                                             BigDecimal balanceBefore,
                                             BigDecimal balanceAfter,
                                             String reason,
                                             UUID operatorId);

    BalanceTransaction recordOrderPayment(UUID userId,
                                          BigDecimal amount,
                                          BigDecimal balanceBefore,
                                          BigDecimal balanceAfter,
                                          UUID orderId,
                                          UUID operatorId);

    Page<BalanceTransaction> listByUser(UUID userId, Pageable pageable);
}
