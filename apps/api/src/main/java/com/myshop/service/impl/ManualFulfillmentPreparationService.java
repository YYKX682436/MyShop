package com.myshop.service.impl;

import com.myshop.constant.FulfillmentStatus;
import com.myshop.constant.OrderStatus;
import com.myshop.entity.Order;
import com.myshop.entity.OrderItem;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Commits the manual lane's waiting state independently of automatic stock
 * allocation. An automatic card shortage must not hide a paid manual item
 * behind a rolled-back PENDING fulfillment snapshot.
 */
@Service
@RequiredArgsConstructor
public class ManualFulfillmentPreparationService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markWaitingManual(UUID orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.PAID) {
            return;
        }

        List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
        for (OrderItem item : items) {
            if (isManual(item)
                    && item.getFulfillmentStatus() != FulfillmentStatus.DELIVERED
                    && item.getFulfillmentStatus() != FulfillmentStatus.CANCELLED
                    && item.getFulfillmentStatus() != FulfillmentStatus.WAITING_MANUAL) {
                item.setFulfillmentStatus(FulfillmentStatus.WAITING_MANUAL);
                orderItemRepository.save(item);
            }
        }
    }

    private boolean isManual(OrderItem item) {
        return "MANUAL".equalsIgnoreCase(item.getDeliveryType());
    }
}
