package com.myshop.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.constant.ErrorCode;
import com.myshop.constant.OrderPaymentMethod;
import com.myshop.constant.OrderStatus;
import com.myshop.constant.StockMode;
import com.myshop.entity.Order;
import com.myshop.entity.OrderItem;
import com.myshop.entity.PaymentAttempt;
import com.myshop.entity.PaymentChannel;
import com.myshop.exception.BusinessException;
import com.myshop.exception.OrderExpiredException;
import com.myshop.payment.PaymentNextAction;
import com.myshop.payment.PaymentNextActionPayload;
import com.myshop.payment.PaymentProvider;
import com.myshop.payment.PaymentStatus;
import com.myshop.payment.PaymentCreationStatus;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.PaymentAttemptRepository;
import com.myshop.repository.PaymentChannelRepository;
import com.myshop.repository.WebhookEventRepository;
import com.myshop.service.OrderExpiryService;
import com.myshop.service.PaymentQueryTransactionService;
import com.myshop.service.PaymentService;
import com.myshop.service.PaymentUrlService;
import com.myshop.supplier.SupplierOrderAvailability;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.concurrent.atomic.AtomicReference;

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
    private final OrderExpiryService orderExpiryService;
    private final PaymentQueryTransactionService paymentQueryTransactionService;
    private TransactionTemplate transactions;
    @Autowired public void setTransactionManager(PlatformTransactionManager manager) { this.transactions = new TransactionTemplate(manager); }
    private com.myshop.repository.SubsiteRepository subsites;
    @Autowired public void setSubsites(com.myshop.repository.SubsiteRepository value) { subsites = value; }
    private PaymentUrlService paymentUrls;
    @Autowired public void setPaymentUrls(PaymentUrlService value) { paymentUrls = value; }
    private WebhookEventRepository paymentEvents;
    @Autowired public void setPaymentEvents(WebhookEventRepository paymentEvents) { this.paymentEvents=paymentEvents; }
    private final SupplierOrderAvailability supplierOrderAvailability;

    @Autowired
    public PaymentServiceImpl(PaymentChannelRepository paymentChannelRepository,
                              OrderRepository orderRepository,
                              OrderItemRepository orderItemRepository,
                              PaymentAttemptRepository paymentAttemptRepository,
                              List<PaymentProvider> paymentProviders,
                              ObjectMapper objectMapper,
                              OrderExpiryService orderExpiryService,
                              PaymentQueryTransactionService paymentQueryTransactionService,
                              SupplierOrderAvailability supplierOrderAvailability) {
        this.paymentChannelRepository = paymentChannelRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.paymentProviders = paymentProviders == null ? List.of() : List.copyOf(paymentProviders);
        this.objectMapper = objectMapper;
        this.orderExpiryService = orderExpiryService;
        this.paymentQueryTransactionService = paymentQueryTransactionService;
        this.supplierOrderAvailability = supplierOrderAvailability;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Map<String, Object> createPayment(UUID orderId, String paymentChannelCode, BigDecimal amount) {
        return createPayment(orderId, paymentChannelCode, amount, null);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Map<String, Object> createPayment(UUID orderId, String paymentChannelCode,
                                              BigDecimal amount, String device) {
        Map<String, Object> intent = inTransactionPreservingExpiry(
                () -> preparePayment(orderId, paymentChannelCode, amount, device));
        return dispatchPayment((UUID) intent.get("payment_id"));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = OrderExpiredException.class)
    public Map<String, Object> preparePayment(UUID orderId, String paymentChannelCode,
                                               BigDecimal amount, String device) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));
        validateOnlineOrder(order);
        validateRequestedChannel(order, paymentChannelCode);
        validatePendingOrder(order);
        validateAmount(order, amount);
        validateSupplierPaymentEnabled(order);
        List<PaymentAttempt> pending = paymentAttemptRepository.findByOrderIdAndStatusForUpdate(orderId, PaymentStatus.PENDING);
        PaymentAttempt active = pending.stream().filter(this::creationUnresolved).findFirst().orElse(null);
        if (active == null) active = findActiveAttempt(order.getId());
        if (active != null) {
            if (!java.util.Objects.equals(active.getChannelCodeSnapshot(), order.getPaymentChannelCode())
                    || active.getAmount() == null || active.getAmount().compareTo(order.getActualAmount()) != 0)
                throw new BusinessException(ErrorCode.BAD_REQUEST, "支付记录与订单不一致，请联系客服");
            return buildResult(order, active);
        }
        PaymentContext context = resolveCreationContext(order.getPaymentChannelCode());
        return createIntent(order, context.channel(), context.provider(), device);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> inspectPayment(UUID orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));
        List<PaymentAttempt> attempts = paymentAttemptRepository.findByOrderIdOrderByCreatedAtDesc(orderId);
        PaymentAttempt attempt = attempts.stream().filter(value -> value.getStatus() == PaymentStatus.PENDING
                && creationUnresolved(value)).findFirst()
                .orElseGet(() -> attempts.stream().findFirst().orElse(null));
        return attempt == null ? null : buildResult(order, attempt);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Map<String, Object> repay(UUID orderId, String device) {
        Map<String, Object> intent = inTransactionPreservingExpiry(() -> prepareRepayment(orderId, device));
        return dispatchPayment((UUID) intent.get("payment_id"));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = OrderExpiredException.class)
    public Map<String, Object> prepareRepayment(UUID orderId, String device) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));
        validateOnlineOrder(order);
        validatePendingOrder(order);
        validateSupplierPaymentEnabled(order);
        List<PaymentAttempt> pending = paymentAttemptRepository.findByOrderIdAndStatusForUpdate(orderId, PaymentStatus.PENDING);
        if (pending.stream().anyMatch(attempt -> !java.util.Objects.equals(attempt.getChannelCodeSnapshot(), order.getPaymentChannelCode())
                || attempt.getAmount() == null || attempt.getAmount().compareTo(order.getActualAmount()) != 0))
            throw new BusinessException(ErrorCode.BAD_REQUEST, "支付记录与订单不一致，请联系客服");
        PaymentAttempt unresolved = pending.stream().filter(this::creationUnresolved).findFirst().orElse(null);
        if (unresolved != null) {
            markStaleUnknown(unresolved);
            return buildResult(order, unresolved);
        }
        PaymentAttempt latest = paymentAttemptRepository.findFirstByOrderIdOrderByCreatedAtDesc(orderId).orElse(null);
        if (latest != null && latest.getCreatedAt() != null
                && latest.getCreatedAt().plusSeconds(REPAY_COOLDOWN_SECONDS).isAfter(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "操作过于频繁，请稍后再试");
        }
        PaymentContext context = resolveCreationContext(order.getPaymentChannelCode());
        closePendingAttempts(pending);
        return createIntent(order, context.channel(), context.provider(), device);
    }

    private <T> T inTransactionPreservingExpiry(java.util.function.Supplier<T> operation) {
        AtomicReference<OrderExpiredException> expired = new AtomicReference<>();
        T result = transactions.execute(status -> {
            try { return operation.get(); }
            catch (OrderExpiredException exception) { expired.set(exception); return null; }
        });
        if (expired.get() != null) throw expired.get();
        return result;
    }

    /** Only INTENT can be claimed. SUBMITTING survives a crash without being sent again. */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Map<String, Object> dispatchPayment(UUID paymentId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Payment creation requires a committed transaction boundary");
        }
        CreationRequest request = transactions.execute(status -> claim(paymentId));
        if (request == null) return readPayment(paymentId);
        PaymentProvider provider = paymentProviders.stream()
                .filter(candidate -> candidate.id().equalsIgnoreCase(request.providerType()))
                .findFirst().orElse(null);
        if (provider == null) {
            transactions.executeWithoutResult(status -> rejectUnsent(paymentId));
            return readPayment(paymentId);
        }
        try {
            PaymentProvider.PaymentResult result = provider.create(new PaymentProvider.PaymentRequest(
                    request.merchantTradeNo(), request.paymentType(), request.productName(), request.amount(),
                    request.clientIp(), request.device(), parseConfig(request.config()), request.orderId().toString(),
                    request.notifyUrl(), request.returnUrl()));
            if (result == null) throw new IllegalStateException("Missing response");
            String action;
            try { action = result.nextAction() == null ? null : serializeNextAction(result.nextAction()); }
            catch (RuntimeException exception) { action = null; }
            String returnedAction = action;
            transactions.executeWithoutResult(status -> completeCreation(request, result.providerTradeNo(), returnedAction));
        } catch (RuntimeException exception) {
            // A provider exception or a failed result commit cannot establish that
            // the gateway did not receive the request. Never submit it again.
            try { transactions.executeWithoutResult(status -> unknown(paymentId)); }
            catch (RuntimeException ignored) { /* SUBMITTING itself remains a durable no-retry state. */ }
        }
        try { return readPayment(paymentId); }
        catch (RuntimeException exception) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("payment_id", paymentId);
            fallback.put("order_id", request.orderId());
            fallback.put("order_no", request.orderNo());
            fallback.put("status", PaymentStatus.PENDING);
            fallback.put("creation_status", PaymentCreationStatus.UNKNOWN);
            fallback.put("payment_method", OrderPaymentMethod.ONLINE.name());
            fallback.put("payment_type", request.paymentType());
            fallback.put("payment_channel_code", request.channelCode());
            fallback.put("next_action", null);
            fallback.put("expires_at", request.expiresAt());
            fallback.put("remaining_seconds", remainingSeconds(request.expiresAt()));
            fallback.put("can_retry", false);
            return fallback;
        }
    }

    private CreationRequest claim(UUID paymentId) {
        UUID orderId = paymentAttemptRepository.findOrderId(paymentId).orElseThrow();
        Order order = orderRepository.findByIdForUpdate(orderId).orElseThrow();
        PaymentAttempt attempt = paymentAttemptRepository.findByIdForUpdate(paymentId).orElseThrow();
        if (attempt.getCreationStatus() != PaymentCreationStatus.INTENT) {
            markStaleUnknown(attempt);
            return null;
        }
        orderExpiryService.expireLockedOrder(order);
        List<OrderItem> orderItems = orderItemRepository.findByOrderId(orderId);
        boolean supplierBlocked = orderItems.stream().anyMatch(item -> item.getStockMode() == StockMode.SUPPLIER)
                && !supplierOrderAvailability.allowsPayment(orderId, orderItems);
        boolean latePayment = paymentEvents != null
                && paymentEvents.existsByOrderIdAndProcessResultStartingWith(orderId, "LATE_PAID_");
        if (order.getStatus() != OrderStatus.PENDING || attempt.getStatus() != PaymentStatus.PENDING
                || attempt.getExpiresAt() != null && !attempt.getExpiresAt().isAfter(LocalDateTime.now())
                || supplierBlocked || latePayment
                || !java.util.Objects.equals(order.getPaymentChannelCode(), attempt.getChannelCodeSnapshot())
                || order.getActualAmount() == null || attempt.getAmount() == null
                || order.getActualAmount().compareTo(attempt.getAmount()) != 0) {
            attempt.setCreationStatus(PaymentCreationStatus.REJECTED);
            return null;
        }
        attempt.setCreationStatus(PaymentCreationStatus.SUBMITTING);
        attempt.setCreationStartedAt(LocalDateTime.now());
        return new CreationRequest(attempt.getId(), orderId, order.getOrderNo(), attempt.getExpiresAt(), attempt.getMerchantTradeNo(), attempt.getProviderType(),
                attempt.getChannelCodeSnapshot(), attempt.getPaymentType(), attempt.getProductNameSnapshot(), attempt.getAmount(),
                attempt.getClientIpSnapshot(), attempt.getCreationDevice(), attempt.getProviderConfigSnapshot(),
                attempt.getNotifyUrlSnapshot(), attempt.getReturnUrlSnapshot());
    }

    private void completeCreation(CreationRequest request, String providerTradeNo, String action) {
        Order order = orderRepository.findByIdForUpdate(request.orderId()).orElseThrow();
        PaymentAttempt attempt = paymentAttemptRepository.findByIdForUpdate(request.attemptId()).orElseThrow();
        if (!request.orderId().equals(attempt.getOrderId())
                || !request.merchantTradeNo().equals(attempt.getMerchantTradeNo())
                || !request.providerType().equals(attempt.getProviderType())
                || !request.channelCode().equals(attempt.getChannelCodeSnapshot())
                || !request.paymentType().equals(attempt.getPaymentType())
                || request.amount().compareTo(attempt.getAmount()) != 0
                || !request.config().equals(attempt.getProviderConfigSnapshot())
                || !java.util.Objects.equals(request.notifyUrl(), attempt.getNotifyUrlSnapshot())
                || !java.util.Objects.equals(request.returnUrl(), attempt.getReturnUrlSnapshot())) {
            throw new IllegalStateException("Payment intent snapshot mismatch");
        }
        String storedTrade = clean(attempt.getProviderTradeNo());
        String receivedTrade = clean(providerTradeNo);
        attempt.setCreateResponseTradeNo(receivedTrade);
        if (storedTrade != null && receivedTrade != null && !storedTrade.equals(receivedTrade)) {
            attempt.setNextAction(action);
            attempt.setCreationStatus(PaymentCreationStatus.UNKNOWN);
            return;
        }
        if (storedTrade == null) attempt.setProviderTradeNo(receivedTrade);
        attempt.setNextAction(action);
        boolean orderMatches = java.util.Objects.equals(order.getPaymentChannelCode(), request.channelCode())
                && order.getActualAmount() != null && order.getActualAmount().compareTo(request.amount()) == 0;
        attempt.setCreationStatus(orderMatches && action != null ? PaymentCreationStatus.READY : PaymentCreationStatus.UNKNOWN);
        // Never change settlement status or revive a paid, expired or refunded order.
    }

    private void rejectUnsent(UUID paymentId) {
        PaymentAttempt attempt = paymentAttemptRepository.findByIdForUpdate(paymentId).orElseThrow();
        if (attempt.getCreationStatus() == PaymentCreationStatus.SUBMITTING) {
            attempt.setCreationStatus(PaymentCreationStatus.REJECTED);
        }
    }

    private void unknown(UUID paymentId) {
        PaymentAttempt attempt = paymentAttemptRepository.findByIdForUpdate(paymentId).orElseThrow();
        if (attempt.getCreationStatus() == PaymentCreationStatus.SUBMITTING) {
            attempt.setCreationStatus(PaymentCreationStatus.UNKNOWN);
        }
    }

    private void markStaleUnknown(PaymentAttempt attempt) {
        if (attempt.getCreationStatus() == PaymentCreationStatus.SUBMITTING
                && attempt.getCreationStartedAt() != null
                && attempt.getCreationStartedAt().plusMinutes(2).isBefore(LocalDateTime.now())) {
            attempt.setCreationStatus(PaymentCreationStatus.UNKNOWN);
        }
    }

    private Map<String, Object> readPayment(UUID paymentId) {
        return transactions.execute(status -> {
            UUID orderId = paymentAttemptRepository.findOrderId(paymentId).orElseThrow();
            Order order = orderRepository.findByIdForUpdate(orderId).orElseThrow();
            PaymentAttempt attempt = paymentAttemptRepository.findByIdForUpdate(paymentId).orElseThrow();
            markStaleUnknown(attempt);
            return buildResult(order, attempt);
        });
    }

    private record CreationRequest(UUID attemptId, UUID orderId, Long orderNo, LocalDateTime expiresAt, String merchantTradeNo, String providerType, String channelCode,
                                   String paymentType, String productName, BigDecimal amount, String clientIp,
                                   String device, String config, String notifyUrl, String returnUrl) { }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void refreshPayment(UUID orderId) {
        var context = paymentQueryTransactionService.prepare(orderId);
        if (context == null) return;
        PaymentProvider provider = paymentProviders.stream()
                .filter(candidate -> candidate.id().equalsIgnoreCase(context.providerType()))
                .findFirst().orElse(null);
        if (provider == null || !provider.supportsServerSideQuery()) return;
        PaymentProvider.PaymentQueryResult query;
        try {
            query = provider.query(new PaymentProvider.PaymentQueryRequest(
                    context.merchantTradeNo(), parseConfig(context.providerConfigSnapshot())));
        } catch (RuntimeException ignored) {
            return;
        }
        // A callback, repay or expiry may have won during the provider call.
        // Complete only after reloading and locking the current rows.
        paymentQueryTransactionService.complete(orderId, context, query);
    }

    private Map<String, Object> createIntent(Order order, PaymentChannel channel,
                                             PaymentProvider provider, String device) {
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setOrderId(order.getId());
        attempt.setChannelId(channel.getId());
        attempt.setChannelCodeSnapshot(channel.getChannelCode());
        attempt.setProviderType(provider.id());
        attempt.setPaymentType(channel.getPaymentType());
        attempt.setProviderConfigSnapshot(requiredConfigSnapshot(channel.getConfigData()));
        attempt.setMerchantTradeNo(nextMerchantTradeNo());
        attempt.setAmount(order.getActualAmount());
        attempt.setStatus(PaymentStatus.PENDING);
        attempt.setCreationStatus(PaymentCreationStatus.INTENT);
        attempt.setCreationDevice(clean(device));
        attempt.setProductNameSnapshot(buildProductName(order.getId()));
        attempt.setClientIpSnapshot(order.getClientIp());
        attempt.setNotifyUrlSnapshot(paymentUrls.webhookUrl(provider.id()));
        String subsitePrefix = null;
        if (order.getSubsiteId() != null) {
            if (subsites == null) throw new IllegalStateException("分站服务不可用");
            subsitePrefix = subsites.findById(order.getSubsiteId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "订单所属分站不存在")).getPrefix();
        }
        attempt.setReturnUrlSnapshot(paymentUrls.paymentReturnUrl(order.getId().toString(), subsitePrefix));
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

    private void validateSupplierPaymentEnabled(Order order) {
        if (paymentEvents != null && paymentEvents.existsByOrderIdAndProcessResultStartingWith(order.getId(), "LATE_PAID_"))
            throw new BusinessException(ErrorCode.BAD_REQUEST, "订单存在待核实的付款，请联系客服");
        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        if (items.stream().anyMatch(item -> item.getStockMode() == StockMode.SUPPLIER)) {
            if (!supplierOrderAvailability.allowsPayment(order.getId(), items))
                throw new BusinessException(ErrorCode.BAD_REQUEST, "货源账号已停用或采购记录缺失，暂不能付款，请联系客服");
            if (paymentEvents==null || paymentEvents.existsByOrderIdAndProcessResultStartingWith(order.getId(),"LATE_PAID_"))
                throw new BusinessException(ErrorCode.BAD_REQUEST, "订单存在待核实的付款，请联系客服");
        }
    }

    private boolean allowsSupplierPayment(UUID orderId) {
        List<OrderItem> items=orderItemRepository.findByOrderId(orderId);
        return items.stream().noneMatch(item -> item.getStockMode()==StockMode.SUPPLIER)
                || supplierOrderAvailability.allowsPayment(orderId,items);
    }

    private PaymentAttempt findActiveAttempt(UUID orderId) {
        return paymentAttemptRepository
                .findFirstByOrderIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                        orderId, PaymentStatus.PENDING, LocalDateTime.now())
                .orElse(null);
    }

    private boolean creationUnresolved(PaymentAttempt attempt) {
        return attempt.getCreationStatus() == PaymentCreationStatus.INTENT
                || attempt.getCreationStatus() == PaymentCreationStatus.SUBMITTING
                || attempt.getCreationStatus() == PaymentCreationStatus.UNKNOWN;
    }

    private void closePendingAttempts(List<PaymentAttempt> attempts) {
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
        result.put("order_no", order.getOrderNo());
        result.put("status", attempt.getStatus());
        result.put("order_status", order.getStatus());
        result.put("creation_status", attempt.getCreationStatus());
        result.put("payment_method", OrderPaymentMethod.ONLINE.name());
        result.put("payment_type", attempt.getPaymentType());
        result.put("payment_channel_code", order.getPaymentChannelCode());
        boolean visible = order.getStatus() == OrderStatus.PENDING && attempt.getStatus() == PaymentStatus.PENDING
                && java.util.Objects.equals(order.getPaymentChannelCode(), attempt.getChannelCodeSnapshot())
                && order.getActualAmount() != null && attempt.getAmount() != null
                && order.getActualAmount().compareTo(attempt.getAmount()) == 0
                && (order.getExpiresAt() == null || order.getExpiresAt().isAfter(LocalDateTime.now()))
                && attempt.getCreationStatus() == PaymentCreationStatus.READY
                && attempt.getNextAction() != null && !attempt.getNextAction().isBlank()
                && (attempt.getExpiresAt() == null || attempt.getExpiresAt().isAfter(LocalDateTime.now()))
                && (paymentEvents == null || !paymentEvents.existsByOrderIdAndProcessResultStartingWith(order.getId(), "LATE_PAID_"))
                && allowsSupplierPayment(order.getId());
        result.put("next_action", visible ? PaymentNextActionPayload.from(deserializeNextAction(attempt.getNextAction())) : null);
        result.put("expires_at", attempt.getExpiresAt());
        result.put("remaining_seconds", remainingSeconds(attempt.getExpiresAt()));
        return result;
    }

    private long remainingSeconds(LocalDateTime expiresAt) {
        if (expiresAt == null) return 0;
        return Math.max(0, java.time.Duration.between(LocalDateTime.now(), expiresAt).getSeconds());
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
        if (order.getExpiresAt() != null && !order.getExpiresAt().isAfter(LocalDateTime.now())) {
            orderExpiryService.expireLockedOrder(order);
            throw new OrderExpiredException();
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

    private String clean(String value) {
        if (value == null) return null;
        String result = value.trim();
        return result.isEmpty() ? null : result;
    }

    private record PaymentContext(PaymentChannel channel, PaymentProvider provider) { }

}
