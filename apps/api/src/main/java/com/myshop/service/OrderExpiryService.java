package com.myshop.service;

import com.myshop.constant.FulfillmentStatus;
import com.myshop.constant.OrderStatus;
import com.myshop.entity.Order;
import com.myshop.entity.OrderItem;
import com.myshop.entity.PaymentAttempt;
import com.myshop.payment.PaymentStatus;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.PaymentAttemptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/** All expiry entry points use the same order -> attempt -> inventory lock order. */
@Service
@RequiredArgsConstructor
public class OrderExpiryService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final ManualStockService manualStockService;

    /** Load the order only after acquiring its lock in a fresh persistence context. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expireById(UUID orderId) {
        orderRepository.findByIdForUpdate(orderId).ifPresent(this::expireLockedOrder);
    }

    /** The caller must already hold the order row lock in the current transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void expireLockedOrder(Order order) {
        if (order == null || order.getStatus() != OrderStatus.PENDING
                || order.getExpiresAt() == null || order.getExpiresAt().isAfter(LocalDateTime.now())) {
            return;
        }
        for (PaymentAttempt attempt : paymentAttemptRepository
                .findByOrderIdAndStatusForUpdate(order.getId(), PaymentStatus.PENDING)) {
            attempt.setStatus(PaymentStatus.CLOSED);
            paymentAttemptRepository.save(attempt);
        }
        for (OrderItem item : orderItemRepository.findByOrderId(order.getId())) {
            if (item.getManualStockReserved() > 0) {
                manualStockService.release(item.getProductId(), item.getSpecId(), item.getManualStockReserved());
                item.setManualStockReserved(0);
            }
            if (item.getFulfillmentStatus() != FulfillmentStatus.DELIVERED) {
                item.setFulfillmentStatus(FulfillmentStatus.CANCELLED);
            }
            orderItemRepository.save(item);
        }
        order.setStatus(OrderStatus.EXPIRED);
        orderRepository.save(order);
    }
}
