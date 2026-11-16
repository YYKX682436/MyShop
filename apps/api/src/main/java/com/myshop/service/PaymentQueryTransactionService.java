package com.myshop.service;

import com.myshop.constant.OrderStatus;
import com.myshop.entity.Order;
import com.myshop.entity.PaymentAttempt;
import com.myshop.payment.PaymentProvider;
import com.myshop.payment.PaymentStatus;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.PaymentAttemptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/** The network query runs between these two short transactions. */
@Service
@RequiredArgsConstructor
public class PaymentQueryTransactionService {
    private final OrderRepository orderRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final OrderExpiryService orderExpiryService;
    private final OrderPaymentCompletionService orderPaymentCompletionService;

    public record QueryContext(UUID attemptId, String providerType, String merchantTradeNo,
                               String providerConfigSnapshot) { }

    @Transactional
    public QueryContext prepare(UUID orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null) return null;
        orderExpiryService.expireLockedOrder(order);
        if (order.getStatus() != OrderStatus.PENDING) return null;
        return paymentAttemptRepository.findFirstByOrderIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                        orderId, PaymentStatus.PENDING, LocalDateTime.now())
                .map(attempt -> new QueryContext(attempt.getId(), attempt.getProviderType(),
                        attempt.getMerchantTradeNo(), attempt.getProviderConfigSnapshot()))
                .orElse(null);
    }

    @Transactional
    public void complete(UUID orderId, UUID attemptId, PaymentProvider.PaymentQueryResult query) {
        if (query == null || query.status() != PaymentStatus.PAID) return;
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null) return;
        orderExpiryService.expireLockedOrder(order);
        if (order.getStatus() != OrderStatus.PENDING) return;
        PaymentAttempt attempt = paymentAttemptRepository.findByIdForUpdate(attemptId).orElse(null);
        if (attempt == null || !orderId.equals(attempt.getOrderId())
                || attempt.getStatus() != PaymentStatus.PENDING
                || attempt.getExpiresAt() == null || !attempt.getExpiresAt().isAfter(LocalDateTime.now())
                || query.amount() == null || attempt.getAmount() == null
                || query.amount().compareTo(attempt.getAmount()) != 0
                || query.providerTradeNo() == null || query.providerTradeNo().isBlank()) {
            return;
        }
        String storedTrade = attempt.getProviderTradeNo();
        if (storedTrade != null && !storedTrade.isBlank()
                && !storedTrade.trim().equals(query.providerTradeNo().trim())) {
            return;
        }
        attempt.setProviderTradeNo(query.providerTradeNo());
        attempt.setStatus(PaymentStatus.PAID);
        paymentAttemptRepository.save(attempt);
        orderPaymentCompletionService.completeFirstPayment(order);
    }
}
