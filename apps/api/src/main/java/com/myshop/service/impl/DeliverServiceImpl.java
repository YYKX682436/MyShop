package com.myshop.service.impl;

import com.myshop.constant.CardKeyStatus;
import com.myshop.constant.ErrorCode;
import com.myshop.constant.OrderStatus;
import com.myshop.entity.CardKey;
import com.myshop.entity.Order;
import com.myshop.entity.OrderItem;
import com.myshop.entity.PointsLog;
import com.myshop.entity.User;
import com.myshop.exception.BusinessException;
import com.myshop.repository.CardKeyRepository;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.PointsLogRepository;
import com.myshop.repository.SiteConfigRepository;
import com.myshop.repository.UserRepository;
import com.myshop.service.DeliverService;
import com.myshop.service.EmailService;
import com.myshop.utils.LookupTokens;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeliverServiceImpl implements DeliverService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CardKeyRepository cardKeyRepository;
    private final UserRepository userRepository;
    private final PointsLogRepository pointsLogRepository;
    private final SiteConfigRepository siteConfigRepository;
    private final EmailService emailService;

    @Override
    @Transactional
    public List<?> queryOrders(Map<String, Object> request, UUID requestUserId) {
        List<OrderCredential> credentials = parseCredentials(request);
        if (credentials.isEmpty()) throw new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在");

        List<Map<String, Object>> result = new ArrayList<>();
        for (OrderCredential credential : credentials) {
            Order order = orderRepository.findById(credential.orderId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));
            authorize(order, credential.lookupToken(), requestUserId);
            expireIfNecessary(order);
            result.add(toBrief(order));
        }
        return result;
    }

    @Override
    @Transactional
    public List<?> deliverOrders(Map<String, Object> request, UUID requestUserId) {
        List<OrderCredential> credentials = parseCredentials(request);
        if (credentials.isEmpty()) throw new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在");
        List<Map<String, Object>> results = new ArrayList<>();
        for (OrderCredential credential : credentials) {
            results.add(deliverSingleOrder(credential, requestUserId));
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
            throw new BusinessException(ErrorCode.ORDER_NOT_PAID, "订单未发货");
        }

        List<CardKey> keys = cardKeyRepository.findByOrderId(orderId);
        List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
        Map<UUID, OrderItem> itemMap = items.stream().collect(Collectors.toMap(OrderItem::getId, item -> item));
        StringBuilder result = new StringBuilder();
        result.append("订单号: ").append(orderId).append("\n");
        result.append("=".repeat(40)).append("\n\n");
        Map<UUID, List<CardKey>> grouped = keys.stream()
                .filter(key -> key.getOrderItemId() != null)
                .collect(Collectors.groupingBy(CardKey::getOrderItemId, LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<UUID, List<CardKey>> entry : grouped.entrySet()) {
            OrderItem item = itemMap.get(entry.getKey());
            if (item == null) continue;
            result.append("商品: ").append(item.getProductTitle());
            if (item.getSpecName() != null) result.append(" [").append(item.getSpecName()).append("]");
            result.append("\n");
            entry.getValue().forEach(key -> result.append(key.getContent()).append("\n"));
            result.append("\n");
        }
        return result.toString();
    }

    private Map<String, Object> deliverSingleOrder(OrderCredential credential, UUID requestUserId) {
        // The order lock serializes concurrent delivery attempts for one order.
        Order order = orderRepository.findByIdForUpdate(credential.orderId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));
        authorize(order, credential.lookupToken(), requestUserId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order_id", order.getId());
        if (order.getStatus() == OrderStatus.DELIVERED) {
            result.put("status", "DELIVERED");
            result.put("groups", buildCardKeyGroups(order.getId()));
            return result;
        }
        if (order.getStatus() != OrderStatus.PAID) {
            result.put("status", order.getStatus().name());
            result.put("groups", List.of());
            return result;
        }

        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        if (items.isEmpty() || items.stream().anyMatch(item -> item.getQuantity() <= 0)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "订单商品数量异常，无法发货");
        }

        /*
         * P0 protocol: lock every pool, verify every quantity without mutation,
         * then mutate every key and mark the order delivered. A shortage throws
         * out of the transaction so all earlier locks/changes roll back.
         */
        List<Allocation> allocations = new ArrayList<>();
        for (OrderItem item : items) {
            List<CardKey> keys = cardKeyRepository.findAndLockAvailable(
                    item.getProductId(), item.getSpecId(), item.getQuantity());
            if (keys.size() != item.getQuantity()) {
                throw new BusinessException(ErrorCode.ORDER_OUT_OF_STOCK, "缺货补货中，请联系客服");
            }
            allocations.add(new Allocation(item, keys));
        }

        LocalDateTime deliveredAt = LocalDateTime.now();
        for (Allocation allocation : allocations) {
            for (CardKey key : allocation.keys()) {
                key.setStatus(CardKeyStatus.SOLD);
                key.setOrderId(order.getId());
                key.setOrderItemId(allocation.item().getId());
                key.setSoldAt(deliveredAt);
            }
            cardKeyRepository.saveAll(allocation.keys());
        }
        order.setStatus(OrderStatus.DELIVERED);
        order.setDeliveredAt(deliveredAt);
        orderRepository.save(order);
        awardPoints(order);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                emailService.sendDeliveryEmail(order.getId(), credential.lookupToken());
            }
        });
        result.put("status", "DELIVERED");
        result.put("groups", buildCardKeyGroups(order.getId()));
        return result;
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
            for (Object raw : list) {
                if (!(raw instanceof Map<?, ?> map)) continue;
                String id = value(map.get("order_id"));
                if (id != null) credentials.add(new OrderCredential(UUID.fromString(id), value(map.get("lookup_token"))));
            }
            return credentials;
        }

        Object rawIds = request.get("order_ids");
        if (!(rawIds instanceof List<?> ids)) return List.of();
        Object rawTokens = request.get("lookup_tokens");
        Map<String, String> tokenMap = rawTokens instanceof Map<?, ?> map
                ? map.entrySet().stream().collect(Collectors.toMap(entry -> String.valueOf(entry.getKey()), entry -> value(entry.getValue()), (a, b) -> b))
                : Map.of();
        List<OrderCredential> credentials = new ArrayList<>();
        for (Object rawId : ids) {
            String id = value(rawId);
            if (id != null) credentials.add(new OrderCredential(UUID.fromString(id), tokenMap.get(id)));
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
                && order.getExpiresAt().isBefore(LocalDateTime.now())) {
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

    private List<Map<String, Object>> buildCardKeyGroups(UUID orderId) {
        List<CardKey> keys = cardKeyRepository.findByOrderId(orderId);
        List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
        Map<UUID, OrderItem> itemMap = items.stream().collect(Collectors.toMap(OrderItem::getId, item -> item));
        Map<UUID, List<CardKey>> grouped = keys.stream()
                .filter(key -> key.getOrderItemId() != null)
                .collect(Collectors.groupingBy(CardKey::getOrderItemId, LinkedHashMap::new, Collectors.toList()));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<UUID, List<CardKey>> entry : grouped.entrySet()) {
            OrderItem item = itemMap.get(entry.getKey());
            if (item == null) continue;
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("product_title", item.getProductTitle());
            group.put("spec_name", item.getSpecName());
            group.put("card_keys", entry.getValue().stream().map(CardKey::getContent).toList());
            result.add(group);
        }
        return result;
    }

    private void awardPoints(Order order) {
        if (order.getUserId() == null) return;
        boolean enabled = siteConfigRepository.findByConfigKey("points_enabled")
                .map(config -> "true".equalsIgnoreCase(config.getConfigValue())).orElse(false);
        int rate = siteConfigRepository.findByConfigKey("points_rate").map(config -> {
            try { return Integer.parseInt(config.getConfigValue()); } catch (Exception ignored) { return 0; }
        }).orElse(0);
        if (!enabled || rate <= 0 || order.getActualAmount() == null) return;
        int earned = order.getActualAmount().multiply(java.math.BigDecimal.valueOf(rate))
                .setScale(0, java.math.RoundingMode.FLOOR).intValue();
        if (earned <= 0) return;
        User user = userRepository.findById(order.getUserId()).orElse(null);
        if (user == null) return;
        user.setPoints(user.getPoints() + earned);
        userRepository.save(user);
        PointsLog log = new PointsLog();
        log.setUserId(user.getId());
        log.setChangeAmount(earned);
        log.setBalanceAfter(user.getPoints());
        log.setReason("购物奖励积分");
        log.setOrderId(order.getId());
        pointsLogRepository.save(log);
    }

    private record OrderCredential(UUID orderId, String lookupToken) { }
    private record Allocation(OrderItem item, List<CardKey> keys) { }
}
