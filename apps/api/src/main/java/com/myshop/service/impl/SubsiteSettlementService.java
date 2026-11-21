package com.myshop.service.impl;

import com.myshop.constant.BalanceTransactionType;
import com.myshop.constant.ErrorCode;
import com.myshop.constant.OrderStatus;
import com.myshop.entity.BalanceTransaction;
import com.myshop.entity.Order;
import com.myshop.entity.User;
import com.myshop.exception.BusinessException;
import com.myshop.repository.BalanceTransactionRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Earned profit is recorded on the locked order in the same transaction as delivery. */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubsiteSettlementService {
    private static final BigDecimal MAX_BALANCE = new BigDecimal("9999999999.99");
    private static final int RETRY_BATCH_SIZE = 100;
    private final UserRepository users;
    private final BalanceTransactionRepository ledger;
    private final OrderRepository orders;
    private TransactionTemplate transactions;

    @Autowired
    public void setTransactionManager(PlatformTransactionManager manager) {
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Called while holding the order lock, inside the delivery transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void settle(Order order) {
        if (order.getSubsiteId() == null || order.getSubsiteProfitState() != null) return;
        requiredProfit(order);
        order.setSubsiteProfitState("PENDING");
        // Delivery and the durable pending record commit together. A completely
        // separate transaction attempts credit afterwards; failures cannot undo delivery.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            UUID orderId = order.getId();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { attemptCredit(orderId); }
            });
        }
    }

    /** Credit and refund always acquire Order -> User; each order can be credited once. */
    private void attemptCredit(UUID orderId) {
        try {
            if (transactions == null) throw new IllegalStateException("结算事务不可用");
            transactions.executeWithoutResult(status -> orders.findByIdForUpdate(orderId).ifPresent(this::creditLocked));
        } catch (RuntimeException exception) {
            // The committed PENDING order is the durable retry record. Avoid logging customer data.
            log.warn("Subsite profit credit deferred for order {}", orderId);
        }
    }

    /** Package-visible for focused tests; production callers must hold the order lock. */
    void creditLocked(Order order) {
        if (!"PENDING".equals(order.getSubsiteProfitState()) || order.getDeliveredAt() == null
                || (order.getStatus() != OrderStatus.DELIVERED && order.getStatus() != OrderStatus.AFTER_SALE)) return;
        BigDecimal profit = requiredProfit(order);
        if (profit.signum() > 0) {
            User owner = users.findByIdForUpdate(order.getSubsiteOwnerIdSnapshot())
                    .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "分站站长账户不存在"));
            BigDecimal before = owner.getBalance();
            if (before == null || before.signum() < 0)
                throw new BusinessException(ErrorCode.BAD_REQUEST, "分站站长余额异常");
            BigDecimal after = before.add(profit);
            if (after.compareTo(MAX_BALANCE) > 0) return;
            owner.setBalance(after);
            users.save(owner);
            writeLedger(order, owner, profit, before, after, BalanceTransactionType.SUBSITE_PROFIT);
        }
        order.setSubsiteProfitState("SETTLED");
        order.setSubsiteProfitSettledAt(LocalDateTime.now());
    }

    @Scheduled(fixedDelayString = "${subsites.settlement-retry-interval-ms:60000}",
            initialDelayString = "${subsites.settlement-retry-initial-delay-ms:30000}")
    public void retryPendingProfit() {
        UUID afterId = null;
        while (true) {
            List<UUID> ids = orders.findEligiblePendingProfitIds(afterId, PageRequest.of(0, RETRY_BATCH_SIZE));
            if (ids.isEmpty()) return;
            ids.forEach(this::attemptCredit);
            afterId = ids.getLast();
            if (ids.size() < RETRY_BATCH_SIZE) return;
        }
    }

    /** Owner is already locked in deterministic order with the customer when profit was credited. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reverse(Order order, User owner) {
        if (order.getSubsiteId() == null) return;
        if ("PENDING".equals(order.getSubsiteProfitState())) {
            order.setSubsiteProfitState("CANCELED");
            order.setSubsiteProfitCanceledAt(LocalDateTime.now());
            return;
        }
        if (!"SETTLED".equals(order.getSubsiteProfitState())) return;
        BigDecimal profit = requiredProfit(order);
        if (profit.signum() > 0) {
            if (owner == null || !Objects.equals(owner.getId(), order.getSubsiteOwnerIdSnapshot()))
                throw new BusinessException(ErrorCode.ORDER_REFUND_NOT_ALLOWED, "分站站长账户不存在");
            BigDecimal before = owner.getBalance();
            if (before == null || before.compareTo(profit) < 0) {
                BigDecimal shortfall = profit.subtract(before == null ? BigDecimal.ZERO : before);
                throw new BusinessException(ErrorCode.ORDER_REFUND_NOT_ALLOWED,
                        "分站站长余额不足，无法撤回收益，缺口 " + shortfall.toPlainString() + " 元");
            }
            BigDecimal after = before.subtract(profit);
            owner.setBalance(after);
            users.save(owner);
            writeLedger(order, owner, profit.negate(), before, after, BalanceTransactionType.SUBSITE_PROFIT_REVERSAL);
        }
        order.setSubsiteProfitState("REVERSED");
        order.setSubsiteProfitReversedAt(LocalDateTime.now());
    }

    private BigDecimal requiredProfit(Order order) {
        BigDecimal profit = order.getSubsiteProfit();
        if (profit == null || profit.signum() < 0 || order.getSubsiteOwnerIdSnapshot() == null
                || profit.signum() > 0 && Objects.equals(order.getUserId(), order.getSubsiteOwnerIdSnapshot()))
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分站订单收益快照异常");
        return profit;
    }

    private void writeLedger(Order order, User owner, BigDecimal delta, BigDecimal before,
                             BigDecimal after, BalanceTransactionType type) {
        BalanceTransaction entry = new BalanceTransaction();
        entry.setUserId(owner.getId());
        entry.setOperatorId(owner.getId());
        entry.setOrderId(order.getId());
        entry.setType(type);
        entry.setAmount(delta.abs());
        entry.setDelta(delta);
        entry.setBalanceBefore(before);
        entry.setBalanceAfter(after);
        entry.setReason(type == BalanceTransactionType.SUBSITE_PROFIT ? "分站订单收益" : "分站退款收益撤回");
        ledger.save(entry);
    }
}
