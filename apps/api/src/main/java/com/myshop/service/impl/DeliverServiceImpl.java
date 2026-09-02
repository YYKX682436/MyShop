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
import com.myshop.service.DeliverService;
import com.myshop.service.FulfillmentService;
import com.myshop.service.ManualStockService;
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
    private final ManualStockService manualStockService;

    @Autowired
    public DeliverServiceImpl(OrderRepository orderRepository,
                              OrderItemRepository orderItemRepository,
                              CardKeyRepository cardKeyRepository,
                              FulfillmentService fulfillmentService,
                              ManualStockService manualStockService) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.cardKeyRepository = cardKeyRepository;
        this.fulfillmentService = fulfillmentService;
        this.manualStockService = manualStockService;
    }

    @Override
    @Transactional
    public List<?> queryOrders(Map<String, Object> request, UUID requestUserId) {
        List<OrderCredential> credentials = parseCredentials(request);
        List<Map<String, Object>> result = new ArrayList<>();
        for (OrderCredential credential : credentials) {
            Order order = findForAuthorization(credential.orderId());
            authorize(order, credential.lookupToken(), requestUserId);
            order = expireIfNecessary(order);
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

        List<CardKey> keys = cardKeyRepository.findByOrderIdOrderByCreatedAtAsc(orderId);
        List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
        StringBuilder result = new StringBuilder();
        result.append("订单号: ").append(orderId).append("\n");

        Map<UUID, List<CardKey>> grouped = keys.stream()
                .filter(key -> key.getOrderItemId() != null)
                .collect(Collectors.groupingBy(CardKey::getOrderItemId, LinkedHashMap::new, Collectors.toList()));
        int itemNumber = 0;
        for (OrderItem item : items) {
            List<CardKey> itemKeys = grouped.getOrDefault(item.getId(), List.of());
            if (itemKeys.isEmpty()) continue;
            itemNumber++;
            result.append("\n第").append(itemNumber).append("项：").append(item.getProductTitle());
            if (item.getSpecName() != null) result.append(" [").append(item.getSpecName()).append("]");
            result.append("\n");
            for (int cardIndex = 0; cardIndex < itemKeys.size(); cardIndex++) {
                CardKey key = itemKeys.get(cardIndex);
                List<String> parts = key.getContentParts();
                if (parts.size() == 1) {
                    result.append(parts.get(0)).append("\n");
                    continue;
                }
                result.append("第 ").append(cardIndex + 1).append(" 份\n");
                for (int partIndex = 0; partIndex < parts.size(); partIndex++) {
                    result.append("卡券内容 ").append(partIndex + 1).append(": ")
                            .append(parts.get(partIndex)).append("\n");
                }
            }
        }
        return result.toString();
    }

    private Order findForAuthorization(UUID orderId) {
        return orderRepository.findById(orderId)
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

    private Order expireIfNecessary(Order order) {
        if (order.getStatus() == OrderStatus.PENDING && order.getExpiresAt() != null
                && order.getExpiresAt().isBefore(java.time.LocalDateTime.now())) {
            // Query endpoints can race payment/expiry schedulers. Re-read the
            // order under the same PostgreSQL row lock used by payment and the
            // scheduler so a reservation is released by at most one path.
            Order locked = orderRepository.findByIdForUpdate(order.getId()).orElse(order);
            if (locked.getStatus() != OrderStatus.PENDING || locked.getExpiresAt() == null
                    || !locked.getExpiresAt().isBefore(java.time.LocalDateTime.now())) {
                return locked;
            }
            orderItemRepository.findByOrderId(locked.getId()).forEach(item -> {
                if (item.getManualStockReserved() > 0) {
                    manualStockService.release(item.getProductId(), item.getSpecId(), item.getManualStockReserved());
                    item.setManualStockReserved(0);
                }
                item.setFulfillmentStatus(com.myshop.constant.FulfillmentStatus.CANCELLED);
                orderItemRepository.save(item);
            });
            locked.setStatus(OrderStatus.EXPIRED);
            orderRepository.save(locked);
            return locked;
        }
        return order;
    }

    private Map<String, Object> toBrief(Order order) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", order.getId());
        map.put("total_amount", order.getTotalAmount());
        map.put("actual_amount", order.getActualAmount());
        map.put("status", order.getStatus().name());
        map.put("order_type", order.getOrderType());
        map.put("payment_method", order.getPaymentMethod());
        map.put("created_at", order.getCreatedAt());
        map.put("items", orderItemRepository.findByOrderId(order.getId()).stream().map(item -> {
            Map<String, Object> itemMap = new LinkedHashMap<>();
            itemMap.put("id", item.getId());
            itemMap.put("product_id", item.getProductId());
            itemMap.put("product_code", item.getProductCode());
            itemMap.put("product_title", item.getProductTitle());
            itemMap.put("spec_name", item.getSpecName());
            itemMap.put("quantity", item.getQuantity());
            itemMap.put("delivery_type", item.getDeliveryType());
            itemMap.put("customer_inputs", item.getCustomerInputs() == null ? List.of() : item.getCustomerInputs());
            itemMap.put("stock_mode", item.getStockMode());
            itemMap.put("fulfillment_status", item.getFulfillmentStatus());
            itemMap.put("delivered_at", item.getDeliveredAt());
            return itemMap;
        }).toList());
        return map;
    }

    private record OrderCredential(UUID orderId, String lookupToken) {
    }
}
