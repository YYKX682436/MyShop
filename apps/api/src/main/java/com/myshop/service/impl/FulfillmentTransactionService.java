package com.myshop.service.impl;

import com.myshop.constant.CardKeyStatus;
import com.myshop.constant.ErrorCode;
import com.myshop.constant.FulfillmentStatus;
import com.myshop.constant.OrderStatus;
import com.myshop.constant.StockMode;
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
import com.myshop.service.EmailService;
import com.myshop.service.FulfillmentService;
import com.myshop.service.ManualStockService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The only service that allocates card keys or completes a manual item. It
 * owns the independent fulfillment transaction so a payment fact can remain
 * PAID when an automatic pool is temporarily short.
 */
@Service
public class FulfillmentTransactionService implements FulfillmentService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CardKeyRepository cardKeyRepository;
    private final UserRepository userRepository;
    private final PointsLogRepository pointsLogRepository;
    private final SiteConfigRepository siteConfigRepository;
    private final EmailService emailService;
    private final ManualStockService manualStockService;

    @Autowired
    public FulfillmentTransactionService(OrderRepository orderRepository,
                                         OrderItemRepository orderItemRepository,
                                         CardKeyRepository cardKeyRepository,
                                         UserRepository userRepository,
                                         PointsLogRepository pointsLogRepository,
                                         SiteConfigRepository siteConfigRepository,
                                         EmailService emailService,
                                         ManualStockService manualStockService) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.cardKeyRepository = cardKeyRepository;
        this.userRepository = userRepository;
        this.pointsLogRepository = pointsLogRepository;
        this.siteConfigRepository = siteConfigRepository;
        this.emailService = emailService;
        this.manualStockService = manualStockService;
    }

    /** Compatibility constructor retained for the existing focused tests. */
    public FulfillmentTransactionService(OrderRepository orderRepository,
                                         OrderItemRepository orderItemRepository,
                                         CardKeyRepository cardKeyRepository,
                                         UserRepository userRepository,
                                         PointsLogRepository pointsLogRepository,
                                         SiteConfigRepository siteConfigRepository,
                                         EmailService emailService) {
        this(orderRepository, orderItemRepository, cardKeyRepository, userRepository,
                pointsLogRepository, siteConfigRepository, emailService, new ManualStockService(null));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Map<String, Object> fulfillOrder(UUID orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));

        if (order.getStatus() == OrderStatus.DELIVERED) {
            return result(order, buildDeliveryGroups(order.getId()));
        }
        if (order.getStatus() != OrderStatus.PAID) {
            return result(order, List.of());
        }

        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        List<UUID> newlyCompletedAutoItems = new ArrayList<>();
        LocalDateTime deliveryAttemptAt = LocalDateTime.now();
        // This also repairs a pre-V5 mixed order whose automatic item was
        // already delivered while the old implementation withheld email
        // until the manual lane completed.
        for (OrderItem item : items) {
            if (!isManual(item)
                    && effectiveFulfillmentStatus(item) == FulfillmentStatus.DELIVERED
                    && item.getDeliveryNotifiedAt() == null) {
                item.setDeliveryNotifiedAt(deliveryAttemptAt);
                newlyCompletedAutoItems.add(item.getId());
            }
        }
        List<Allocation> allocations = new ArrayList<>();
        Map<StockPoolKey, Integer> autoQuantities = new LinkedHashMap<>();

        // Lock and collect every automatic allocation before changing any
        // key. A later shortage therefore rolls back the whole fulfillment
        // transaction and leaves earlier pools untouched.
        for (OrderItem item : items) {
            FulfillmentStatus status = effectiveFulfillmentStatus(item);
            if (status == FulfillmentStatus.DELIVERED || status == FulfillmentStatus.CANCELLED) {
                continue;
            }

            if (isManual(item)) {
                item.setFulfillmentStatus(FulfillmentStatus.WAITING_MANUAL);
                continue;
            }

            if (effectiveStockMode(item) != StockMode.CARD_KEY) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "自动发货订单的库存模式无效");
            }
            autoQuantities.merge(new StockPoolKey(item.getProductId(), item.getSpecId()),
                    item.getQuantity(), Integer::sum);
        }

        // Lock each product/spec pool once. A PostgreSQL transaction can see
        // rows it already locked with FOR UPDATE SKIP LOCKED, so taking keys
        // item-by-item while leaving them AVAILABLE would allocate the same
        // key twice when two cart items share a pool (for example, different
        // customer-input values). Aggregate first, then slice the
        // locked list back into the original order-item boundaries.
        Map<StockPoolKey, List<CardKey>> lockedKeysByPool = new LinkedHashMap<>();
        for (Map.Entry<StockPoolKey, Integer> entry : autoQuantities.entrySet()) {
            StockPoolKey pool = entry.getKey();
            List<CardKey> keys = cardKeyRepository.findAndLockAvailable(
                    pool.productId(), pool.specId(), entry.getValue());
            if (keys.size() != entry.getValue()) {
                throw new BusinessException(ErrorCode.ORDER_OUT_OF_STOCK, "缺货补货中，请联系客服");
            }
            lockedKeysByPool.put(pool, keys);
        }

        Map<StockPoolKey, Integer> poolOffsets = new LinkedHashMap<>();
        for (OrderItem item : items) {
            FulfillmentStatus status = effectiveFulfillmentStatus(item);
            if (status == FulfillmentStatus.DELIVERED || status == FulfillmentStatus.CANCELLED || isManual(item)) {
                continue;
            }
            StockPoolKey pool = new StockPoolKey(item.getProductId(), item.getSpecId());
            List<CardKey> poolKeys = lockedKeysByPool.get(pool);
            int start = poolOffsets.getOrDefault(pool, 0);
            int end = start + item.getQuantity();
            allocations.add(new Allocation(item, new ArrayList<>(poolKeys.subList(start, end))));
            poolOffsets.put(pool, end);
        }

        LocalDateTime deliveredAt = deliveryAttemptAt;
        for (Allocation allocation : allocations) {
            for (CardKey key : allocation.keys()) {
                key.setStatus(CardKeyStatus.SOLD);
                key.setOrderId(order.getId());
                key.setOrderItemId(allocation.item().getId());
                key.setSoldAt(deliveredAt);
            }
            cardKeyRepository.saveAll(allocation.keys());
            allocation.item().setFulfillmentStatus(FulfillmentStatus.DELIVERED);
            allocation.item().setDeliveredAt(deliveredAt);
            if (allocation.item().getDeliveryNotifiedAt() == null) {
                allocation.item().setDeliveryNotifiedAt(deliveredAt);
                newlyCompletedAutoItems.add(allocation.item().getId());
            }
        }
        for (OrderItem item : items) {
            if (isManual(item) && effectiveFulfillmentStatus(item) != FulfillmentStatus.DELIVERED) {
                item.setFulfillmentStatus(FulfillmentStatus.WAITING_MANUAL);
            }
            orderItemRepository.save(item);
        }

        boolean completed = allItemsDelivered(items);
        if (completed) {
            markOrderDelivered(order, deliveredAt);
        } else {
            // Mixed orders deliberately remain PAID until the manual lane is
            // completed. The retry scheduler only selects automatic items.
            orderRepository.save(order);
        }
        if (!newlyCompletedAutoItems.isEmpty()) {
            notifyAfterCommit(order, newlyCompletedAutoItems);
        }
        return result(order, buildDeliveryGroups(order.getId()));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Map<String, Object> completeManualItem(UUID orderId, UUID orderItemId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));
        OrderItem item = orderItemRepository.findById(orderItemId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "订单项不存在"));
        if (!orderId.equals(item.getOrderId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "订单项不属于当前订单");
        }
        if (!isManual(item)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "只有人工发货订单项可以手动完成");
        }
        if (effectiveFulfillmentStatus(item) == FulfillmentStatus.DELIVERED) {
            return result(order, buildDeliveryGroups(order.getId()));
        }
        if (effectiveFulfillmentStatus(item) == FulfillmentStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "已取消的订单项不能完成发货");
        }
        if (order.getStatus() != OrderStatus.PAID) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "只有已支付订单可以完成人工发货");
        }

        int reserved = item.getManualStockReserved();
        if (reserved > 0 && effectiveStockMode(item) == StockMode.MANUAL) {
            manualStockService.consumeReservation(item.getProductId(), item.getSpecId(), reserved);
            item.setManualStockReserved(0);
        }
        LocalDateTime deliveredAt = LocalDateTime.now();
        item.setFulfillmentStatus(FulfillmentStatus.DELIVERED);
        item.setDeliveredAt(deliveredAt);
        orderItemRepository.save(item);

        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        if (allItemsDelivered(items)) {
            markOrderDelivered(order, deliveredAt);
        } else {
            orderRepository.save(order);
        }
        return result(order, buildDeliveryGroups(order.getId()));
    }

    private void markOrderDelivered(Order order, LocalDateTime deliveredAt) {
        if (order.getStatus() == OrderStatus.DELIVERED) return;
        order.setStatus(OrderStatus.DELIVERED);
        order.setDeliveredAt(deliveredAt);
        orderRepository.save(order);
        awardPoints(order);
    }

    private void notifyAfterCommit(Order order, List<UUID> orderItemIds) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            UUID orderId = order.getId();
            String lookupToken = order.getGuestLookupToken();
            List<UUID> selectedItems = List.copyOf(orderItemIds);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    emailService.sendDeliveryEmail(orderId, lookupToken, selectedItems);
                }
            });
        } else {
            emailService.sendDeliveryEmail(order.getId(), order.getGuestLookupToken(), orderItemIds);
        }
    }

    private Map<String, Object> result(Order order, List<Map<String, Object>> groups) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order_id", order.getId());
        result.put("status", order.getStatus().name());
        result.put("groups", groups);
        result.put("item_statuses", orderItemRepository.findByOrderId(order.getId()).stream().map(item -> {
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("id", item.getId());
            status.put("product_title", item.getProductTitle());
            status.put("fulfillment_status", effectiveFulfillmentStatus(item));
            status.put("delivery_type", effectiveDeliveryType(item));
            return status;
        }).toList());
        return result;
    }

    private List<Map<String, Object>> buildDeliveryGroups(UUID orderId) {
        List<CardKey> keys = cardKeyRepository.findByOrderIdOrderByCreatedAtAsc(orderId);
        if (keys == null || keys.isEmpty()) {
            // Compatibility with old repository seams and old rows; the
            // production query above preserves deterministic card order.
            keys = cardKeyRepository.findByOrderId(orderId);
        }
        List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
        Map<UUID, List<CardKey>> byItem = new LinkedHashMap<>();
        for (CardKey key : keys) {
            if (key.getOrderItemId() != null) {
                byItem.computeIfAbsent(key.getOrderItemId(), ignored -> new ArrayList<>()).add(key);
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (OrderItem item : items) {
            List<CardKey> itemKeys = byItem.getOrDefault(item.getId(), List.of());
            if (itemKeys.isEmpty()) continue;
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("product_title", item.getProductTitle());
            group.put("spec_name", item.getSpecName());
            group.put("quantity", item.getQuantity());
            group.put("delivery_type", effectiveDeliveryType(item));
            group.put("fulfillment_status", effectiveFulfillmentStatus(item));
            List<Map<String, Object>> deliveryItems = itemKeys.stream().map(key -> {
                List<String> parts = key.resolvedContentParts();
                Map<String, Object> deliveryItem = new LinkedHashMap<>();
                deliveryItem.put("parts", parts);
                // Compatibility alias; the structured parts array is the
                // authoritative representation for new clients.
                deliveryItem.put("content_parts", parts);
                return deliveryItem;
            }).toList();
            group.put("delivery_items", deliveryItems);
            group.put("card_keys", itemKeys.stream().map(key -> {
                List<String> parts = key.resolvedContentParts();
                return parts.isEmpty() ? "" : parts.get(0);
            }).toList());
            result.add(group);
        }
        return result;
    }

    private boolean allItemsDelivered(List<OrderItem> items) {
        return !items.isEmpty() && items.stream()
                .allMatch(item -> effectiveFulfillmentStatus(item) == FulfillmentStatus.DELIVERED);
    }

    private boolean isManual(OrderItem item) {
        return "MANUAL".equalsIgnoreCase(effectiveDeliveryType(item));
    }

    private String effectiveDeliveryType(OrderItem item) {
        return item.getDeliveryType() == null || item.getDeliveryType().isBlank()
                ? "AUTO" : item.getDeliveryType().trim().toUpperCase();
    }

    private StockMode effectiveStockMode(OrderItem item) {
        return item.getStockMode() == null ? StockMode.CARD_KEY : item.getStockMode();
    }

    private FulfillmentStatus effectiveFulfillmentStatus(OrderItem item) {
        return item.getFulfillmentStatus() == null ? FulfillmentStatus.PENDING : item.getFulfillmentStatus();
    }

    private void awardPoints(Order order) {
        if (order.getUserId() == null) return;
        boolean enabled = siteConfigRepository.findByConfigKey("points_enabled")
                .map(config -> "true".equalsIgnoreCase(config.getConfigValue()))
                .orElse(false);
        int rate = siteConfigRepository.findByConfigKey("points_rate")
                .map(config -> {
                    try {
                        return Integer.parseInt(config.getConfigValue());
                    } catch (Exception ignored) {
                        return 0;
                    }
                }).orElse(0);
        if (!enabled || rate <= 0 || order.getActualAmount() == null) return;

        int earned = order.getActualAmount()
                .multiply(java.math.BigDecimal.valueOf(rate))
                .intValue();
        if (earned <= 0) return;

        User user = userRepository.findById(order.getUserId()).orElse(null);
        if (user == null) return;
        user.setPoints(user.getPoints() + earned);
        userRepository.save(user);

        PointsLog pointsLog = new PointsLog();
        pointsLog.setUserId(user.getId());
        pointsLog.setChangeAmount(earned);
        pointsLog.setBalanceAfter(user.getPoints());
        pointsLog.setReason("订单购买获得积分");
        pointsLog.setOrderId(order.getId());
        pointsLogRepository.save(pointsLog);
    }

    private record Allocation(OrderItem item, List<CardKey> keys) {
    }

    private record StockPoolKey(UUID productId, UUID specId) {
    }
}
