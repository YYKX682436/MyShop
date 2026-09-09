package com.myshop.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.constant.ErrorCode;
import com.myshop.constant.FulfillmentStatus;
import com.myshop.constant.OrderPaymentMethod;
import com.myshop.constant.OrderStatus;
import com.myshop.entity.Order;
import com.myshop.entity.OrderItem;
import com.myshop.entity.PaymentAttempt;
import com.myshop.entity.PaymentChannel;
import com.myshop.exception.BusinessException;
import com.myshop.payment.PaymentNextAction;
import com.myshop.payment.PaymentNextActionPayload;
import com.myshop.payment.PaymentProvider;
import com.myshop.payment.PaymentStatus;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.PaymentAttemptRepository;
import com.myshop.repository.PaymentChannelRepository;
import com.myshop.service.ManualStockService;
import com.myshop.service.OrderPaymentCompletionService;
import com.myshop.service.PaymentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PaymentServiceImpl implements PaymentService {

    private static final int REPAY_COOLDOWN_SECONDS = 10;

    private final PaymentChannelRepository paymentChannelRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final List<PaymentProvider> paymentProviders;
    private final ObjectMapper objectMapper;
    private final ManualStockService manualStockService;
    private final OrderPaymentCompletionService orderPaymentCompletionService;

    @Autowired
    public PaymentServiceImpl(PaymentChannelRepository paymentChannelRepository,
                              OrderRepository orderRepository,
                              OrderItemRepository orderItemRepository,
                              PaymentAttemptRepository paymentAttemptRepository,
                              List<PaymentProvider> paymentProviders,
                              ObjectMapper objectMapper,
                              ManualStockService manualStockService,
                              OrderPaymentCompletionService orderPaymentCompletionService) {
        this.paymentChannelRepository = paymentChannelRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.paymentProviders = paymentProviders == null ? List.of() : List.copyOf(paymentProviders);
        this.objectMapper = objectMapper;
        this.manualStockService = manualStockService;
        this.orderPaymentCompletionService = orderPaymentCompletionService;
    }

    @Override
    public Map<String, Object> createPayment(UUID orderId, String paymentChannelCode, BigDecimal amount) {
        return createPayment(orderId, paymentChannelCode, amount, null);
    }

    @Override
    @Transactional
    public Map<String, Object> createPayment(UUID orderId, String paymentChannelCode,
                                              BigDecimal amount, String device) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));
        validateOnlineOrder(order);
        validateRequestedChannel(order, paymentChannelCode);

        validatePendingOrder(order);
        validateAmount(order, amount);
        PaymentAttempt active = findActiveAttempt(order.getId());
        if (active != null) return buildResult(order, active);

        PaymentContext context = resolveCreationContext(order.getPaymentChannelCode());
        return createAttempt(order, context.channel(), context.provider(), device);
    }

    @Override
    @Transactional
    public Map<String, Object> repay(UUID orderId, String device) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));
        validateOnlineOrder(order);
        validatePendingOrder(order);
        if (order.getUpdatedAt() != null
                && order.getUpdatedAt().plusSeconds(REPAY_COOLDOWN_SECONDS).isAfter(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "操作过于频繁，请稍后再试");
        }

        // Resolve the current enabled channel before closing the old attempt.
        // A repay is a new external transaction and must never use a disabled
        // channel, while history remains usable by the webhook path.
        PaymentContext context = resolveCreationContext(order.getPaymentChannelCode());
        closePendingAttempts(order.getId());
        return createAttempt(order, context.channel(), context.provider(), device);
    }

    @Override
    @Transactional
    public void refreshPayment(UUID orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.PENDING) return;
        if (order.getExpiresAt() != null && !order.getExpiresAt().isAfter(LocalDateTime.now())) {
            expireOrder(order);
            return;
        }

        PaymentAttempt attempt = findActiveAttempt(order.getId());
        if (attempt == null) return;
        PaymentProvider provider = paymentProviders.stream()
                .filter(candidate -> candidate.id().equalsIgnoreCase(attempt.getProviderType()))
                .findFirst()
                .orElse(null);
        if (provider == null || !provider.supportsServerSideQuery()) return;

        PaymentProvider.PaymentQueryResult query;
        try {
            query = provider.query(new PaymentProvider.PaymentQueryRequest(
                    attempt.getMerchantTradeNo(), parseConfig(attempt.getProviderConfigSnapshot())));
        } catch (RuntimeException ignored) {
            return;
        }
        if (query == null || query.status() != PaymentStatus.PAID
                || !sameAmount(query.amount(), attempt.getAmount())
                || !sameProviderTrade(query.providerTradeNo(), attempt.getProviderTradeNo())) {
            return;
        }
        attempt.setProviderTradeNo(query.providerTradeNo());
        attempt.setStatus(PaymentStatus.PAID);
        paymentAttemptRepository.save(attempt);
        orderPaymentCompletionService.completeFirstPayment(order);
    }

    private Map<String, Object> createAttempt(Order order, PaymentChannel channel,
                                              PaymentProvider provider, String device) {
        String configSnapshot = requiredConfigSnapshot(channel.getConfigData());
        Map<String, String> providerConfig = parseConfig(configSnapshot);
        String merchantTradeNo = nextMerchantTradeNo();
        String productName = buildProductName(order.getId());
        PaymentProvider.PaymentResult providerResult = provider.create(
                new PaymentProvider.PaymentRequest(
                        merchantTradeNo,
                        channel.getPaymentType(),
                        productName,
                        order.getActualAmount(),
                        order.getClientIp(),
                        device,
                        providerConfig,
                        order.getId().toString()));
        if (providerResult == null || providerResult.nextAction() == null) {
            throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE, "支付提供商未返回有效支付动作");
        }

        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setOrderId(order.getId());
        attempt.setChannelId(channel.getId());
        attempt.setChannelCodeSnapshot(channel.getChannelCode());
        attempt.setProviderType(provider.id());
        attempt.setPaymentType(channel.getPaymentType());
        attempt.setProviderConfigSnapshot(configSnapshot);
        attempt.setMerchantTradeNo(merchantTradeNo);
        attempt.setProviderTradeNo(providerResult.providerTradeNo());
        attempt.setAmount(order.getActualAmount());
        attempt.setStatus(PaymentStatus.PENDING);
        attempt.setNextAction(serializeNextAction(providerResult.nextAction()));
        attempt.setExpiresAt(order.getExpiresAt());
        paymentAttemptRepository.save(attempt);
        return buildResult(order, attempt);
    }

    private PaymentContext resolveCreationContext(String channelCode) {
        PaymentChannel channel = paymentChannelRepository
                .findByChannelCodeAndIsDeleted(channelCode, 0)
                .filter(PaymentChannel::isEnabled)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE, "支付渠道不可用"));
        PaymentProvider provider = paymentProviders.stream()
                .filter(candidate -> candidate.id().equalsIgnoreCase(channel.getProviderType()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE, "支付提供商未注册"));
        if (provider.paymentTypes().stream()
                .noneMatch(type -> type.equalsIgnoreCase(channel.getPaymentType()))) {
            throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE, "支付方式不受该提供商支持");
        }
        return new PaymentContext(channel, provider);
    }

    private PaymentAttempt findActiveAttempt(UUID orderId) {
        return paymentAttemptRepository
                .findFirstByOrderIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                        orderId, PaymentStatus.PENDING, LocalDateTime.now())
                .orElse(null);
    }

    private void closePendingAttempts(UUID orderId) {
        List<PaymentAttempt> attempts = paymentAttemptRepository
                .findByOrderIdAndStatusForUpdate(orderId, PaymentStatus.PENDING);
        for (PaymentAttempt attempt : attempts) {
            attempt.setStatus(PaymentStatus.CLOSED);
            paymentAttemptRepository.save(attempt);
        }
    }

    private String nextMerchantTradeNo() {
        String candidate;
        do {
            candidate = "m_" + Long.toUnsignedString(System.currentTimeMillis(), 36)
                    + "_" + UUID.randomUUID().toString().replace("-", "");
        } while (paymentAttemptRepository.existsByMerchantTradeNo(candidate));
        return candidate;
    }

    private Map<String, Object> buildResult(Order order, PaymentAttempt attempt) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("payment_id", attempt.getId());
        result.put("order_id", order.getId());
        result.put("status", attempt.getStatus());
        result.put("payment_method", OrderPaymentMethod.ONLINE.name());
        result.put("payment_type", attempt.getPaymentType());
        result.put("payment_channel_code", order.getPaymentChannelCode());
        result.put("next_action", PaymentNextActionPayload.from(
                deserializeNextAction(attempt.getNextAction())));
        result.put("expires_at", attempt.getExpiresAt());
        result.put("remaining_seconds", remainingSeconds(attempt.getExpiresAt()));
        return result;
    }

    private long remainingSeconds(LocalDateTime expiresAt) {
        if (expiresAt == null) return 0;
        return Math.max(0, java.time.Duration.between(LocalDateTime.now(), expiresAt).getSeconds());
    }

    private boolean sameProviderTrade(String queried, String stored) {
        String queryTrade = clean(queried);
        String storedTrade = clean(stored);
        return queryTrade != null && (storedTrade == null || queryTrade.equals(storedTrade));
    }

    private boolean sameAmount(BigDecimal actual, BigDecimal expected) {
        return actual != null && expected != null && actual.compareTo(expected) == 0;
    }

    private String serializeNextAction(PaymentNextAction action) {
        try {
            return objectMapper.writeValueAsString(action);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE, "支付动作无法保存");
        }
    }

    private PaymentNextAction deserializeNextAction(String raw) {
        try {
            if (raw == null || raw.isBlank()) throw new IllegalArgumentException();
            return objectMapper.readValue(raw, PaymentNextAction.class);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE, "支付动作数据无效");
        }
    }

    private String requiredConfigSnapshot(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE, "支付渠道配置不可用");
        }
        // Validate the snapshot as an object without putting its contents in
        // an exception or log message.
        try {
            Map<String, Object> ignored = objectMapper.readValue(raw, new TypeReference<>() { });
            return raw.trim();
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE, "支付渠道配置不可用");
        }
    }

    private Map<String, String> parseConfig(String raw) {
        try {
            Map<String, Object> values = objectMapper.readValue(raw, new TypeReference<>() { });
            Map<String, String> result = new LinkedHashMap<>();
            values.forEach((key, value) -> {
                if (key != null && value != null) result.put(key, String.valueOf(value));
            });
            return result;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE, "支付渠道配置不可用");
        }
    }

    private void validateRequestedChannel(Order order, String requestedChannel) {
        String actual = clean(order.getPaymentChannelCode());
        String requested = clean(requestedChannel);
        if (actual == null || requested == null || !actual.equalsIgnoreCase(requested)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "订单支付渠道不匹配");
        }
    }

    private void validateOnlineOrder(Order order) {
        if (order.getOrderType() != com.myshop.constant.OrderType.NORMAL
                || order.getPaymentMethod() != OrderPaymentMethod.ONLINE
                || order.getPaymentChannelCode() == null
                || order.getPaymentChannelCode().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "只有在线支付订单支持该操作");
        }
    }

    private void validatePendingOrder(Order order) {
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new BusinessException(ErrorCode.ORDER_EXPIRED, "订单状态不允许发起支付");
        }
        if (order.getExpiresAt() != null && order.getExpiresAt().isBefore(LocalDateTime.now())) {
            expireOrder(order);
            throw new BusinessException(ErrorCode.ORDER_EXPIRED, "订单已过期");
        }
        if (order.getActualAmount() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "订单金额异常");
        }
    }

    private void validateAmount(Order order, BigDecimal amount) {
        if (order.getActualAmount() == null || amount == null
                || order.getActualAmount().compareTo(amount) != 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "订单金额异常");
        }
    }

    private String buildProductName(UUID orderId) {
        List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
        if (items.isEmpty()) return "MyShop 订单";
        String firstName = items.get(0).getProductTitle();
        return items.size() == 1 ? firstName : firstName + " 等" + items.size() + "件商品";
    }

    private void expireOrder(Order order) {
        for (PaymentAttempt attempt : paymentAttemptRepository
                .findByOrderIdAndStatusForUpdate(order.getId(), PaymentStatus.PENDING)) {
            attempt.setStatus(PaymentStatus.CLOSED);
            paymentAttemptRepository.save(attempt);
        }
        for (OrderItem item : orderItemRepository.findByOrderId(order.getId())) {
            int reserved = item.getManualStockReserved();
            if (reserved > 0) {
                manualStockService.release(item.getProductId(), item.getSpecId(), reserved);
                item.setManualStockReserved(0);
            }
            item.setFulfillmentStatus(FulfillmentStatus.CANCELLED);
            orderItemRepository.save(item);
        }
        order.setStatus(OrderStatus.EXPIRED);
        orderRepository.save(order);
    }

    private String clean(String value) {
        if (value == null) return null;
        String result = value.trim();
        return result.isEmpty() ? null : result;
    }

    private record PaymentContext(PaymentChannel channel, PaymentProvider provider) { }

}
