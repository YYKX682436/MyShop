package com.myshop.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.entity.Order;
import com.myshop.entity.PaymentChannel;
import com.myshop.payment.PaymentProvider;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.PaymentChannelRepository;
import com.myshop.repository.WebhookEventRepository;
import com.myshop.service.WebhookService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Epay callback verification. It performs all untrusted/provider I/O before
 * handing the trusted result to the separate payment-confirmation transaction.
 */
@Slf4j
@Service
public class WebhookServiceImpl implements WebhookService {

    private final WebhookEventRepository webhookEventRepository;
    private final OrderRepository orderRepository;
    private final PaymentChannelRepository paymentChannelRepository;
    private final List<PaymentProvider> paymentProviders;
    private final ObjectMapper objectMapper;
    private final PaymentConfirmationTransactionService confirmationService;

    @Autowired
    public WebhookServiceImpl(WebhookEventRepository webhookEventRepository,
                              OrderRepository orderRepository,
                              PaymentChannelRepository paymentChannelRepository,
                              List<PaymentProvider> paymentProviders,
                              ObjectMapper objectMapper,
                              PaymentConfirmationTransactionService confirmationService) {
        this.webhookEventRepository = webhookEventRepository;
        this.orderRepository = orderRepository;
        this.paymentChannelRepository = paymentChannelRepository;
        this.paymentProviders = paymentProviders;
        this.objectMapper = objectMapper;
        this.confirmationService = confirmationService;
    }

    @Override
    public String processEpayCallback(Map<String, String> params) {
        String tradeNo = clean(params.get("trade_no"));
        String outTradeNo = clean(params.get("out_trade_no"));
        String tradeStatus = clean(params.get("trade_status"));
        String money = clean(params.get("money"));

        if (tradeNo == null || outTradeNo == null) return "FAIL";
        String eventId = "epay:" + tradeNo;
        if (webhookEventRepository.findByEventId(eventId).isPresent()) return "SUCCESS";

        UUID orderId;
        try {
            orderId = UUID.fromString(outTradeNo);
        } catch (IllegalArgumentException exception) {
            log.warn("Rejected Epay callback with invalid order identifier");
            return "FAIL";
        }

        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            // Do not consume a callback that raced order creation.
            log.warn("Epay callback order was not found");
            return "FAIL";
        }

        ResolvedChannel resolvedChannel;
        try {
            resolvedChannel = resolveChannel(order);
        } catch (RuntimeException exception) {
            log.warn("Epay callback rejected because the channel profile is unavailable");
            return "FAIL";
        }

        if (!resolvedChannel.provider().verifyCallback(
                new PaymentProvider.CallbackRequest(params, resolvedChannel.config()))) {
            log.warn("Epay callback signature verification failed");
            return "FAIL";
        }

        // A non-success notification must not consume the identity of a later
        // success notification with the same gateway trade number.
        if (!"TRADE_SUCCESS".equalsIgnoreCase(tradeStatus)) return "SUCCESS";

        BigDecimal callbackAmount = parseAmount(money);
        if (callbackAmount == null || order.getActualAmount() == null
                || order.getActualAmount().compareTo(callbackAmount) != 0) {
            log.warn("Epay callback amount mismatch; event remains retryable");
            return "FAIL";
        }

        PaymentProvider.PaymentQueryResult query;
        try {
            query = resolvedChannel.provider().query(new PaymentProvider.PaymentQueryRequest(
                    outTradeNo, resolvedChannel.config()));
        } catch (RuntimeException exception) {
            log.warn("Epay callback server-side query failed");
            return "FAIL";
        }
        if (query == null || !isPaid(query.tradeStatus()) || !sameAmount(query.money(), order.getActualAmount())
                || !sameTrade(query.tradeNo(), tradeNo, outTradeNo)) {
            log.warn("Epay callback server-side verification failed");
            return "FAIL";
        }

        // Only this service mutates payment state. It commits PAID and the
        // webhook event first; its afterCommit hook starts fulfillment in a
        // separate transaction.
        return confirmationService.confirmPaid(orderId, eventId,
                resolvedChannel.channel().getChannelCode(), params);
    }

    private ResolvedChannel resolveChannel(Order order) {
        if (order.getPaymentMethod() == null || order.getPaymentMethod().isBlank()) {
            throw new IllegalStateException("payment method missing");
        }
        PaymentChannel channel = paymentChannelRepository
                .findByChannelCodeAndIsDeleted(order.getPaymentMethod(), 0)
                .orElseThrow(() -> new IllegalStateException("payment channel missing"));
        // Enabled controls only creation of new payment requests. The exact
        // channel profile must remain usable for an existing order callback,
        // even after an administrator disables the channel.
        Map<String, String> config = parseConfig(channel.getConfigData());
        PaymentProvider provider = paymentProviders.stream()
                .filter(candidate -> candidate.id().equalsIgnoreCase(channel.getProviderType()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("payment provider missing"));
        return new ResolvedChannel(channel, provider, config);
    }

    private Map<String, String> parseConfig(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalStateException("empty payment configuration");
        try {
            Map<String, Object> values = objectMapper.readValue(raw, new TypeReference<>() {
            });
            Map<String, String> result = new LinkedHashMap<>();
            values.forEach((key, value) -> {
                if (value != null) result.put(key, value.toString());
            });
            return result;
        } catch (Exception exception) {
            throw new IllegalStateException("invalid payment configuration", exception);
        }
    }

    private BigDecimal parseAmount(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private boolean sameAmount(String value, BigDecimal expected) {
        BigDecimal actual = parseAmount(value);
        return actual != null && expected != null && expected.compareTo(actual) == 0;
    }

    private boolean isPaid(String status) {
        return "TRADE_SUCCESS".equalsIgnoreCase(status) || "1".equals(status);
    }

    private boolean sameTrade(String queriedTrade, String callbackTrade, String outTrade) {
        return queriedTrade == null || queriedTrade.isBlank()
                || queriedTrade.equals(callbackTrade) || queriedTrade.equals(outTrade);
    }

    private String clean(String value) {
        if (value == null) return null;
        String result = value.trim();
        return result.isEmpty() ? null : result;
    }

    private record ResolvedChannel(PaymentChannel channel, PaymentProvider provider,
                                   Map<String, String> config) {
    }
}
