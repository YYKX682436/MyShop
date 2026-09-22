package com.myshop.service;

import com.myshop.constant.CardKeyStatus;
import com.myshop.constant.FulfillmentStatus;
import com.myshop.constant.OrderStatus;
import com.myshop.constant.SalesChannelOrderDeliveryStatus;
import com.myshop.entity.CardKey;
import com.myshop.entity.Order;
import com.myshop.entity.OrderItem;
import com.myshop.entity.SalesChannelOrder;
import com.myshop.repository.CardKeyRepository;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.SalesChannelOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Applies an external refund/cancel fact under the same order-first lock
 * discipline as card fulfillment. Card keys are never reclaimed here.
 */
@Service
@RequiredArgsConstructor
public class SalesChannelOrderTerminalStatusService {

    private static final Set<String> TERMINAL_EXTERNAL_STATUSES = Set.of(
            "cancelled", "refunding", "refunded");

    private final OrderRepository orderRepository;
    private final CardKeyRepository cardKeyRepository;
    private final OrderItemRepository orderItemRepository;
    private final SalesChannelOrderRepository channelOrderRepository;

    @Transactional
    public void handle(UUID channelOrderId, UUID orderId, String externalStatus) {
        if (channelOrderId == null || orderId == null
                || !TERMINAL_EXTERNAL_STATUSES.contains(externalStatus)) {
            return;
        }

        // This order-first sequence is intentional. FulfillmentTransactionService
        // takes the same Order lock before touching any CardKey pool.
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null) return;
        List<CardKey> boundKeys = cardKeyRepository.findByOrderIdForUpdate(orderId);
        boolean sold = boundKeys.stream().anyMatch(key -> key.getStatus() == CardKeyStatus.SOLD);

        // A delivered order is already a committed fulfillment fact even if a
        // damaged historical row happens to have no visible SOLD key.
        if (sold || order.getStatus() == OrderStatus.DELIVERED) {
            markReview(channelOrderId,
                    "EXTERNAL_REFUND_AFTER_FULFILLMENT",
                    "Provider order was refunded/cancelled after fulfillment; no automatic reclaim");
            return;
        }

        if (order.getStatus() != OrderStatus.PAID) return;

        order.setStatus(OrderStatus.REFUNDED);
        order.setRefundedAt(LocalDateTime.now());
        orderRepository.save(order);
        for (OrderItem item : orderItemRepository.findByOrderId(orderId)) {
            if (item.getFulfillmentStatus() != FulfillmentStatus.DELIVERED) {
                item.setFulfillmentStatus(FulfillmentStatus.CANCELLED);
                orderItemRepository.save(item);
            }
        }

        SalesChannelOrder channelOrder = channelOrderRepository.findByIdForUpdate(channelOrderId).orElse(null);
        if (channelOrder != null) {
            channelOrder.setDeliveryStatus(SalesChannelOrderDeliveryStatus.FAILED);
            channelOrder.setLastErrorCode("EXTERNAL_ORDER_TERMINAL");
            channelOrder.setLastErrorMessage("Provider order was refunded/cancelled before MyShop fulfillment");
            channelOrderRepository.save(channelOrder);
        }
    }

    private void markReview(UUID channelOrderId, String code, String message) {
        SalesChannelOrder channelOrder = channelOrderRepository.findByIdForUpdate(channelOrderId).orElse(null);
        if (channelOrder == null) return;
        channelOrder.setDeliveryStatus(SalesChannelOrderDeliveryStatus.REVIEW_REQUIRED);
        channelOrder.setLastErrorCode(code);
        channelOrder.setLastErrorMessage(message);
        channelOrderRepository.save(channelOrder);
    }
}
