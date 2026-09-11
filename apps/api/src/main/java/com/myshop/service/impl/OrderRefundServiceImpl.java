package com.myshop.service.impl;

import com.myshop.constant.ErrorCode;
import com.myshop.constant.FulfillmentStatus;
import com.myshop.constant.OrderStatus;
import com.myshop.constant.OrderType;
import com.myshop.constant.StockMode;
import com.myshop.constant.UserRole;
import com.myshop.context.RequestContext;
import com.myshop.entity.Order;
import com.myshop.entity.OrderItem;
import com.myshop.entity.PaymentAttempt;
import com.myshop.entity.User;
import com.myshop.exception.BusinessException;
import com.myshop.model.response.AdminOrderRefundResponse;
import com.myshop.payment.PaymentStatus;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.PaymentAttemptRepository;
import com.myshop.repository.UserRepository;
import com.myshop.service.BalanceTransactionService;
import com.myshop.service.ManualStockService;
import com.myshop.service.OperationLogService;
import com.myshop.service.OrderRefundService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Atomic administrator refund boundary. The order row is always locked before
 * the registered user's row, and all accounting/fulfillment mutations join the
 * same transaction.
 */
@Service
public class OrderRefundServiceImpl implements OrderRefundService {

    private static final String REFUND_DESTINATION = "BALANCE";
    private static final BigDecimal MAX_BALANCE = new BigDecimal("9999999999.99");

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final UserRepository userRepository;
    private final ManualStockService manualStockService;
    private final BalanceTransactionService balanceTransactionService;
    private final OperationLogService operationLogService;

