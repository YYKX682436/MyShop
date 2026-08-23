package com.myshop.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.constant.OrderStatus;
import com.myshop.entity.Order;
import com.myshop.entity.PaymentChannel;
import com.myshop.entity.WebhookEvent;
import com.myshop.payment.PaymentProvider;
import com.myshop.repository.PaymentChannelRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.WebhookEventRepository;
import com.myshop.service.WebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Epay callback processor.
 *
 * The callback is an untrusted notification. A payment can only become PAID
 * after signature verification, exact amount verification, and a server-side
 * order query against the same configured merchant profile.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookServiceImpl implements WebhookService {

    private final WebhookEventRepository webhookEventRepository;
    private final OrderRepository orderRepository;
    private final PaymentChannelRepository paymentChannelRepository;
    private final List<PaymentProvider> paymentProviders;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public String processEpayCallback(Map<String, String> params) {
        String tradeNo = clean(params.get("trade_no"));
        String outTradeNo = clean(params.get("out_trade_no"));
        String tradeStatus = clean(params.get("trade_status"));
        String money = clean(params.get("money"));

        if (tradeNo == null || outTradeNo == null) {
            return "FAIL";
        }

        String eventId = "epay:" + tradeNo;
        if (webhookEventRepository.findByEventId(eventId).isPresent()) {
            return "SUCCESS";
        }

        UUID orderId;
        try {
            orderId = UUID.fromString(outTradeNo);
        } catch (IllegalArgumentException exception) {
            log.warn("Rejected Epay callback with invalid order identifier");
            return "FAIL";
        }

        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            // Do not consume an event for a missing order: a callback can race
            // the order transaction and must remain retryable.
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
            // Never record an event before signature verification. Otherwise a
            // forged request could consume the id used by the real callback.
            log.warn("Epay callback signature verification failed");
            return "FAIL";
        }

        // A non-success notification must not block a later success
        // notification with the same gateway trade number.
        if (!"TRADE_SUCCESS".equalsIgnoreCase(tradeStatus)) {
            return "SUCCESS";
        }

        BigDecimal callbackAmount = parseAmount(money);
        if (callbackAmount == null || order.getActualAmount() == null
                || order.getActualAmount().compareTo(callbackAmount) != 0) {
            // An amount mismatch is rejected evidence, not a consumed event.
            // The provider may retry the valid notification with the same trade
            // number after a transient/malformed callback reaches us.
            log.warn("Epay callback amount mismatch; event remains retryable");
            return "FAIL";
        }

        // The callback is not sufficient evidence on its own. Query the
        // gateway using the exact profile stored on the payment channel and
        // require both paid status and an exact amount match.
        PaymentProvider.PaymentQueryResult query;
        try {
            query = resolvedChannel.provider().query(new PaymentProvider.PaymentQueryRequest(
                    outTradeNo, resolvedChannel.config()));
        } catch (RuntimeException exception) {
            log.warn("Epay callback server-side query failed", exception);
            return "FAIL";
        }
        if (query == null || !isPaid(query.tradeStatus()) || !sameAmount(query.money(), order.getActualAmount())
                || !sameTrade(query.tradeNo(), tradeNo, outTradeNo)) {
            log.warn("Epay callback server-side verification failed");
            return "FAIL";
        }

        // Lock and re-read immediately before mutating. Rechecking the event
        // after the row lock closes the concurrent duplicate-callback window.
        Order lockedOrder = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (lockedOrder == null) {
            return "FAIL";
        }
        if (webhookEventRepository.findByEventId(eventId).isPresent()) {
            return "SUCCESS";
        }

        String result;
        if (lockedOrder.getStatus() == OrderStatus.PENDING) {
            lockedOrder.setStatus(OrderStatus.PAID);
            lockedOrder.setPaidAt(LocalDateTime.now());
            orderRepository.save(lockedOrder);
            result = "SUCCESS";
        } else {
            // A delivered/paid order is harmlessly idempotent. An expired
            // order is recorded but is not silently reopened by a late pay.
            result = "SKIPPED_" + lockedOrder.getStatus().name();
        }

        recordEvent(eventId, resolvedChannel.channel().getChannelCode(), lockedOrder, result, params);
        return "SUCCESS";
    }

    private ResolvedChannel resolveChannel(Order order) {
        if (order.getPaymentMethod() == null || order.getPaymentMethod().isBlank()) {
            throw new IllegalStateException("payment method missing");
        }
        PaymentChannel channel = paymentChannelRepository
                .findByChannelCodeAndIsDeleted(order.getPaymentMethod(), 0)
                .orElseThrow(() -> new IllegalStateException("payment channel missing"));
        if (!channel.isEnabled() || !"epay".equalsIgnoreCase(channel.getProviderType())) {
            throw new IllegalStateException("payment channel disabled or unsupported");
        }
        Map<String, String> config = parseConfig(channel.getConfigData());
        PaymentProvider provider = paymentProviders.stream()
                .filter(candidate -> candidate.id().equalsIgnoreCase(channel.getProviderType()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("payment provider missing"));
        return new ResolvedChannel(channel, provider, config);
    }

    private Map<String, String> parseConfig(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("empty payment configuration");
        }
        try {
            Map<String, Object> values = objectMapper.readValue(raw, new TypeReference<>() {});
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

    private void recordEvent(String eventId, String channelCode, Order order, String result,
                             Map<String, String> params) {
        WebhookEvent event = new WebhookEvent();
        event.setEventId(eventId);
        event.setChannelCode(channelCode);
        event.setOrderId(order.getId());
        event.setPayload(redactedPayload(params));
        event.setProcessResult(result);
        webhookEventRepository.save(event);
    }

    private String redactedPayload(Map<String, String> params) {
        Map<String, String> copy = new LinkedHashMap<>(params);
        if (copy.containsKey("sign")) copy.put("sign", "[redacted]");
        try {
            return objectMapper.writeValueAsString(copy);
        } catch (Exception exception) {
            return "{}";
        }
    }

    private String clean(String value) {
        if (value == null) return null;
        String cleaned = value.trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private record ResolvedChannel(PaymentChannel channel, PaymentProvider provider, Map<String, String> config) { }
}
