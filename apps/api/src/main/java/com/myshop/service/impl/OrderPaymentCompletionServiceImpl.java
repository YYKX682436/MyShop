package com.myshop.service.impl;

import com.myshop.constant.OrderStatus;
import com.myshop.entity.Order;
import com.myshop.repository.OrderRepository;
import com.myshop.service.AutoFulfillmentService;
import com.myshop.service.OrderPaymentCompletionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.UUID;

/** Shared, idempotent payment-to-PAID transition for every real payment path. */
@Service
public class OrderPaymentCompletionServiceImpl implements OrderPaymentCompletionService {

    private final OrderRepository orderRepository;
    private final AutoFulfillmentService autoFulfillmentService;

    public OrderPaymentCompletionServiceImpl(OrderRepository orderRepository,
                                             AutoFulfillmentService autoFulfillmentService) {
        this.orderRepository = orderRepository;
        this.autoFulfillmentService = autoFulfillmentService;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean completeFirstPayment(Order order) {
        if (order == null || order.getStatus() != OrderStatus.PENDING) {
            return false;
        }
        order.setStatus(OrderStatus.PAID);
        order.setPaidAt(LocalDateTime.now());
        orderRepository.save(order);
        scheduleFulfillmentAfterCommit(order.getId());
        return true;
    }

    private void scheduleFulfillmentAfterCommit(UUID orderId) {
        Runnable trigger = () -> autoFulfillmentService.trigger(orderId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    trigger.run();
                }
            });
        } else {
            trigger.run();
        }
    }
}
