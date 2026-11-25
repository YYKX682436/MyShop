package com.myshop.service;

import com.myshop.constant.OrderStatus;
import com.myshop.entity.Order;
import com.myshop.entity.PaymentAttempt;
import com.myshop.entity.AccountPaymentTransaction;
import com.myshop.payment.PaymentProvider;
import com.myshop.payment.PaymentStatus;
import com.myshop.payment.PaymentCreationStatus;
import com.myshop.payment.PaymentEventIdentity;
import com.myshop.service.impl.PaymentConfirmationTransactionService;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.PaymentAttemptRepository;
import com.myshop.repository.AccountPaymentTransactionRepository;
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
    private final AccountPaymentTransactionRepository accountPaymentTransactions;
    private final OrderExpiryService orderExpiryService;
    private final PaymentConfirmationTransactionService confirmationService;

    public record QueryContext(UUID attemptId, String providerType, String merchantTradeNo,
                               String providerConfigSnapshot) { }

    @Transactional
    public QueryContext prepare(UUID orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null) return null;
        orderExpiryService.expireLockedOrder(order);
        var attempts = paymentAttemptRepository.findByOrderIdOrderByCreatedAtDesc(orderId);
        var target = attempts.stream().filter(attempt -> attempt.getStatus() != PaymentStatus.PAID
                && (attempt.getCreationStatus() == PaymentCreationStatus.UNKNOWN
                || attempt.getCreationStatus() == PaymentCreationStatus.SUBMITTING)).findFirst()
                .orElseGet(() -> attempts.stream()
                        .filter(attempt -> attempt.getCreationStatus() != PaymentCreationStatus.INTENT
                                && attempt.getCreationStatus() != PaymentCreationStatus.REJECTED)
                        .findFirst().orElse(null));
        return target == null ? null : new QueryContext(target.getId(), target.getProviderType(),
                target.getMerchantTradeNo(), target.getProviderConfigSnapshot());
    }

    @Transactional
    public QueryContext prepareAccount(UUID accountTransactionId) {
        AccountPaymentTransaction transaction = accountPaymentTransactions.findByIdForUpdate(accountTransactionId).orElse(null);
        if (transaction == null) return null;
        var attempts = paymentAttemptRepository.findByAccountPaymentTransactionIdOrderByCreatedAtDesc(accountTransactionId);
        var target = attempts.stream().filter(attempt -> attempt.getStatus() != PaymentStatus.PAID
                && attempt.getCreationStatus() != PaymentCreationStatus.INTENT
                && attempt.getCreationStatus() != PaymentCreationStatus.REJECTED)
                .findFirst().orElseGet(() -> attempts.stream().findFirst().orElse(null));
        return target == null ? null : new QueryContext(target.getId(), target.getProviderType(),
                target.getMerchantTradeNo(), target.getProviderConfigSnapshot());
    }

    @Transactional
    public void complete(UUID orderId, QueryContext context, PaymentProvider.PaymentQueryResult query) {
        if (query == null || query.status() != PaymentStatus.PAID) return;
        UUID persistedOrderId = paymentAttemptRepository.findOrderId(context.attemptId()).orElse(null);
        if (!orderId.equals(persistedOrderId) || query.amount() == null
                || query.providerTradeNo() == null || query.providerTradeNo().isBlank()) return;
        confirmationService.confirmPaid(context.attemptId(), new PaymentProvider.CallbackResult(
                PaymentEventIdentity.scoped(context.providerType(), context.merchantTradeNo(),
                        context.providerType() + ":" + query.providerTradeNo()),
                context.merchantTradeNo(), query.providerTradeNo(), PaymentStatus.PAID, query.amount()));
    }

    @Transactional
    public void completeAccount(UUID accountTransactionId, QueryContext context,
                                PaymentProvider.PaymentQueryResult query) {
        if (query == null || query.status() != PaymentStatus.PAID) return;
        UUID persistedTransactionId = paymentAttemptRepository
                .findAccountPaymentTransactionId(context.attemptId()).orElse(null);
        if (!accountTransactionId.equals(persistedTransactionId) || query.amount() == null
                || query.providerTradeNo() == null || query.providerTradeNo().isBlank()) return;
        confirmationService.confirmPaid(context.attemptId(), new PaymentProvider.CallbackResult(
                PaymentEventIdentity.scoped(context.providerType(), context.merchantTradeNo(),
                        context.providerType() + ":" + query.providerTradeNo()),
                context.merchantTradeNo(), query.providerTradeNo(), PaymentStatus.PAID, query.amount()));
    }
}
