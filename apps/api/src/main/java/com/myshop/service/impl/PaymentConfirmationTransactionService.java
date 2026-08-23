package com.myshop.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.constant.OrderStatus;
import com.myshop.entity.Order;
import com.myshop.entity.WebhookEvent;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.WebhookEventRepository;
import com.myshop.service.AutoFulfillmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Commits the payment fact and webhook idempotency record before fulfillment. */
@Service
@RequiredArgsConstructor
public class PaymentConfirmationTransactionService {

    private final WebhookEventRepository webhookEventRepository;
    private final OrderRepository orderRepository;
    private final AutoFulfillmentService autoFulfillmentService;
    private final ObjectMapper objectMapper;

    @Transactional
    public String confirmPaid(UUID orderId, String eventId, String channelCode,
                              Map<String, String> params) {
        if (webhookEventRepository.findByEventId(eventId).isPresent()) {
            return "SUCCESS";
        }

        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null) return "FAIL";
        // The second event read happens after the row lock, closing the race
        // between two identical gateway callbacks.
        if (webhookEventRepository.findByEventId(eventId).isPresent()) {
            return "SUCCESS";
        }

        String result;
        if (order.getStatus() == OrderStatus.PENDING) {
            order.setStatus(OrderStatus.PAID);
            order.setPaidAt(java.time.LocalDateTime.now());
            orderRepository.save(order);
            result = "SUCCESS";
        } else {
            result = "SKIPPED_" + order.getStatus().name();
        }

        WebhookEvent event = new WebhookEvent();
        event.setEventId(eventId);
        event.setChannelCode(channelCode);
        event.setOrderId(order.getId());
        event.setPayload(redactedPayload(params));
        event.setProcessResult(result);
        webhookEventRepository.save(event);

        Runnable trigger = () -> autoFulfillmentService.trigger(order.getId());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    trigger.run();
                }
            });
        } else {
            // Direct invocation is useful for focused unit tests; the Spring
            // runtime always takes the after-commit path above.
            trigger.run();
        }
        return "SUCCESS";
    }

    private String redactedPayload(Map<String, String> params) {
        Map<String, String> copy = new LinkedHashMap<>();
        params.forEach((key, value) -> {
            String lower = key == null ? "" : key.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("secret") || lower.contains("password") || lower.contains("token")
                    || lower.equals("key") || lower.equals("sign")) {
                copy.put(key, "[redacted]");
            } else {
                copy.put(key, value);
            }
        });
        try {
            return objectMapper.writeValueAsString(copy);
        } catch (Exception ignored) {
            return "{}";
        }
    }
}
