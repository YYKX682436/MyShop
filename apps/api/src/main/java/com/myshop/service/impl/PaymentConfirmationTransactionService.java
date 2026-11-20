package com.myshop.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.constant.OrderStatus;
import com.myshop.entity.Order;
import com.myshop.entity.PaymentAttempt;
import com.myshop.constant.OrderPaymentMethod;
import com.myshop.constant.OrderType;
import com.myshop.entity.WebhookEvent;
import com.myshop.payment.PaymentProvider;
import com.myshop.payment.PaymentStatus;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.PaymentAttemptRepository;
import com.myshop.repository.WebhookEventRepository;
import com.myshop.repository.SupplierPurchaseRepository;
import com.myshop.service.OrderPaymentCompletionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Commits the payment attempt, order state, and webhook event before fulfillment. */
@Service
public class PaymentConfirmationTransactionService {

    public static final String UNVERIFIED_RECEIPT = "LATE_PAID_UNVERIFIED";

    private final WebhookEventRepository webhookEventRepository;
    private final OrderRepository orderRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final OrderPaymentCompletionService orderPaymentCompletionService;
    private final ObjectMapper objectMapper;
    private SupplierPurchaseRepository supplierPurchases;
    @Autowired public void setSupplierPurchases(SupplierPurchaseRepository value) { supplierPurchases=value; }

    public boolean isSupplierOrder(UUID orderId) {
        return supplierPurchases!=null && orderId!=null && supplierPurchases.existsByOrderId(orderId);
    }

    public PaymentConfirmationTransactionService(WebhookEventRepository webhookEventRepository,
                                                 OrderRepository orderRepository,
                                                 PaymentAttemptRepository paymentAttemptRepository,
                                                 OrderPaymentCompletionService orderPaymentCompletionService,
                                                 ObjectMapper objectMapper) {
        this.webhookEventRepository = webhookEventRepository;
        this.orderRepository = orderRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.orderPaymentCompletionService = orderPaymentCompletionService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public String confirmPaid(UUID paymentAttemptId, PaymentProvider.CallbackResult callback) {
        if (callback == null || callback.status() != PaymentStatus.PAID) return "FAIL";

        // Obtain only the scalar id before waiting for the order lock; loading
        // the entity here would keep a stale status in the persistence context.
        UUID orderId = paymentAttemptRepository.findOrderId(paymentAttemptId).orElse(null);
        if (orderId == null) return "FAIL";
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null) return "FAIL";
        PaymentAttempt attempt = paymentAttemptRepository.findByIdForUpdate(paymentAttemptId).orElse(null);
        if (attempt == null) return "FAIL";

        // The second event read happens after row locks, closing the race
        // between identical callbacks.
        WebhookEvent existing = webhookEventRepository.findByEventId(callback.eventId()).orElse(null);
        boolean upgrading = existing != null && UNVERIFIED_RECEIPT.equals(existing.getProcessResult());
        if (existing != null && !upgrading) return "SUCCESS";
        if (!matchesCallback(attempt, callback)) {
            if (attempt.getMerchantTradeNo().equals(callback.merchantTradeNo())
                    && callback.amount() != null && attempt.getAmount() != null
                    && attempt.getAmount().compareTo(callback.amount()) == 0
                    && attempt.getProviderTradeNo() != null && callback.providerTradeNo() != null
                    && !attempt.getProviderTradeNo().equals(callback.providerTradeNo())) {
                saveEvent(attempt, callback, "LATE_PAID_CONFLICT");
                return "SUCCESS";
            }
            return "FAIL";
        }
        if (upgrading) {
            // Exclude this notification itself from the late-payment guard;
            // the confirmation below updates the same event in this transaction.
            existing.setProcessResult("VERIFYING");
            webhookEventRepository.saveAndFlush(existing);
        }
        if (attempt.getStatus() == PaymentStatus.PAID) {
            // A manual mark-paid can precede the first actual gateway receipt.
            // Record that receipt instead of silently treating it as a duplicate.
            if (upgrading || !webhookEventRepository.existsByPaymentAttemptId(attempt.getId())) {
                if (attempt.getProviderTradeNo() == null && callback.providerTradeNo() != null)
                    attempt.setProviderTradeNo(callback.providerTradeNo());
                saveEvent(attempt, callback, "LATE_PAID_PAID");
            }
            return "SUCCESS";
        }
        boolean orderWasPending = order.getStatus() == OrderStatus.PENDING;
        boolean orderMatches = order.getOrderType() == OrderType.NORMAL
                && order.getPaymentMethod() == OrderPaymentMethod.ONLINE
                && java.util.Objects.equals(order.getPaymentChannelCode(), attempt.getChannelCodeSnapshot())
                && order.getActualAmount() != null && attempt.getAmount() != null
                && order.getActualAmount().compareTo(attempt.getAmount()) == 0;
        boolean late=attempt.getStatus()!=PaymentStatus.PENDING
                || attempt.getExpiresAt()!=null && !attempt.getExpiresAt().isAfter(LocalDateTime.now())
                || orderWasPending && order.getExpiresAt()!=null && !order.getExpiresAt().isAfter(LocalDateTime.now())
                || !orderWasPending || !orderMatches
                || webhookEventRepository.existsByOrderIdAndProcessResultStartingWith(orderId, "LATE_PAID_");
        if (late) {
            // A verified late payment is a separate fact requiring manual
            // handling. Do not revive an old attempt or trigger procurement.
            if (attempt.getProviderTradeNo() == null && callback.providerTradeNo() != null)
                attempt.setProviderTradeNo(callback.providerTradeNo());
            saveEvent(attempt,callback,"LATE_PAID_"+attempt.getStatus().name());
            return "SUCCESS";
        }
        if (callback.providerTradeNo() != null && !callback.providerTradeNo().isBlank()) {
            attempt.setProviderTradeNo(callback.providerTradeNo());
        }
        if (orderWasPending) {
            attempt.setStatus(PaymentStatus.PAID);
            paymentAttemptRepository.save(attempt);
            orderPaymentCompletionService.completeFirstPayment(order);
        } else {
            // Preserve the payment fact even if another trusted path already
            // paid the order; the attempt and event still remain consistent.
            attempt.setStatus(PaymentStatus.PAID);
            paymentAttemptRepository.save(attempt);
        }

        saveEvent(attempt,callback,orderWasPending ? "SUCCESS" : "SKIPPED_" + order.getStatus().name());
        return "SUCCESS";
    }

