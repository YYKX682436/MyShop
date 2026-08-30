package com.myshop.service.impl;

import com.myshop.constant.CardKeyStatus;
import com.myshop.constant.ErrorCode;
import com.myshop.constant.FulfillmentStatus;
import com.myshop.constant.OrderStatus;
import com.myshop.constant.OrderType;
import com.myshop.constant.StockMode;
import com.myshop.entity.*;
import com.myshop.exception.BusinessException;
import com.myshop.repository.*;
import com.myshop.service.OrderService;
import com.myshop.service.AutoFulfillmentService;
import com.myshop.service.ManualStockService;
import com.myshop.service.PaymentService;
import com.myshop.service.ProductRules;
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
    private final WholesaleRuleRepository wholesaleRuleRepository;
    private final CardKeyRepository cardKeyRepository;
    private final SiteConfigRepository siteConfigRepository;
    private final PaymentChannelRepository paymentChannelRepository;
    private final PaymentService paymentService;
    private final ManualStockService manualStockService;
    private final UserRepository userRepository;
    private final AutoFulfillmentService autoFulfillmentService;

    @Autowired
    public OrderServiceImpl(OrderRepository orderRepository, OrderItemRepository orderItemRepository,
                            ProductRepository productRepository, ProductSpecRepository productSpecRepository,
                            WholesaleRuleRepository wholesaleRuleRepository,
                            CardKeyRepository cardKeyRepository, SiteConfigRepository siteConfigRepository,
                            PaymentChannelRepository paymentChannelRepository, PaymentService paymentService,
                            ManualStockService manualStockService,
                            UserRepository userRepository,
                            AutoFulfillmentService autoFulfillmentService) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.productRepository = productRepository;
        this.productSpecRepository = productSpecRepository;
        this.wholesaleRuleRepository = wholesaleRuleRepository;
        this.cardKeyRepository = cardKeyRepository;
        this.siteConfigRepository = siteConfigRepository;
        this.paymentChannelRepository = paymentChannelRepository;
        this.paymentService = paymentService;
        this.manualStockService = manualStockService;
        this.userRepository = userRepository;
        this.autoFulfillmentService = autoFulfillmentService;
    }

    /** Compatibility constructor for focused tests that exercise guest/normal orders only. */
    public OrderServiceImpl(OrderRepository orderRepository, OrderItemRepository orderItemRepository,
                            ProductRepository productRepository, ProductSpecRepository productSpecRepository,
                            WholesaleRuleRepository wholesaleRuleRepository,
                            CardKeyRepository cardKeyRepository, SiteConfigRepository siteConfigRepository,
                            PaymentChannelRepository paymentChannelRepository, PaymentService paymentService,
                            ManualStockService manualStockService) {
        this(orderRepository, orderItemRepository, productRepository, productSpecRepository,
                wholesaleRuleRepository, cardKeyRepository, siteConfigRepository,
                paymentChannelRepository, paymentService, manualStockService, null, null);
    }

    @Override
    @Transactional
    public Map<String, Object> createDirectOrder(Map<String, Object> req, UUID userId, String clientIp, String sessionToken) {
        String device = (String) req.get("device");
        User currentUser = reloadAuthenticatedUser(userId);
        boolean adminPickup = isAdminPickupRequested(req);
        if (adminPickup && !isEnabledAdministrator(currentUser)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有启用中的管理员可以管理员提卡");
        }
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
        String email = clean(asString(req.get("email")));
        if (adminPickup && (email == null || email.isBlank())) {
            email = currentUser.getEmail();
        }

        // F4: 购买数量校验（读取后台配置，兜底 999）
        int maxQuantity = getMaxPurchasePerUser();
        if (quantity < 1 || quantity > maxQuantity) {
            throw new BusinessException(ErrorCode.PURCHASE_LIMIT_EXCEEDED, "购买数量无效，允许范围 1~" + maxQuantity,
                    Map.of("max", maxQuantity));
        }

        // F14: 提前提取 email，用于 pending 订单限制（email + IP 双维度防刷）
        checkPendingOrderLimits(userId, clientIp, email);
        String paymentMethod = clean(asString(req.get("payment_method")));
        if (adminPickup) {
            paymentMethod = "ADMIN_PICKUP";
        } else {
            validatePaymentMethod(paymentMethod);
        }

        Product product = productRepository.findById(productId)
                .filter(p -> p.getIsDeleted() == 0 && p.isEnabled())
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "商品不存在或已下架"));

        // F18: 规格模式安全校验 — 防止通过伪造 spec_id 访问非当前模式的库存池或获取不同价格
        validateSpecConsistency(product, specId);

        List<com.myshop.model.CustomerInputValue> customerInputs =
                ProductRules.normalizeCustomerInputs(product, req);
        StockMode stockMode = product.getStockMode();
        StockView stock = getStock(product, specId);
        if (!stock.unlimited() && stock.available() < quantity) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK, "库存不足",
                    Map.of("available", stock.available()));
        }

        BigDecimal unitPrice = getUnitPrice(product, specId, quantity);
        BigDecimal totalAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));
        if (totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "订单金额异常，请联系客服");
        }
        reserveManualStock(product, specId, stockMode, quantity);
        int expireMinutes = getConfigInt("order_expire_minutes", 15);

        Order order = new Order();
        order.setUserId(userId);
        order.setEmail(email);
        order.setTotalAmount(totalAmount);
        order.setActualAmount(adminPickup ? BigDecimal.ZERO.setScale(2) : totalAmount);
        order.setOrderType(adminPickup ? OrderType.ADMIN_PICKUP : OrderType.NORMAL);
        order.setStatus(adminPickup ? OrderStatus.PAID : OrderStatus.PENDING);
        order.setPaymentMethod(paymentMethod);
        LocalDateTime now = LocalDateTime.now();
        order.setExpiresAt(adminPickup ? null : now.plusMinutes(expireMinutes));
        order.setPaidAt(adminPickup ? now : null);
        order.setIdempotencyKey(idempotencyKey);
        order.setIdempotencyFingerprint(idempotencyFingerprint);
        order.setClientIp(clientIp);
        order.setSessionToken(sessionToken);
        LookupTokens.Issued lookupToken = LookupTokens.issue();
        order.setLookupTokenHash(lookupToken.hash());
        if (userId == null) order.setGuestLookupToken(lookupToken.plaintext());
        orderRepository.save(order);

        String specName = null;
        if (specId != null) {
            ProductSpec spec = productSpecRepository.findById(specId).orElse(null);
            specName = spec != null ? spec.getName() : null;
        }

        OrderItem item = new OrderItem();
        item.setOrderId(order.getId());
        item.setProductId(productId);
        item.setSpecId(specId);
        item.setProductTitle(product.getTitle());
        item.setSpecName(specName);
        item.setQuantity(quantity);
        item.setUnitPrice(unitPrice);
        item.setSubtotal(totalAmount);
        setItemSnapshot(item, product, customerInputs, quantity, stockMode);
        orderItemRepository.save(item);

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
        result.put("status", order.getStatus().name());
        result.put("expires_at", order.getExpiresAt());
        // 返回服务端计算的剩余秒数，前端倒计时以此为准，不受客户端时钟影响
        if (order.getStatus() == OrderStatus.PENDING) {
            long remainingSeconds = Duration.between(LocalDateTime.now(), order.getExpiresAt()).getSeconds();
            result.put("remaining_seconds", Math.max(0, remainingSeconds));
        } else {
            result.put("remaining_seconds", 0);
        }
        if (order.getStatus() == OrderStatus.PENDING) {
            // 优先返回二维码 URL（PC），其次 H5 跳转链接（移动端）
            String effectiveUrl = order.getQrcodeUrl() != null ? order.getQrcodeUrl() : order.getPaymentUrl();
            if (effectiveUrl != null) {
                result.put("payment_url", effectiveUrl);
            }
        }
        return result;
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

    private BigDecimal getUnitPrice(Product product, UUID specId, int quantity) {
        BigDecimal basePrice = product.getBasePrice();
        if (specId != null) {
            // F1: 严格校验规格归属 — 防止用低价规格 ID 篡改高价商品的价格
            ProductSpec spec = productSpecRepository.findById(specId)
                    .filter(s -> s.getProductId().equals(product.getId()) && s.getIsDeleted() == 0)
                    .orElseThrow(() -> new BusinessException(ErrorCode.SPEC_NOT_FOUND, "商品规格不存在或与商品不匹配"));
            basePrice = spec.getPrice();
        }

        if (product.isWholesaleEnabled()) {
            List<WholesaleRule> rules;
            if (specId != null) {
                rules = wholesaleRuleRepository.findByProductIdAndSpecIdOrderByMinQuantityAsc(product.getId(), specId);
            } else {
                rules = wholesaleRuleRepository.findByProductIdAndSpecIdIsNullOrderByMinQuantityAsc(product.getId());
            }
            // Find matching tier (highest minQuantity <= quantity)
            for (int i = rules.size() - 1; i >= 0; i--) {
                if (quantity >= rules.get(i).getMinQuantity()) {
                    return rules.get(i).getUnitPrice();
                }
            }
        }
        return basePrice;
    }

    private void checkPendingOrderLimits(UUID userId, String clientIp, String email) {
        // 邮箱和 IP 共用同一配置值（面板"最大待支付订单数"）
        int maxPending = getConfigInt("max_pending_orders_per_user", 5);

        if (userId != null) {
            long pending = orderRepository.countByUserIdAndStatus(userId, OrderStatus.PENDING);
            if (pending >= maxPending) {
                throw new BusinessException(ErrorCode.UNPAID_ORDER_EXISTS, "您有未支付的订单，请先完成支付或等待过期");
            }
        }
        if (clientIp != null) {
            long pending = orderRepository.countByClientIpAndStatus(clientIp, OrderStatus.PENDING);
            if (pending >= maxPending) {
                throw new BusinessException(ErrorCode.UNPAID_ORDER_EXISTS, "您有未支付的订单，请先完成支付或等待过期");
            }
        }
        // F14: 邮箱维度 pending 订单限制 — 防止通过 IP 轮换绕过限制
        if (email != null && !email.isBlank()) {
            long pending = orderRepository.countByEmailAndStatus(email, OrderStatus.PENDING);
            if (pending >= maxPending) {
                throw new BusinessException(ErrorCode.UNPAID_ORDER_EXISTS, "该邮箱有未支付的订单，请先完成支付或等待过期");
            }
        }
    }

    private Map<String, Object> buildOrderResult(Order order, String device, String lookupToken) {
        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        Map<String, Object> orderDetail = toOrderDetail(order, items);
        Map<String, Object> payment;
        if (isAdminPickup(order)) {
            payment = new LinkedHashMap<>();
            payment.put("order_id", order.getId());
            payment.put("order_type", OrderType.ADMIN_PICKUP);
            payment.put("admin_pickup", true);
            payment.put("paid", true);
            payment.put("expires_at", null);
            triggerAdminPickupAfterCommit(order);
        } else {
            payment = paymentService.createPayment(
                    order.getId(), order.getPaymentMethod(), order.getActualAmount(), device);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order", orderDetail);
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
        map.put("total_amount", o.getTotalAmount());
        map.put("actual_amount", o.getActualAmount());
        map.put("status", o.getStatus().name());
        map.put("order_type", o.getOrderType());
        map.put("payment_method", o.getPaymentMethod());
        map.put("created_at", o.getCreatedAt());
        map.put("email", o.getEmail());
        map.put("expires_at", o.getExpiresAt());
        map.put("paid_at", o.getPaidAt());
        map.put("delivered_at", o.getDeliveredAt());
        map.put("items", items.stream().map(i -> {
            Map<String, Object> im = new LinkedHashMap<>();
            im.put("id", i.getId());
            im.put("product_id", i.getProductId());
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
    private void validateSpecConsistency(Product product, UUID specId) {
        if (product.isSpecEnabled()) {
            List<ProductSpec> activeSpecs = productSpecRepository
                    .findByProductIdAndIsDeletedOrderBySortOrderAsc(product.getId(), 0);
            if (!activeSpecs.isEmpty() && specId == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "该商品需要选择规格");
            }
        } else {
            if (specId != null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "该商品不支持规格选择");
            }
        }
    }

    /**
     * 支付渠道前置校验 — 在订单落库前确认渠道存在且启用，防止伪造不存在的支付方式绕过后续校验
     */
    private void validatePaymentMethod(String paymentMethod) {
        if (paymentMethod == null || paymentMethod.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "支付方式不能为空");
        }
        paymentChannelRepository.findByChannelCodeAndIsDeleted(paymentMethod, 0)
                .filter(PaymentChannel::isEnabled)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE, "支付渠道不可用"));
    }

    private boolean sameIdempotencyOwner(Order order, UUID userId, String sessionToken) {
        if (userId != null) return userId.equals(order.getUserId());
        return order.getUserId() == null
                && sessionToken != null
                && !sessionToken.isBlank()
                && sessionToken.equals(order.getSessionToken());
    }

    /** Always reloads an authenticated shopper from the database before using role-sensitive flags. */
    private User reloadAuthenticatedUser(UUID userId) {
        if (userId == null) return null;
        if (userRepository == null) {
            throw new IllegalStateException("Authenticated order creation requires the user repository");
        }
        return userRepository.findById(userId)
                .filter(user -> user.getIsDeleted() == 0)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "登录账号不存在或已被禁用"));
    }

    private boolean isAdminPickupRequested(Map<String, Object> request) {
        Object value = request.get("admin_pickup");
        if (value instanceof Boolean booleanValue) return booleanValue;
        return value instanceof String stringValue && Boolean.parseBoolean(stringValue);
    }

    private boolean isEnabledAdministrator(User user) {
        return user != null && user.getIsDeleted() == 0
                && (user.getRole() == com.myshop.constant.UserRole.ADMIN
                || user.getRole() == com.myshop.constant.UserRole.SUPER_ADMIN);
    }

    private boolean isAdminPickup(Order order) {
        return order.getOrderType() == OrderType.ADMIN_PICKUP
                || "ADMIN_PICKUP".equalsIgnoreCase(order.getPaymentMethod());
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
