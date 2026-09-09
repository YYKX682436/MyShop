package com.myshop.service.impl;

import com.myshop.constant.ErrorCode;
import com.myshop.constant.OrderPaymentMethod;
import com.myshop.constant.OrderStatus;
import com.myshop.constant.OrderType;
import com.myshop.constant.UserRole;
import com.myshop.entity.Order;
import com.myshop.entity.User;
import com.myshop.exception.BusinessException;
import com.myshop.repository.UserRepository;
import com.myshop.service.BalanceOrderPaymentService;
import com.myshop.service.BalanceTransactionService;
import com.myshop.service.OrderPaymentCompletionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Atomic balance payment boundary; it never resolves a payment channel/provider. */
@Service
public class BalanceOrderPaymentServiceImpl implements BalanceOrderPaymentService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final UserRepository userRepository;
    private final BalanceTransactionService balanceTransactionService;
    private final OrderPaymentCompletionService orderPaymentCompletionService;

    public BalanceOrderPaymentServiceImpl(UserRepository userRepository,
                                          BalanceTransactionService balanceTransactionService,
                                          OrderPaymentCompletionService orderPaymentCompletionService) {
        this.userRepository = userRepository;
        this.balanceTransactionService = balanceTransactionService;
        this.orderPaymentCompletionService = orderPaymentCompletionService;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void pay(Order order, UUID userId) {
        if (order == null || order.getOrderType() != OrderType.NORMAL
                || order.getPaymentMethod() != OrderPaymentMethod.BALANCE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "只有余额支付订单可以使用余额支付");
        }
        if (userId == null) {
            throw new BusinessException(ErrorCode.BALANCE_PAYMENT_REQUIRES_LOGIN,
                    "余额支付仅支持已登录普通用户");
        }
        if (order.getUserId() == null || !userId.equals(order.getUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "余额支付用户与订单不匹配");
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "订单状态不允许余额支付");
        }

        User user = userRepository.findByIdForUpdate(userId)
                .filter(candidate -> candidate.getIsDeleted() == 0
                        && candidate.getRole() == UserRole.USER)
                .orElseThrow(() -> new BusinessException(ErrorCode.BALANCE_PAYMENT_REQUIRES_LOGIN,
                        "余额支付仅支持已登录普通用户"));
        BigDecimal amount = money(order.getActualAmount());
        if (amount.signum() <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "订单金额异常");
        }
        BigDecimal before = money(user.getBalance());
        if (before.compareTo(amount) < 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_BALANCE, "余额不足，无法完成本次支付",
                    Map.of("balance", before, "required", amount));
        }
        BigDecimal after = before.subtract(amount).setScale(2, RoundingMode.UNNECESSARY);
        user.setBalance(after);
        userRepository.save(user);

        balanceTransactionService.recordOrderPayment(
                userId, amount, before, after, order.getId(), userId);
        order.setExpiresAt(null);
        orderPaymentCompletionService.completeFirstPayment(order);
    }

    @Override
    public Map<String, Object> buildPaymentResult(Order order) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order_id", order.getId());
        result.put("order_no", order.getOrderNo());
        result.put("status", order.getStatus().name());
        result.put("paid", order.getStatus() == OrderStatus.PAID
                || order.getStatus() == OrderStatus.DELIVERED);
        result.put("payment_method", order.getPaymentMethod() == null ? null : order.getPaymentMethod().name());
        result.put("payment_channel_code", order.getPaymentChannelCode());
        result.put("next_action", null);
        result.put("expires_at", order.getExpiresAt());
        return result;
    }

    private BigDecimal money(BigDecimal value) {
        if (value == null) return ZERO;
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "余额支付金额最多支持两位小数");
        }
    }
}
