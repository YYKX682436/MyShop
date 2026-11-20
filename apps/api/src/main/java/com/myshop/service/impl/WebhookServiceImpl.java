package com.myshop.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.entity.PaymentAttempt;
import com.myshop.payment.PaymentQueryUnavailableException;
import com.myshop.payment.PaymentProvider;
import com.myshop.payment.PaymentEventIdentity;
import com.myshop.payment.PaymentStatus;
import com.myshop.repository.PaymentAttemptRepository;
import com.myshop.repository.WebhookEventRepository;
import com.myshop.service.WebhookService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Generic webhook boundary; provider protocol details remain inside each adapter. */
@Service
public class WebhookServiceImpl implements WebhookService {

    private final WebhookEventRepository webhookEventRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final List<PaymentProvider> paymentProviders;
    private final ObjectMapper objectMapper;
    private final PaymentConfirmationTransactionService confirmationService;

    @Autowired
    public WebhookServiceImpl(WebhookEventRepository webhookEventRepository,
                              PaymentAttemptRepository paymentAttemptRepository,
                              List<PaymentProvider> paymentProviders,
                              ObjectMapper objectMapper,
                              PaymentConfirmationTransactionService confirmationService) {
        this.webhookEventRepository = webhookEventRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.paymentProviders = paymentProviders == null ? List.of() : List.copyOf(paymentProviders);
        this.objectMapper = objectMapper;
        this.confirmationService = confirmationService;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PaymentProvider.WebhookAcknowledgement handleWebhook(
            String providerId, PaymentProvider.WebhookRequest request) {
        PaymentProvider provider = providerForPath(providerId);
        if (provider == null || request == null) return PaymentProvider.WebhookAcknowledgement.failure();

        String merchantTradeNo;
        try {
            merchantTradeNo = clean(provider.extractMerchantTradeNo(request));
        } catch (RuntimeException exception) {
            return provider.failureAcknowledgement();
        }
        if (merchantTradeNo == null) return provider.failureAcknowledgement();

        PaymentAttempt attempt = paymentAttemptRepository.findByMerchantTradeNo(merchantTradeNo)
                .orElse(null);
        if (attempt == null || !provider.id().equalsIgnoreCase(attempt.getProviderType())) {
            return provider.failureAcknowledgement();
        }

        Map<String, String> config;
        try {
            config = parseConfigSnapshot(attempt.getProviderConfigSnapshot());
        } catch (RuntimeException exception) {
            return provider.failureAcknowledgement();
        }

        PaymentProvider.CallbackResult callback;
        try {
            callback = provider.verifyAndParseCallback(request, config);
        } catch (RuntimeException exception) {
            // Never include the snapshot or provider exception text in logs.
            return provider.failureAcknowledgement();
        }
        if (!validCallback(attempt, callback, merchantTradeNo)) {
            return provider.failureAcknowledgement();
        }

        var legacyEvent = webhookEventRepository.findByEventId(callback.eventId()).orElse(null);
        if (legacyEvent != null && attempt.getId().equals(legacyEvent.getPaymentAttemptId())) {
            return provider.successAcknowledgement();
        }
        callback = new PaymentProvider.CallbackResult(
                PaymentEventIdentity.scoped(provider.id(), merchantTradeNo, callback.eventId()),
                callback.merchantTradeNo(), callback.providerTradeNo(), callback.status(), callback.amount());
        var existing = webhookEventRepository.findByEventId(callback.eventId()).orElse(null);
        if (existing != null && !PaymentConfirmationTransactionService.UNVERIFIED_RECEIPT.equals(existing.getProcessResult()))
            return provider.successAcknowledgement();

        // A valid signed non-paid notification is acknowledged, but it does
        // not consume the event identity: a later PAID notification remains
        // eligible for confirmation.
        if (callback.status() != PaymentStatus.PAID) {
            return provider.successAcknowledgement();
        }

        String storedTrade = clean(attempt.getProviderTradeNo());
        String callbackTrade = clean(callback.providerTradeNo());
        if (storedTrade != null && callbackTrade != null && !storedTrade.equals(callbackTrade)) {
            // Preserve the signed but conflicting receipt for an operator;
            // never treat it as a settled order or overwrite the first trade.
            return "SUCCESS".equals(confirmationService.recordConflictingReceipt(attempt.getId(), callback))
                    ? provider.successAcknowledgement() : provider.failureAcknowledgement();
        }

        if (provider.supportsServerSideQuery() && !queryMatches(provider, attempt, callback, config)) {
            confirmationService.recordUnverifiedReceipt(attempt.getId(), callback);
            return provider.failureAcknowledgement();
        }

        String result = confirmationService.confirmPaid(attempt.getId(), callback);
        return "SUCCESS".equals(result)
                ? provider.successAcknowledgement()
                : provider.failureAcknowledgement();
    }

    private boolean queryMatches(PaymentProvider provider, PaymentAttempt attempt,
                                 PaymentProvider.CallbackResult callback,
                                 Map<String, String> config) {
        PaymentProvider.PaymentQueryResult query;
        try {
            query = provider.query(new PaymentProvider.PaymentQueryRequest(
                    attempt.getMerchantTradeNo(), config));
        } catch (PaymentQueryUnavailableException exception) {
            return provider.allowSignedCallbackWhenQueryUnavailable();
        } catch (RuntimeException exception) {
            return false;
        }
        if (query == null || query.status() != PaymentStatus.PAID
                || !sameAmount(query.amount(), attempt.getAmount())) {
            return false;
        }
        return sameTrade(query.providerTradeNo(), callback.providerTradeNo(), attempt.getProviderTradeNo());
    }

    private boolean validCallback(PaymentAttempt attempt, PaymentProvider.CallbackResult callback,
                                  String extractedMerchantTradeNo) {
        if (callback == null || callback.eventId() == null || callback.eventId().isBlank()
                || callback.status() == null
                || !extractedMerchantTradeNo.equals(callback.merchantTradeNo())
                || !attempt.getMerchantTradeNo().equals(callback.merchantTradeNo())
                || callback.amount() == null
                || !sameAmount(callback.amount(), attempt.getAmount())) {
            return false;
        }
        return true;
    }

    private Map<String, String> parseConfigSnapshot(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException();
        try {
            Map<String, Object> values = objectMapper.readValue(raw, new TypeReference<>() { });
            Map<String, String> result = new LinkedHashMap<>();
            values.forEach((key, value) -> {
                if (key != null && value != null) result.put(key, String.valueOf(value));
            });
            return result;
        } catch (Exception exception) {
            throw new IllegalArgumentException("invalid payment snapshot");
        }
    }

    private PaymentProvider providerForPath(String providerId) {
        String requested = clean(providerId);
        if (requested == null) return null;
        return paymentProviders.stream()
                .filter(provider -> provider.id().equalsIgnoreCase(requested))
                .findFirst()
                .orElse(null);
    }

    private boolean sameAmount(BigDecimal actual, BigDecimal expected) {
        return actual != null && expected != null && actual.compareTo(expected) == 0;
    }

    private boolean sameTrade(String queried, String callback, String created) {
        String queryTrade = clean(queried);
        String callbackTrade = clean(callback);
        String createdTrade = clean(created);
        if (queryTrade == null) return true;
        if (callbackTrade != null) return queryTrade.equals(callbackTrade);
        return createdTrade == null || queryTrade.equals(createdTrade);
    }

    private String clean(String value) {
        if (value == null) return null;
        String result = value.trim();
        return result.isEmpty() ? null : result;
    }
}