    public OrderRefundServiceImpl(OrderRepository orderRepository,
                                  OrderItemRepository orderItemRepository,
                                  PaymentAttemptRepository paymentAttemptRepository,
                                  UserRepository userRepository,
                                  ManualStockService manualStockService,
                                  BalanceTransactionService balanceTransactionService,
                                  OperationLogService operationLogService) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.userRepository = userRepository;
        this.manualStockService = manualStockService;
        this.balanceTransactionService = balanceTransactionService;
        this.operationLogService = operationLogService;
    }

    @Override
    @Transactional
    public AdminOrderRefundResponse refund(UUID orderId, String reason, boolean confirmed) {
        String normalizedReason = validateRequest(reason, confirmed);
        RequestContext.UserInfo operator = RequestContext.get();
        UUID operatorId = operator == null ? null : operator.getUserId();
        if (operatorId == null
                || (!UserRole.ADMIN.name().equals(operator.getRole())
                && !UserRole.SUPER_ADMIN.name().equals(operator.getRole()))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅管理员可执行退款");
        }

        // This is the first business row lock. Every refund and fulfillment
        // path must observe the state after this lock before changing anything.
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));
        validateRefundableOrder(order);

        // Keep the business lock order stable: Order -> User. Do not move this
        // read above the order lock even if a caller already knows the user id.
        User user = userRepository.findByIdForUpdate(order.getUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_REFUND_NOT_ALLOWED,
                        "退款目标账户不存在"));
        if (user.getRole() != UserRole.USER) {
            throw new BusinessException(ErrorCode.ORDER_REFUND_NOT_ALLOWED,
                    "只有注册普通用户订单支持退款");
        }

        // Close any stale pending attempt while both business rows are held.
        // This keeps a late provider callback from turning a refunded order's
        // old attempt into an apparently active payment fact.
        List<PaymentAttempt> pendingAttempts = paymentAttemptRepository
                .findByOrderIdAndStatusForUpdate(order.getId(), PaymentStatus.PENDING);

        BigDecimal refundAmount = money(order.getActualAmount(), "订单退款金额异常");
        if (refundAmount.signum() <= 0) {
            throw new BusinessException(ErrorCode.ORDER_REFUND_NOT_ALLOWED, "订单退款金额必须大于 0");
        }
        BigDecimal balanceBefore = money(user.getBalance(), "账户余额异常");
        if (balanceBefore.signum() < 0) {
            throw new BusinessException(ErrorCode.ORDER_REFUND_NOT_ALLOWED, "账户余额不能为负数");
        }
        BigDecimal balanceAfter;
        try {
            balanceAfter = balanceBefore.add(refundAmount).setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new BusinessException(ErrorCode.ORDER_REFUND_BALANCE_LIMIT,
                    "退款后账户余额超出系统支持范围");
        }
        if (balanceAfter.compareTo(MAX_BALANCE) > 0) {
            throw new BusinessException(ErrorCode.ORDER_REFUND_BALANCE_LIMIT,
                    "退款后账户余额超出系统支持范围");
        }

        for (PaymentAttempt pendingAttempt : pendingAttempts) {
            pendingAttempt.setStatus(PaymentStatus.CLOSED);
            paymentAttemptRepository.save(pendingAttempt);
        }
        cancelUnfinishedFulfillment(order);

        user.setBalance(balanceAfter);
        userRepository.save(user);

        LocalDateTime refundedAt = LocalDateTime.now();
        order.setStatus(OrderStatus.REFUNDED);
        order.setRefundedAt(refundedAt);
        orderRepository.save(order);

        balanceTransactionService.recordRefund(
                user.getId(), refundAmount, balanceBefore, balanceAfter, order.getId(),
                normalizedReason, operatorId);
        operationLogService.logCurrentRequest(
                "order.refund",
                "ORDER",
                order.getId().toString(),
                "订单号=" + order.getOrderNo()
                        + "，退款金额=" + refundAmount.toPlainString()
                        + "，退款去向=" + REFUND_DESTINATION
                        + "，原因=" + normalizedReason);

        return new AdminOrderRefundResponse(
                order.getId(), order.getOrderNo(), order.getStatus(), refundAmount,
                refundedAt, REFUND_DESTINATION, balanceBefore, balanceAfter);
    }

    private String validateRequest(String reason, boolean confirmed) {
        if (!confirmed) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "必须明确确认退款");
        }
        String normalized = reason == null ? null : reason.trim();
        if (normalized == null || normalized.length() < 3 || normalized.length() > 500) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "退款原因必须为 3 至 500 个字符");
        }
        return normalized;
    }

    private void validateRefundableOrder(Order order) {
        if (order.getUserId() == null || order.getOrderType() != OrderType.NORMAL) {
            throw new BusinessException(ErrorCode.ORDER_REFUND_NOT_ALLOWED,
                    "仅支持注册普通用户订单退款，游客订单暂不支持退款");
        }
        if (order.getStatus() == OrderStatus.REFUNDED) {
            throw new BusinessException(ErrorCode.ORDER_ALREADY_REFUNDED, "订单已退款，不能重复退款");
        }
        if (order.getStatus() != OrderStatus.PAID && order.getStatus() != OrderStatus.DELIVERED) {
            throw new BusinessException(ErrorCode.ORDER_REFUND_NOT_ALLOWED,
                    "仅 PAID 或 DELIVERED 状态订单支持退款");
        }
    }

    private void cancelUnfinishedFulfillment(Order order) {
        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        for (OrderItem item : items) {
            // A delivered item is immutable from the refund path. In
            // particular, do not try to repair or release a stale reservation
            // here: it could turn a previously delivered stock record into a
            // reusable inventory unit.
            if (item.getFulfillmentStatus() == FulfillmentStatus.DELIVERED) {
                continue;
            }
            int reserved = item.getManualStockReserved();
            if (reserved > 0 && item.getStockMode() == StockMode.MANUAL) {
                manualStockService.release(item.getProductId(), item.getSpecId(), reserved);
                item.setManualStockReserved(0);
            }
            item.setFulfillmentStatus(FulfillmentStatus.CANCELLED);
            orderItemRepository.save(item);
        }
    }

    private BigDecimal money(BigDecimal value, String message) {
        if (value == null || value.signum() < 0 || value.compareTo(MAX_BALANCE) > 0) {
            throw new BusinessException(ErrorCode.ORDER_REFUND_NOT_ALLOWED, message);
        }
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new BusinessException(ErrorCode.ORDER_REFUND_NOT_ALLOWED,
                    message + "，金额最多支持两位小数");
        }
    }
}
