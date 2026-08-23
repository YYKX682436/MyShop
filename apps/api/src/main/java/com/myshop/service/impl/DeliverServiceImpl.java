package com.myshop.service.impl;

import com.myshop.constant.ErrorCode;
import com.myshop.constant.OrderStatus;
import com.myshop.entity.CardKey;
import com.myshop.entity.Order;
import com.myshop.entity.OrderItem;
import com.myshop.exception.BusinessException;
import com.myshop.repository.CardKeyRepository;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.PointsLogRepository;
import com.myshop.repository.SiteConfigRepository;
import com.myshop.repository.UserRepository;
import com.myshop.service.DeliverService;
import com.myshop.service.EmailService;
import com.myshop.service.FulfillmentService;
import com.myshop.utils.LookupTokens;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Public credential-gated facade. It never calls a public controller internally. */
@Service
public class DeliverServiceImpl implements DeliverService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CardKeyRepository cardKeyRepository;
    private final FulfillmentService fulfillmentService;

    @Autowired
    public DeliverServiceImpl(OrderRepository orderRepository,
                              OrderItemRepository orderItemRepository,
                              CardKeyRepository cardKeyRepository,
                              FulfillmentService fulfillmentService) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.cardKeyRepository = cardKeyRepository;
        this.fulfillmentService = fulfillmentService;
    }

    /** Compatibility constructor for the focused unit tests and older callers. */
    public DeliverServiceImpl(OrderRepository orderRepository,
                              OrderItemRepository orderItemRepository,
                              CardKeyRepository cardKeyRepository,
                              UserRepository userRepository,
                              PointsLogRepository pointsLogRepository,
                              SiteConfigRepository siteConfigRepository,
                              EmailService emailService) {
        this(orderRepository, orderItemRepository, cardKeyRepository,
                new FulfillmentTransactionService(orderRepository, orderItemRepository, cardKeyRepository,
                        userRepository, pointsLogRepository, siteConfigRepository, emailService));
    }

    @Override
    @Transactional
    public List<?> queryOrders(Map<String, Object> request, UUID requestUserId) {
        List<OrderCredential> credentials = parseCredentials(request);
        List<Map<String, Object>> result = new ArrayList<>();
        for (OrderCredential credential : credentials) {
            Order order = findForAuthorization(credential.orderId());
            authorize(order, credential.lookupToken(), requestUserId);
            expireIfNecessary(order);
            result.add(toBrief(order));
        }
        return result;
    }

    @Override
    public List<?> deliverOrders(Map<String, Object> request, UUID requestUserId) {
        List<OrderCredential> credentials = parseCredentials(request);
        List<Map<String, Object>> results = new ArrayList<>();
        for (OrderCredential credential : credentials) {
            Order order = findForAuthorization(credential.orderId());
            authorize(order, credential.lookupToken(), requestUserId);
            results.add(fulfillmentService.fulfillOrder(order.getId()));
        }
        return results;
    }

    @Override
    @Transactional(readOnly = true)
    public String exportCardKeys(UUID orderId, String lookupToken, UUID requestUserId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));
        authorize(order, lookupToken, requestUserId);
        if (order.getStatus() != OrderStatus.DELIVERED) {
            throw new BusinessException(ErrorCode.ORDER_NOT_PAID, "订单尚未发货");
        }

        List<CardKey> keys = cardKeyRepository.findByOrderId(orderId);
        List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
        Map<UUID, OrderItem> itemMap = items.stream()
                .collect(Collectors.toMap(OrderItem::getId, item -> item));
        StringBuilder result = new StringBuilder();
        result.append("订单号: ").append(orderId).append("\n");

        Map<UUID, List<CardKey>> grouped = keys.stream()
                .filter(key -> key.getOrderItemId() != null)
                .collect(Collectors.groupingBy(CardKey::getOrderItemId, LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<UUID, List<CardKey>> entry : grouped.entrySet()) {
            OrderItem item = itemMap.get(entry.getKey());
            if (item == null) continue;
            result.append("\n").append(item.getProductTitle());
            if (item.getSpecName() != null) result.append(" [").append(item.getSpecName()).append("]");
            result.append("\n");
            entry.getValue().forEach(key -> result.append(key.getContent()).append("\n"));
        }
        return result.toString();
    }

    private Order findForAuthorization(UUID orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order != null) return order;
        // Keeps the old narrow unit-test seam while the real fulfillment path
        // obtains its own lock in FulfillmentTransactionService.
        return orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));
    }

    private void authorize(Order order, String lookupToken, UUID requestUserId) {
        boolean owner = requestUserId != null && requestUserId.equals(order.getUserId());
        if (!owner && !LookupTokens.matches(lookupToken, order.getLookupTokenHash())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "订单查询凭证无效");
        }
    }

    private List<OrderCredential> parseCredentials(Map<String, Object> request) {
        Object rawCredentials = request.get("orders");
        if (rawCredentials instanceof List<?> list) {
            List<OrderCredential> credentials = new ArrayList<>();
            for (Object value : list) {
                if (!(value instanceof Map<?, ?> map)) continue;
                String id = value(map.get("order_id"));
                if (id != null) {
                    credentials.add(new OrderCredential(UUID.fromString(id), value(map.get("lookup_token"))));
                }
            }
            if (!credentials.isEmpty()) return credentials;
        }

        Object rawIds = request.get("order_ids");
        Object rawTokens = request.get("lookup_tokens");
        Map<String, String> tokenMap = rawTokens instanceof Map<?, ?> map
                ? map.entrySet().stream().collect(Collectors.toMap(
                        entry -> String.valueOf(entry.getKey()), entry -> value(entry.getValue()),
                        (first, ignored) -> first))
                : Map.of();
        List<OrderCredential> credentials = new ArrayList<>();
        if (rawIds instanceof List<?> ids) {
            for (Object rawId : ids) {
                String id = value(rawId);
                if (id != null) credentials.add(new OrderCredential(UUID.fromString(id), tokenMap.get(id)));
            }
        }
        return credentials;
    }

    private String value(Object value) {
        if (value == null) return null;
        String result = String.valueOf(value).trim();
        return result.isEmpty() ? null : result;
    }

    private void expireIfNecessary(Order order) {
        if (order.getStatus() == OrderStatus.PENDING && order.getExpiresAt() != null
                && order.getExpiresAt().isBefore(java.time.LocalDateTime.now())) {
            order.setStatus(OrderStatus.EXPIRED);
            orderRepository.save(order);
        }
    }

    private Map<String, Object> toBrief(Order order) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", order.getId());
        map.put("total_amount", order.getTotalAmount());
        map.put("actual_amount", order.getActualAmount());
        map.put("status", order.getStatus().name());
        map.put("order_type", order.getOrderType().name());
        map.put("payment_method", order.getPaymentMethod());
        map.put("created_at", order.getCreatedAt());
        return map;
    }

    private record OrderCredential(UUID orderId, String lookupToken) {
    }
}
