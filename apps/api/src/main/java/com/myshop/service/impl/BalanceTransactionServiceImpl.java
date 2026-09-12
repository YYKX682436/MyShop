package com.myshop.service.impl;

import com.myshop.constant.BalanceTransactionType;
import com.myshop.constant.ErrorCode;
import com.myshop.entity.BalanceTransaction;
import com.myshop.exception.BusinessException;
import com.myshop.repository.BalanceTransactionRepository;
import com.myshop.service.BalanceTransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BalanceTransactionServiceImpl implements BalanceTransactionService {

    private final BalanceTransactionRepository balanceTransactionRepository;
    private static final BigDecimal MAX_BALANCE = new BigDecimal("9999999999.99");

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public BalanceTransaction recordAdminAdjustment(UUID userId,
                                                     BigDecimal amount,
                                                     BigDecimal delta,
                                                     BigDecimal balanceBefore,
                                                     BigDecimal balanceAfter,
                                                     String reason,
                                                     UUID operatorId) {
        if (userId == null || operatorId == null || amount == null || delta == null
                || balanceBefore == null || balanceAfter == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "余额流水参数不完整");
        }
        if (amount.signum() <= 0 || delta.signum() == 0
                || balanceBefore.signum() < 0 || balanceAfter.signum() < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "余额流水金额无效");
        }
        if (balanceBefore.compareTo(MAX_BALANCE) > 0 || balanceAfter.compareTo(MAX_BALANCE) > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "余额流水金额超出系统支持范围");
        }
        if (amount.scale() > 2 || delta.scale() > 2
                || balanceBefore.scale() > 2 || balanceAfter.scale() > 2) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "余额流水金额最多支持两位小数");
        }
        if (delta.abs().compareTo(amount) != 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "余额流水变动金额不一致");
        }
        BigDecimal expectedAfter = balanceBefore.add(delta).setScale(2, RoundingMode.UNNECESSARY);
        if (expectedAfter.compareTo(balanceAfter.setScale(2, RoundingMode.UNNECESSARY)) != 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "余额流水前后余额不一致");
        }
        String normalizedReason = reason == null ? null : reason.trim();
        if (normalizedReason != null && normalizedReason.length() > 500) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "余额流水原因不能超过 500 个字符");
        }

        BalanceTransaction transaction = new BalanceTransaction();
        transaction.setUserId(userId);
        transaction.setType(BalanceTransactionType.ADMIN_ADJUSTMENT);
        transaction.setAmount(amount.setScale(2, RoundingMode.UNNECESSARY));
        transaction.setDelta(delta.setScale(2, RoundingMode.UNNECESSARY));
        transaction.setBalanceBefore(balanceBefore.setScale(2, RoundingMode.UNNECESSARY));
        transaction.setBalanceAfter(balanceAfter.setScale(2, RoundingMode.UNNECESSARY));
        transaction.setReason(normalizedReason == null || normalizedReason.isBlank()
                ? null : normalizedReason);
        transaction.setOperatorId(operatorId);
        transaction.setOrderId(null);
        return balanceTransactionRepository.save(transaction);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public BalanceTransaction recordOrderPayment(UUID userId,
                                                 BigDecimal amount,
                                                 BigDecimal balanceBefore,
                                                 BigDecimal balanceAfter,
                                                 UUID orderId,
                                                 UUID operatorId) {
        if (userId == null || operatorId == null || orderId == null
                || amount == null || balanceBefore == null || balanceAfter == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "余额支付流水参数不完整");
        }
        validateLedgerValues(amount, amount.negate(), balanceBefore, balanceAfter);
        BalanceTransaction transaction = new BalanceTransaction();
        transaction.setUserId(userId);
        transaction.setType(BalanceTransactionType.ORDER_PAYMENT);
        transaction.setAmount(amount.setScale(2, RoundingMode.UNNECESSARY));
        transaction.setDelta(amount.negate().setScale(2, RoundingMode.UNNECESSARY));
        transaction.setBalanceBefore(balanceBefore.setScale(2, RoundingMode.UNNECESSARY));
        transaction.setBalanceAfter(balanceAfter.setScale(2, RoundingMode.UNNECESSARY));
        transaction.setReason("订单余额支付");
        transaction.setOperatorId(operatorId);
        transaction.setOrderId(orderId);
        return balanceTransactionRepository.save(transaction);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public BalanceTransaction recordRefund(UUID userId,
                                           BigDecimal amount,
                                           BigDecimal balanceBefore,
                                           BigDecimal balanceAfter,
                                           UUID orderId,
                                           String reason,
                                           UUID operatorId) {
        if (userId == null || operatorId == null || orderId == null
                || amount == null || balanceBefore == null || balanceAfter == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "退款余额流水参数不完整");
        }
        String normalizedReason = reason == null ? null : reason.trim();
        if (normalizedReason != null && normalizedReason.length() > 500) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "退款原因不能超过 500 个字符");
        }
        validateLedgerValues(amount, amount, balanceBefore, balanceAfter);

        BalanceTransaction transaction = new BalanceTransaction();
        transaction.setUserId(userId);
        transaction.setType(BalanceTransactionType.REFUND);
        transaction.setAmount(amount.setScale(2, RoundingMode.UNNECESSARY));
        transaction.setDelta(amount.setScale(2, RoundingMode.UNNECESSARY));
        transaction.setBalanceBefore(balanceBefore.setScale(2, RoundingMode.UNNECESSARY));
        transaction.setBalanceAfter(balanceAfter.setScale(2, RoundingMode.UNNECESSARY));
        transaction.setReason(normalizedReason == null || normalizedReason.isBlank()
                ? null : normalizedReason);
        transaction.setOperatorId(operatorId);
        transaction.setOrderId(orderId);
        return balanceTransactionRepository.save(transaction);
    }

    private void validateLedgerValues(BigDecimal amount, BigDecimal delta,
                                      BigDecimal balanceBefore, BigDecimal balanceAfter) {
        if (amount.signum() <= 0 || delta.signum() == 0
                || balanceBefore.signum() < 0 || balanceAfter.signum() < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "余额流水金额无效");
        }
        if (balanceBefore.compareTo(MAX_BALANCE) > 0 || balanceAfter.compareTo(MAX_BALANCE) > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "余额流水金额超出系统支持范围");
        }
        if (amount.scale() > 2 || delta.scale() > 2
                || balanceBefore.scale() > 2 || balanceAfter.scale() > 2) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "余额流水金额最多支持两位小数");
        }
        if (delta.abs().compareTo(amount) != 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "余额流水变动金额不一致");
        }
        BigDecimal expectedAfter = balanceBefore.add(delta).setScale(2, RoundingMode.UNNECESSARY);
        if (expectedAfter.compareTo(balanceAfter.setScale(2, RoundingMode.UNNECESSARY)) != 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "余额流水前后余额不一致");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BalanceTransaction> listByUser(UUID userId, Pageable pageable) {
        return balanceTransactionRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }
}
