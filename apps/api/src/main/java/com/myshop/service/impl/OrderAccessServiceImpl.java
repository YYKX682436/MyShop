package com.myshop.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.constant.ErrorCode;
import com.myshop.constant.FulfillmentStatus;
import com.myshop.constant.OrderPaymentMethod;
import com.myshop.constant.OrderStatus;
import com.myshop.entity.CardKey;
import com.myshop.entity.Order;
import com.myshop.entity.OrderItem;
import com.myshop.entity.PaymentAttempt;
import com.myshop.exception.BusinessException;
import com.myshop.payment.PaymentNextAction;
import com.myshop.payment.PaymentNextActionPayload;
import com.myshop.payment.PaymentStatus;
import com.myshop.repository.CardKeyRepository;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.PaymentAttemptRepository;
import com.myshop.service.BalanceOrderPaymentService;
import com.myshop.service.OrderAccessService;
import com.myshop.service.OrderService;
import com.myshop.service.PaymentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The single authorization boundary for customer order reads and mutations.
 * Authentication is checked before any item, payment, or card-key lookup.
 */
@Service
public class OrderAccessServiceImpl implements OrderAccessService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CardKeyRepository cardKeyRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final OrderService orderService;
    private final PaymentService paymentService;
    private final BalanceOrderPaymentService balanceOrderPaymentService;
    private final ObjectMapper objectMapper;

    public OrderAccessServiceImpl(OrderRepository orderRepository,
                                  OrderItemRepository orderItemRepository,
                                  CardKeyRepository cardKeyRepository,
                                  PaymentAttemptRepository paymentAttemptRepository,
                                  OrderService orderService,
                                  PaymentService paymentService,
                                  BalanceOrderPaymentService balanceOrderPaymentService,
                                  ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.cardKeyRepository = cardKeyRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.orderService = orderService;
        this.paymentService = paymentService;
        this.balanceOrderPaymentService = balanceOrderPaymentService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public Map<String, Object> getOrder(UUID orderId, UUID requestUserId, String sessionToken) {
        Order order = authorizedOrder(orderId, requestUserId, sessionToken);
        orderService.expireIfNeeded(order);
        return view(order);
    }

    @Override
    @Transactional
    public Map<String, Object> refreshOrder(UUID orderId, UUID requestUserId, String sessionToken) {
        Order order = authorizedOrder(orderId, requestUserId, sessionToken);
        orderService.expireIfNeeded(order);
        if (order.getStatus() == OrderStatus.PENDING) {
            paymentService.refreshPayment(order.getId());
            order = orderRepository.findByIdForUpdate(order.getId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));
        }
        return view(order);
    }

    @Override
    @Transactional
    public Map<String, Object> repayOrder(UUID orderId, String device,
                                          UUID requestUserId, String sessionToken) {
        // This authorization check intentionally precedes the payment service.
        authorizedOrder(orderId, requestUserId, sessionToken);
        return paymentService.repay(orderId, device);
    }

    @Override
    @Transactional
    public String exportCardKeys(UUID orderId, UUID requestUserId, String sessionToken) {
        Order order = authorizedOrder(orderId, requestUserId, sessionToken);
        List<CardKey> keys = cardKeyRepository.findByOrderIdOrderByCreatedAtAsc(order.getId());
        if (keys.isEmpty()) {
            throw new BusinessException(ErrorCode.ORDER_NOT_PAID, "订单尚未产生卡密");
        }

        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        Map<UUID, List<CardKey>> grouped = keys.stream()
                .filter(key -> key.getOrderItemId() != null)
                .collect(Collectors.groupingBy(CardKey::getOrderItemId,
                        LinkedHashMap::new, Collectors.toList()));
        StringBuilder result = new StringBuilder("MyShop 卡密\n");
        for (OrderItem item : items) {
            List<CardKey> itemKeys = grouped.getOrDefault(item.getId(), List.of());
            if (itemKeys.isEmpty()) continue;
            result.append("\n").append(item.getProductTitle());
            if (item.getSpecName() != null && !item.getSpecName().isBlank()) {
                result.append(" [").append(item.getSpecName()).append("]");
            }
            result.append("\n");
            for (CardKey key : itemKeys) {
                result.append(String.join("\t", key.getContentParts())).append("\n");
            }
        }
        return result.toString();
    }

    private Order authorizedOrder(UUID orderId, UUID requestUserId, String sessionToken) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));
        if (order.getUserId() != null) {
            if (requestUserId == null || !requestUserId.equals(order.getUserId())) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问此订单");
            }
            return order;
        }

        String provided = clean(sessionToken);
        String stored = clean(order.getSessionToken());
        if (provided == null || stored == null || !stored.equals(provided)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "游客会话无效");
        }
        return order;
    }

    private Map<String, Object> view(Order order) {
        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order", orderDetail(order, items));
        result.put("payment", paymentView(order));
        result.put("delivery", deliveryView(order, items));
        return result;
    }

    private Map<String, Object> orderDetail(Order order, List<OrderItem> items) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", order.getId());
        result.put("order_no", order.getOrderNo());
        result.put("total_amount", order.getTotalAmount());
        result.put("actual_amount", order.getActualAmount());
        result.put("status", order.getStatus().name());
        result.put("order_type", order.getOrderType());
        result.put("payment_method", order.getPaymentMethod() == null ? null : order.getPaymentMethod().name());
        result.put("payment_channel_code", order.getPaymentChannelCode());
        result.put("created_at", order.getCreatedAt());
        result.put("expires_at", order.getExpiresAt());
        result.put("paid_at", order.getPaidAt());
        result.put("delivered_at", order.getDeliveredAt());
        result.put("refunded_at", order.getRefundedAt());
        result.put("items", items.stream().map(this::itemDetail).toList());
        return result;
    }

    private Map<String, Object> itemDetail(OrderItem item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", item.getId());
        result.put("product_id", item.getProductId());
        result.put("product_no", item.getProductNo());
        result.put("product_title", item.getProductTitle());
        result.put("spec_name", item.getSpecName());
        result.put("quantity", item.getQuantity());
        result.put("unit_price", item.getUnitPrice());
        result.put("subtotal", item.getSubtotal());
        result.put("delivery_type", item.getDeliveryType());
        result.put("customer_inputs", item.getCustomerInputs() == null ? List.of() : item.getCustomerInputs());
        result.put("stock_mode", item.getStockMode());
        result.put("fulfillment_status", item.getFulfillmentStatus());
        result.put("delivered_at", item.getDeliveredAt());
        if (item.getFulfillmentStatus() == FulfillmentStatus.DELIVERED) {
            result.put("customer_delivery_content", item.getCustomerDeliveryContent());
        }
        return result;
    }

    private Map<String, Object> paymentView(Order order) {
        if (order.getPaymentMethod() == OrderPaymentMethod.BALANCE) {
            return balanceOrderPaymentService.buildPaymentResult(order);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order_id", order.getId());
        result.put("order_no", order.getOrderNo());
        result.put("status", paymentStatus(order));
        result.put("paid", order.getStatus() == OrderStatus.PAID
                || order.getStatus() == OrderStatus.DELIVERED
                || order.getStatus() == OrderStatus.REFUNDED);
        result.put("payment_method", order.getPaymentMethod() == null ? null : order.getPaymentMethod().name());
        result.put("payment_channel_code", order.getPaymentChannelCode());
        result.put("next_action", null);
        result.put("expires_at", order.getExpiresAt());
        result.put("remaining_seconds", remainingSeconds(order.getExpiresAt(), order.getStatus()));

        PaymentAttempt attempt = latestAttempt(order.getId());
        if (attempt != null) {
            result.put("payment_id", attempt.getId());
            result.put("payment_type", attempt.getPaymentType());
            if (order.getStatus() == OrderStatus.PENDING && attempt.getStatus() == PaymentStatus.PENDING
                    && attempt.getExpiresAt() != null && attempt.getExpiresAt().isAfter(LocalDateTime.now())) {
                result.put("expires_at", attempt.getExpiresAt());
                result.put("remaining_seconds", remainingSeconds(attempt.getExpiresAt(), order.getStatus()));
                result.put("next_action", PaymentNextActionPayload.from(readNextAction(attempt.getNextAction())));
            }
        } else {
            result.put("payment_type", null);
        }
        return result;
    }

    private PaymentStatus paymentStatus(Order order) {
        if (order.getStatus() == OrderStatus.EXPIRED) return PaymentStatus.CLOSED;
        if (order.getStatus() == OrderStatus.PAID || order.getStatus() == OrderStatus.DELIVERED
                || order.getStatus() == OrderStatus.REFUNDED) {
            return PaymentStatus.PAID;
        }
        return PaymentStatus.PENDING;
    }

    private PaymentAttempt latestAttempt(UUID orderId) {
        return paymentAttemptRepository.findByOrderIdOrderByCreatedAtDesc(orderId)
                .stream().findFirst().orElse(null);
    }

    private PaymentNextAction readNextAction(String raw) {
        try {
            if (raw == null || raw.isBlank()) throw new IllegalArgumentException();
            return objectMapper.readValue(raw, PaymentNextAction.class);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE, "支付动作数据无效");
        }
    }

    private long remainingSeconds(LocalDateTime expiresAt, OrderStatus status) {
        if (status != OrderStatus.PENDING || expiresAt == null) return 0;
        return Math.max(0, Duration.between(LocalDateTime.now(), expiresAt).getSeconds());
    }

    private Map<String, Object> deliveryView(Order order, List<OrderItem> items) {
        List<CardKey> keys = cardKeyRepository.findByOrderIdOrderByCreatedAtAsc(order.getId());
        Map<UUID, List<CardKey>> byItem = keys.stream()
                .filter(key -> key.getOrderItemId() != null)
                .collect(Collectors.groupingBy(CardKey::getOrderItemId,
                        LinkedHashMap::new, Collectors.toList()));
        List<Map<String, Object>> groups = new ArrayList<>();
        for (OrderItem item : items) {
            if ("MANUAL".equalsIgnoreCase(item.getDeliveryType())) {
                continue;
            }
            List<CardKey> itemKeys = byItem.getOrDefault(item.getId(), List.of());
            if (itemKeys.isEmpty()) continue;
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("product_no", item.getProductNo());
            group.put("product_title", item.getProductTitle());
            group.put("spec_name", item.getSpecName());
            group.put("quantity", item.getQuantity());
            group.put("delivery_type", item.getDeliveryType());
            group.put("fulfillment_status", item.getFulfillmentStatus());
            group.put("delivery_items", itemKeys.stream().map(key -> Map.of(
                    "content_parts", List.copyOf(key.getContentParts()))).toList());
            groups.add(group);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("groups", groups);
        result.put("item_statuses", items.stream().map(item -> {
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("id", item.getId());
            status.put("product_no", item.getProductNo());
            status.put("product_title", item.getProductTitle());
            status.put("fulfillment_status", item.getFulfillmentStatus());
            status.put("delivery_type", item.getDeliveryType());
            return status;
        }).toList());
        return result;
    }

    private String clean(String value) {
        if (value == null) return null;
        String result = value.trim();
        return result.isEmpty() ? null : result;
    }
}