    /** Preserve a signed notification when the gateway cannot confirm it yet. */
    @Transactional
    public void recordUnverifiedReceipt(UUID paymentAttemptId, PaymentProvider.CallbackResult callback) {
        if (callback == null || callback.status() != PaymentStatus.PAID) return;
        UUID orderId = paymentAttemptRepository.findOrderId(paymentAttemptId).orElse(null);
        if (orderId == null || orderRepository.findByIdForUpdate(orderId).isEmpty()) return;
        PaymentAttempt attempt = paymentAttemptRepository.findByIdForUpdate(paymentAttemptId).orElse(null);
        if (attempt == null || !matchesCallback(attempt, callback)) return;
        WebhookEvent existing = webhookEventRepository.findByEventId(callback.eventId()).orElse(null);
        if (existing == null) saveEvent(attempt, callback, UNVERIFIED_RECEIPT);
    }

    /** A signed callback reporting a different gateway trade is evidence for
     * manual verification, not proof that the order should be fulfilled. */
    @Transactional
    public String recordConflictingReceipt(UUID paymentAttemptId, PaymentProvider.CallbackResult callback) {
        if (callback == null || callback.status() != PaymentStatus.PAID) return "FAIL";
        UUID orderId = paymentAttemptRepository.findOrderId(paymentAttemptId).orElse(null);
        if (orderId == null || orderRepository.findByIdForUpdate(orderId).isEmpty()) return "FAIL";
        PaymentAttempt attempt = paymentAttemptRepository.findByIdForUpdate(paymentAttemptId).orElse(null);
        if (attempt == null || !attempt.getMerchantTradeNo().equals(callback.merchantTradeNo())
                || callback.amount() == null || attempt.getAmount() == null
                || attempt.getAmount().compareTo(callback.amount()) != 0) return "FAIL";
        if (webhookEventRepository.findByEventId(callback.eventId()).isPresent()) return "SUCCESS";
        saveEvent(attempt, callback, "LATE_PAID_CONFLICT");
        return "SUCCESS";
    }

    private void saveEvent(PaymentAttempt attempt,PaymentProvider.CallbackResult callback,String result) {
        WebhookEvent event = webhookEventRepository.findByEventId(callback.eventId()).orElseGet(WebhookEvent::new);
        event.setEventId(callback.eventId());
        event.setChannelCode(attempt.getChannelCodeSnapshot());
        event.setOrderId(attempt.getOrderId());
        event.setPaymentAttemptId(attempt.getId());
        event.setPayload(safePayload(callback));
        event.setProcessResult(result);
        webhookEventRepository.save(event);
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
