package com.myshop.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.constant.OrderStatus;
import com.myshop.entity.Order;
import com.myshop.entity.PaymentAttempt;
import com.myshop.entity.WebhookEvent;
import com.myshop.payment.PaymentProvider;
import com.myshop.payment.PaymentStatus;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.PaymentAttemptRepository;
import com.myshop.repository.WebhookEventRepository;
import com.myshop.service.AutoFulfillmentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Commits the payment attempt, order state, and webhook event before fulfillment. */
@Service
public class PaymentConfirmationTransactionService {

    private final WebhookEventRepository webhookEventRepository;
    private final OrderRepository orderRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final AutoFulfillmentService autoFulfillmentService;
    private final ObjectMapper objectMapper;

    public PaymentConfirmationTransactionService(WebhookEventRepository webhookEventRepository,
                                                 OrderRepository orderRepository,
                                                 PaymentAttemptRepository paymentAttemptRepository,
                                                 AutoFulfillmentService autoFulfillmentService,
                                                 ObjectMapper objectMapper) {
        this.webhookEventRepository = webhookEventRepository;
        this.orderRepository = orderRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.autoFulfillmentService = autoFulfillmentService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public String confirmPaid(UUID paymentAttemptId, PaymentProvider.CallbackResult callback) {
        if (callback == null || callback.status() != PaymentStatus.PAID) return "FAIL";
        if (webhookEventRepository.findByEventId(callback.eventId()).isPresent()) return "SUCCESS";

        // Read the attempt once to obtain its order id, then lock in the same
        // order-first order used by create/repay to avoid lock inversions.
        PaymentAttempt source = paymentAttemptRepository.findById(paymentAttemptId).orElse(null);
        if (source == null) return "FAIL";
        Order order = orderRepository.findByIdForUpdate(source.getOrderId()).orElse(null);
        if (order == null) return "FAIL";
        PaymentAttempt attempt = paymentAttemptRepository.findByIdForUpdate(paymentAttemptId).orElse(null);
        if (attempt == null) return "FAIL";

        // The second event read happens after row locks, closing the race
        // between identical callbacks.
        if (webhookEventRepository.findByEventId(callback.eventId()).isPresent()) return "SUCCESS";
        if (!matchesCallback(attempt, callback)) return "FAIL";
        if (attempt.getStatus() != PaymentStatus.PENDING) {
            // A closed attempt belongs to a previous repay and must never be
            // revived by a late provider notification.
            return attempt.getStatus() == PaymentStatus.PAID ? "SUCCESS" : "FAIL";
        }

        boolean orderWasPending = order.getStatus() == OrderStatus.PENDING;
        if (orderWasPending && order.getExpiresAt() != null
                && !order.getExpiresAt().isAfter(LocalDateTime.now())) {
            return "FAIL";
        }
        if (callback.providerTradeNo() != null && !callback.providerTradeNo().isBlank()) {
            attempt.setProviderTradeNo(callback.providerTradeNo());
        }
        if (orderWasPending) {
            attempt.setStatus(PaymentStatus.PAID);
            paymentAttemptRepository.save(attempt);
            order.setStatus(OrderStatus.PAID);
            order.setPaidAt(LocalDateTime.now());
            orderRepository.save(order);
        } else {
            // Preserve the payment fact even if another trusted path already
            // paid the order; the attempt and event still remain consistent.
            attempt.setStatus(PaymentStatus.PAID);
            paymentAttemptRepository.save(attempt);
        }

        WebhookEvent event = new WebhookEvent();
        event.setEventId(callback.eventId());
        event.setChannelCode(attempt.getChannelCodeSnapshot());
        event.setOrderId(attempt.getOrderId());
        event.setPaymentAttemptId(attempt.getId());
        event.setPayload(safePayload(callback));
        event.setProcessResult(orderWasPending ? "SUCCESS" : "SKIPPED_" + order.getStatus().name());
        webhookEventRepository.save(event);

        if (orderWasPending) {
            Runnable trigger = () -> autoFulfillmentService.trigger(order.getId());
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
        return "SUCCESS";
    }

    private boolean matchesCallback(PaymentAttempt attempt, PaymentProvider.CallbackResult callback) {
        if (!attempt.getMerchantTradeNo().equals(callback.merchantTradeNo())) return false;
        if (callback.amount() == null || attempt.getAmount() == null
                || attempt.getAmount().compareTo(callback.amount()) != 0) return false;
        String storedProviderTradeNo = attempt.getProviderTradeNo();
        return storedProviderTradeNo == null || storedProviderTradeNo.isBlank()
                || callback.providerTradeNo() == null
                || storedProviderTradeNo.equals(callback.providerTradeNo());
    }

    private String safePayload(PaymentProvider.CallbackResult callback) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event_id", callback.eventId());
        payload.put("merchant_trade_no", callback.merchantTradeNo());
        payload.put("provider_trade_no", callback.providerTradeNo());
        payload.put("status", callback.status());
        payload.put("amount", callback.amount());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ignored) {
            return "{}";
        }
    }
}
