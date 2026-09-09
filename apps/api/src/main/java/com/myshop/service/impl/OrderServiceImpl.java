package com.myshop.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.constant.CardKeyStatus;
import com.myshop.constant.ErrorCode;
import com.myshop.constant.FulfillmentStatus;
import com.myshop.constant.OrderStatus;
import com.myshop.constant.OrderPaymentMethod;
import com.myshop.constant.OrderType;
import com.myshop.constant.PricingSource;
import com.myshop.constant.StockMode;
import com.myshop.constant.UserRole;
import com.myshop.entity.*;
import com.myshop.exception.BusinessException;
import com.myshop.model.pricing.PriceResolution;
import com.myshop.payment.PaymentNextAction;
import com.myshop.payment.PaymentNextActionPayload;
import com.myshop.payment.PaymentStatus;
import com.myshop.repository.*;
import com.myshop.service.OrderService;
import com.myshop.service.AutoFulfillmentService;
import com.myshop.service.BalanceOrderPaymentService;
import com.myshop.service.ManualStockService;
import com.myshop.service.PaymentService;
import com.myshop.service.ProductRules;
import com.myshop.service.PricingService;
import com.myshop.utils.LookupTokens;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductRepository productRepository;
    private final ProductSpecRepository productSpecRepository;
    private final CardKeyRepository cardKeyRepository;
    private final SiteConfigRepository siteConfigRepository;
    private final PaymentChannelRepository paymentChannelRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentService paymentService;
    private final BalanceOrderPaymentService balanceOrderPaymentService;
    private final ManualStockService manualStockService;
    private final UserRepository userRepository;
    private final UserLevelRepository userLevelRepository;
    private final AutoFulfillmentService autoFulfillmentService;
    private final PricingService pricingService;
    private final ObjectMapper objectMapper;

    @Autowired
    public OrderServiceImpl(OrderRepository orderRepository, OrderItemRepository orderItemRepository,
                             ProductRepository productRepository, ProductSpecRepository productSpecRepository,
                             CardKeyRepository cardKeyRepository, SiteConfigRepository siteConfigRepository,
                             PaymentChannelRepository paymentChannelRepository,
                             PaymentAttemptRepository paymentAttemptRepository, PaymentService paymentService,
                             BalanceOrderPaymentService balanceOrderPaymentService,
                             ManualStockService manualStockService,
                             UserRepository userRepository,
                             UserLevelRepository userLevelRepository,
                             AutoFulfillmentService autoFulfillmentService,
                             PricingService pricingService, ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.productRepository = productRepository;
        this.productSpecRepository = productSpecRepository;
        this.cardKeyRepository = cardKeyRepository;
        this.siteConfigRepository = siteConfigRepository;
        this.paymentChannelRepository = paymentChannelRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.paymentService = paymentService;
        this.balanceOrderPaymentService = balanceOrderPaymentService;
        this.manualStockService = manualStockService;
        this.userRepository = userRepository;
        this.userLevelRepository = userLevelRepository;
        this.autoFulfillmentService = autoFulfillmentService;
        this.pricingService = pricingService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public Map<String, Object> createDirectOrder(Map<String, Object> req, UUID userId, String clientIp, String sessionToken) {
        String device = (String) req.get("device");
        Object requestedPaymentMethod = req.get("payment_method");
        User currentUser = reloadAuthenticatedUser(userId, isBalancePaymentRequested(requestedPaymentMethod));
        boolean adminPickup = isEnabledAdministrator(currentUser);
        String idempotencyKey = clean((String) req.get("idempotency_key"));
        String idempotencyFingerprint = clean((String) req.get("idempotency_fingerprint"));
        if (idempotencyKey != null) {
            Optional<Order> existing = orderRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                Order existingOrder = existing.get();
                boolean sameOwner = sameIdempotencyOwner(existingOrder, userId, sessionToken);
                if (sameOwner) {
                    verifyIdempotencyFingerprint(existingOrder, idempotencyFingerprint);
                    return buildOrderResult(existingOrder, device, existingOrder.getGuestLookupToken());
                }
                throw new BusinessException(ErrorCode.FORBIDDEN, "幂等键不属于当前用户或会话");
            }
        }

        UUID productId = UUID.fromString((String) req.get("product_id"));
        UUID specId = req.get("spec_id") != null ? UUID.fromString((String) req.get("spec_id")) : null;
        int quantity = ((Number) req.get("quantity")).intValue();

        // F4: 购买数量校验（读取后台配置，兜底 999）
        int maxQuantity = getMaxPurchasePerUser();
        if (quantity < 1 || quantity > maxQuantity) {
            throw new BusinessException(ErrorCode.PURCHASE_LIMIT_EXCEEDED, "购买数量无效，允许范围 1~" + maxQuantity,
                    Map.of("max", maxQuantity));
        }

        OrderPaymentMethod paymentMethod = null;
        String paymentChannelCode = null;
        if (adminPickup) {
            // ADMIN_PICKUP is expressed only by order_type. Both payment
            // columns remain null regardless of client-supplied values.
        } else {
            paymentMethod = parsePaymentMethod(req.get("payment_method"));
            paymentChannelCode = clean(asString(req.get("payment_channel_code")));
            validatePaymentRequest(req, paymentMethod, paymentChannelCode);
        }

        Product product = productRepository.findById(productId)
                .filter(p -> p.getIsDeleted() == 0 && p.isEnabled())
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "商品不存在或已下架"));

        ProductSpec spec = resolveSellableSpec(product, specId);

        List<com.myshop.model.CustomerInputValue> customerInputs =
                ProductRules.normalizeCustomerInputs(product, req);

        UserLevel userLevel = null;
        if (!adminPickup && currentUser != null && currentUser.getRole() == UserRole.USER) {
            userLevel = pricingService.requireActiveLevel(currentUser);
        }
        PriceResolution price = pricingService.resolve(product, spec, userLevel);
        BigDecimal unitPrice = price.price();
        if (!adminPickup) {
            BigDecimal expectedUnitPrice = parseExpectedUnitPrice(req.get("expected_unit_price"));
            if (expectedUnitPrice == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "expected_unit_price 必填");
            }
            if (expectedUnitPrice.compareTo(unitPrice) != 0) {
                throw new BusinessException(ErrorCode.PRICE_CHANGED, "商品价格已变化，请刷新后重新确认",
                        Map.of("reason", "PRICE_CHANGED", "current_unit_price", unitPrice));
            }
        }

        StockMode stockMode = product.getStockMode();
        StockView stock = getStock(product, specId);
        if (!stock.unlimited() && stock.available() < quantity) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK, "库存不足",
                    Map.of("available", stock.available()));
        }
        BigDecimal totalAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));
        if (totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "订单金额异常，请联系客服");
        }
        reserveManualStock(product, specId, stockMode, quantity);
        int expireMinutes = getConfigInt("order_expire_minutes", 15);

        Order order = new Order();
        order.setUserId(userId);
        order.setTotalAmount(totalAmount);
        order.setActualAmount(adminPickup ? BigDecimal.ZERO.setScale(2) : totalAmount);
        order.setOrderType(adminPickup ? OrderType.ADMIN_PICKUP : OrderType.NORMAL);
        order.setStatus(adminPickup ? OrderStatus.PAID : OrderStatus.PENDING);
        order.setPaymentMethod(paymentMethod);
        order.setPaymentChannelCode(paymentChannelCode);
        LocalDateTime now = LocalDateTime.now();
        order.setExpiresAt(adminPickup ? null : now.plusMinutes(expireMinutes));
        order.setPaidAt(adminPickup ? now : null);
        order.setIdempotencyKey(idempotencyKey);
        order.setIdempotencyFingerprint(idempotencyFingerprint);
        order.setClientIp(clientIp);
        order.setSessionToken(sessionToken);
        setUserLevelSnapshot(order, userLevel, adminPickup);
        LookupTokens.Issued lookupToken = LookupTokens.issue();
        order.setLookupTokenHash(lookupToken.hash());
        if (userId == null) order.setGuestLookupToken(lookupToken.plaintext());
        order.setOrderNo(orderRepository.nextOrderNo());
        orderRepository.save(order);

        String specName = null;
        if (spec != null) specName = spec.getName();

        OrderItem item = new OrderItem();
        item.setOrderId(order.getId());
        item.setProductId(productId);
        item.setProductNo(product.getProductNo());
        item.setSpecId(specId);
        item.setProductTitle(product.getTitle());
        item.setSpecName(specName);
        item.setQuantity(quantity);
        item.setUnitPrice(unitPrice);
        item.setCostPriceSnapshot(price.costPrice());
        item.setRetailPriceSnapshot(price.retailPrice());
        item.setPricingSource(price.pricingSource());
        item.setSubtotal(totalAmount);
        setItemSnapshot(item, product, customerInputs, quantity, stockMode);
        orderItemRepository.save(item);

        if (!adminPickup && paymentMethod == OrderPaymentMethod.BALANCE) {
            balanceOrderPaymentService.pay(order, currentUser);
        }

        return buildOrderResult(order, device, lookupToken.plaintext());
    }

    @Override
    @Transactional
    public Map<String, Object> getOrderStatus(UUID orderId, String lookupToken, UUID requestUserId) {
        Order order = findOrderForStatus(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));
        authorize(order, lookupToken, requestUserId);
        // Auto expire check
        if (order.getStatus() == OrderStatus.PENDING && order.getExpiresAt() != null
                && order.getExpiresAt().isBefore(LocalDateTime.now())) {
            expireOrder(order);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order_id", order.getId());
        result.put("order_no", order.getOrderNo());
        result.put("status", order.getStatus().name());
        result.put("payment_method", order.getPaymentMethod() == null ? null : order.getPaymentMethod().name());
        result.put("payment_channel_code", order.getPaymentChannelCode());
        result.put("expires_at", order.getExpiresAt());
        // 返回服务端计算的剩余秒数，前端倒计时以此为准，不受客户端时钟影响
        if (order.getStatus() == OrderStatus.PENDING && order.getExpiresAt() != null) {
            long remainingSeconds = Duration.between(LocalDateTime.now(), order.getExpiresAt()).getSeconds();
            result.put("remaining_seconds", Math.max(0, remainingSeconds));
        } else {
            result.put("remaining_seconds", 0);
        }
        if (order.getStatus() == OrderStatus.PENDING) {
            paymentAttemptRepository
                    .findFirstByOrderIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                            order.getId(), PaymentStatus.PENDING, LocalDateTime.now())
                    .ifPresent(attempt -> {
                        result.put("payment_id", attempt.getId());
                        result.put("next_action", PaymentNextActionPayload.from(
                                readNextAction(attempt.getNextAction())));
                    });
        }
        return result;
    }

    private PaymentNextAction readNextAction(String raw) {
        try {
            if (raw == null || raw.isBlank()) throw new IllegalArgumentException();
            return objectMapper.readValue(raw, PaymentNextAction.class);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE, "支付动作数据无效");
        }
    }

    @Override
    @Transactional
    public Map<String, Object> refreshOrderStatus(UUID orderId, String lookupToken, UUID requestUserId) {
        // Refresh currently reuses the locked server-side status read; it does
        // not contact the payment provider.
        return getOrderStatus(orderId, lookupToken, requestUserId);
    }

    @Override
    @Scheduled(fixedRate = 60000)
    @Transactional
    public void expireOrders() {
        List<Order> expired = orderRepository.findExpiredOrders(LocalDateTime.now());
        for (Order order : expired) {
            Order locked = orderRepository.findByIdForUpdate(order.getId()).orElse(null);
            if (locked != null && locked.getStatus() == OrderStatus.PENDING
                    && locked.getExpiresAt() != null && locked.getExpiresAt().isBefore(LocalDateTime.now())) {
                expireOrder(locked);
            }
        }
    }

    private void setUserLevelSnapshot(Order order, UserLevel level, boolean adminPickup) {
        if (adminPickup || level == null) {
            return;
        }
        order.setUserLevelIdSnapshot(level.getId());
        order.setUserLevelNameSnapshot(level.getName());
    }

    private Map<String, Object> buildOrderResult(Order order, String device, String lookupToken) {
        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        Map<String, Object> payment;
        if (isAdminPickup(order)) {
            payment = new LinkedHashMap<>();
            payment.put("order_id", order.getId());
            payment.put("order_no", order.getOrderNo());
            payment.put("order_type", OrderType.ADMIN_PICKUP);
            payment.put("admin_pickup", true);
            payment.put("paid", true);
            payment.put("expires_at", null);
            triggerAdminPickupAfterCommit(order);
        } else if (order.getPaymentMethod() == OrderPaymentMethod.BALANCE) {
            payment = balanceOrderPaymentService.buildPaymentResult(order);
        } else {
            payment = paymentService.createPayment(
                    order.getId(), order.getPaymentChannelCode(), order.getActualAmount(), device);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        // BALANCE completion changes the managed order to PAID before the
        // response is built; serialize the order only after every payment
        // branch has reached its final synchronous state.
        result.put("order", toOrderDetail(order, items));
        result.put("payment", payment);
        if (lookupToken != null) result.put("lookup_token", lookupToken);
        return result;
    }

    private void authorize(Order order, String lookupToken, UUID requestUserId) {
        boolean owner = requestUserId != null && requestUserId.equals(order.getUserId());
        if (!owner && !LookupTokens.matches(lookupToken, order.getLookupTokenHash())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "订单查询凭证无效");
        }
    }

    private Map<String, Object> toOrderDetail(Order o, List<OrderItem> items) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", o.getId());
        map.put("order_no", o.getOrderNo());
        map.put("total_amount", o.getTotalAmount());
        map.put("actual_amount", o.getActualAmount());
        map.put("status", o.getStatus().name());
        map.put("order_type", o.getOrderType());
        map.put("payment_method", o.getPaymentMethod() == null ? null : o.getPaymentMethod().name());
        map.put("payment_channel_code", o.getPaymentChannelCode());
        map.put("created_at", o.getCreatedAt());
        map.put("expires_at", o.getExpiresAt());
        map.put("paid_at", o.getPaidAt());
        map.put("delivered_at", o.getDeliveredAt());
        map.put("items", items.stream().map(i -> {
            Map<String, Object> im = new LinkedHashMap<>();
            im.put("id", i.getId());
            im.put("product_id", i.getProductId());
            im.put("product_no", i.getProductNo());
            im.put("product_title", i.getProductTitle());
            im.put("spec_name", i.getSpecName());
            im.put("quantity", i.getQuantity());
            im.put("unit_price", i.getUnitPrice());
            im.put("subtotal", i.getSubtotal());
            im.put("delivery_type", i.getDeliveryType());
            im.put("customer_inputs", i.getCustomerInputs() == null ? List.of() : i.getCustomerInputs());
            im.put("stock_mode", i.getStockMode());
            im.put("fulfillment_status", i.getFulfillmentStatus());
            im.put("delivered_at", i.getDeliveredAt());
            return im;
        }).toList());
        return map;
    }

    /**
     * F18: 规格模式安全校验
     * - specEnabled=true 且有规格时，必须提供 spec_id（防止跨池分配卡密）
     * - specEnabled=false 时，不允许传 spec_id（防止绕过模式获取规格价格）
     */
    private ProductSpec resolveSellableSpec(Product product, UUID specId) {
        if (product.isSpecEnabled()) {
            if (specId == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "该商品需要选择规格");
            }
            return productSpecRepository.findById(specId)
                    .filter(spec -> product.getId().equals(spec.getProductId())
                            && spec.getIsDeleted() == 0 && spec.isVisible())
                    .orElseThrow(() -> new BusinessException(ErrorCode.SPEC_NOT_FOUND, "商品规格不存在、已隐藏或与商品不匹配"));
        } else {
            if (specId != null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "该商品不支持规格选择");
            }
            return null;
        }
    }

    private BigDecimal parseExpectedUnitPrice(Object raw) {
        if (raw == null) return null;
        String text = String.valueOf(raw).trim();
        if (text.isBlank()) return null;
        try {
            BigDecimal value = new BigDecimal(text);
            if (value.signum() <= 0 || value.scale() > 2) throw new NumberFormatException();
            return value.setScale(2);
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "expected_unit_price 无效");
        }
    }

    private OrderPaymentMethod parsePaymentMethod(Object raw) {
        String value = clean(asString(raw));
        if (value == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "支付方式不能为空");
        }
        try {
            return OrderPaymentMethod.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "支付方式只能是 BALANCE 或 ONLINE");
        }
    }

    /** Validates the normal-order method/channel protocol before reserving stock. */
    private void validatePaymentRequest(Map<String, Object> request,
                                        OrderPaymentMethod paymentMethod,
                                        String paymentChannelCode) {
        if (paymentMethod == OrderPaymentMethod.ONLINE) {
            if (paymentChannelCode == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "在线支付必须提供 payment_channel_code");
            }
            paymentChannelRepository.findByChannelCodeAndIsDeleted(paymentChannelCode, 0)
                    .filter(PaymentChannel::isEnabled)
                    .orElseThrow(() -> new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE, "支付渠道不可用"));
            return;
        }
        if (request.containsKey("payment_channel_code")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "余额支付不能提供 payment_channel_code");
        }
    }

    private boolean sameIdempotencyOwner(Order order, UUID userId, String sessionToken) {
        if (userId != null) return userId.equals(order.getUserId());
        return order.getUserId() == null
                && sessionToken != null
                && !sessionToken.isBlank()
                && sessionToken.equals(order.getSessionToken());
    }

    /**
     * Loads the authenticated shopper before any price or balance decision. A
     * balance order must acquire the user's row lock on this first read and
     * pass that same managed instance through pricing and payment.
     */
    private User reloadAuthenticatedUser(UUID userId, boolean lockForBalance) {
        if (userId == null) return null;
        if (userRepository == null) {
            throw new IllegalStateException("Authenticated order creation requires the user repository");
        }
        Optional<User> loadedUser = lockForBalance
                ? userRepository.findByIdForUpdate(userId)
                : userRepository.findById(userId);
        return loadedUser
                .filter(candidate -> candidate.getIsDeleted() == 0)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "登录账号不存在或已被禁用"));
    }

    private boolean isBalancePaymentRequested(Object rawPaymentMethod) {
        return rawPaymentMethod != null
                && "BALANCE".equalsIgnoreCase(String.valueOf(rawPaymentMethod).trim());
    }

    private boolean isEnabledAdministrator(User user) {
        return user != null && user.getIsDeleted() == 0
                && (user.getRole() == com.myshop.constant.UserRole.ADMIN
                || user.getRole() == com.myshop.constant.UserRole.SUPER_ADMIN);
    }

    private boolean isAdminPickup(Order order) {
        return order.getOrderType() == OrderType.ADMIN_PICKUP;
    }

    private void triggerAdminPickupAfterCommit(Order order) {
        if (autoFulfillmentService == null) {
            throw new IllegalStateException("Administrator pickup fulfillment service is not configured");
        }
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

    private void verifyIdempotencyFingerprint(Order order, String fingerprint) {
        if (order.getIdempotencyFingerprint() != null
                && !Objects.equals(order.getIdempotencyFingerprint(), fingerprint)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "幂等键对应的订单内容已改变，请重新提交");
        }
    }

    private String clean(String value) {
        if (value == null) return null;
        String result = value.trim();
        return result.isEmpty() ? null : result;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 每用户单次最大购买数量，后台可配，兜底 999（配置异常或 ≤ 0 时回退） */
    private int getMaxPurchasePerUser() {
        int val = getConfigInt("max_purchase_per_user", 999);
        return (val > 0 && val <= 999) ? val : 999;
    }

    private int getConfigInt(String key, int defaultValue) {
        return siteConfigRepository.findByConfigKey(key)
                .map(c -> {
                    try { return Integer.parseInt(c.getConfigValue()); }
                    catch (Exception e) { return defaultValue; }
                }).orElse(defaultValue);
    }

    private Optional<Order> findOrderForStatus(UUID orderId) {
        return orderRepository.findByIdForUpdate(orderId);
    }

    private StockView getStock(Product product, UUID specId) {
        StockMode stockMode = product.getStockMode();
        if (stockMode == StockMode.UNLIMITED) return new StockView(Long.MAX_VALUE, true);
        if (stockMode == StockMode.MANUAL) {
            return new StockView(manualStockService.getAvailable(product.getId(), specId), false);
        }
        long available = specId != null
                ? cardKeyRepository.countByProductIdAndSpecIdAndStatus(product.getId(), specId, CardKeyStatus.AVAILABLE)
                : cardKeyRepository.countByProductIdAndSpecIdIsNullAndStatus(product.getId(), CardKeyStatus.AVAILABLE);
        return new StockView(available, false);
    }

    private void reserveManualStock(Product product, UUID specId, StockMode stockMode, int quantity) {
        if (stockMode == StockMode.MANUAL) {
            manualStockService.reserveOrThrow(product.getId(), specId, quantity);
        }
    }

    private void setItemSnapshot(OrderItem item, Product product,
                                 List<com.myshop.model.CustomerInputValue> customerInputs,
                                 int quantity, StockMode stockMode) {
        item.setDeliveryType(product.getDeliveryType());
        item.setCustomerInputs(customerInputs == null ? List.of() : List.copyOf(customerInputs));
        item.setStockMode(stockMode);
        item.setFulfillmentStatus(FulfillmentStatus.PENDING);
        item.setManualStockReserved(stockMode == StockMode.MANUAL ? quantity : 0);
    }

    private void expireOrder(Order order) {
        for (PaymentAttempt attempt : paymentAttemptRepository
                .findByOrderIdAndStatusForUpdate(order.getId(), PaymentStatus.PENDING)) {
            attempt.setStatus(PaymentStatus.CLOSED);
            paymentAttemptRepository.save(attempt);
        }
        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        for (OrderItem item : items) {
            int reserved = item.getManualStockReserved();
            if (reserved > 0 && item.getStockMode() == StockMode.MANUAL) {
                manualStockService.release(item.getProductId(), item.getSpecId(), reserved);
                item.setManualStockReserved(0);
            }
            if (item.getFulfillmentStatus() != FulfillmentStatus.DELIVERED) {
                item.setFulfillmentStatus(FulfillmentStatus.CANCELLED);
            }
            orderItemRepository.save(item);
        }
        order.setStatus(OrderStatus.EXPIRED);
        orderRepository.save(order);
    }

    private record StockView(long available, boolean unlimited) {
    }

}
