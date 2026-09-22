package com.myshop.service.impl;

import com.myshop.constant.CardKeyStatus;
import com.myshop.constant.ErrorCode;
import com.myshop.constant.FulfillmentStatus;
import com.myshop.constant.OrderStatus;
import com.myshop.constant.OrderType;
import com.myshop.constant.StockMode;
import com.myshop.entity.CardKey;
import com.myshop.entity.Order;
import com.myshop.entity.OrderItem;
import com.myshop.exception.BusinessException;
import com.myshop.repository.CardKeyRepository;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.model.request.AdminManualFulfillRequest;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final EmailService emailService;
    private final ManualStockService manualStockService;
    private final ManualFulfillmentPreparationService manualFulfillmentPreparationService;

    @Autowired
    public FulfillmentTransactionService(OrderRepository orderRepository,
                                         OrderItemRepository orderItemRepository,
                                         CardKeyRepository cardKeyRepository,
                                         EmailService emailService,
                                         ManualStockService manualStockService,
                                         ManualFulfillmentPreparationService manualFulfillmentPreparationService) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.cardKeyRepository = cardKeyRepository;
        this.emailService = emailService;
        this.manualStockService = manualStockService;
        this.manualFulfillmentPreparationService = manualFulfillmentPreparationService;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Map<String, Object> fulfillOrder(UUID orderId) {
        // Persist the manual lane before attempting automatic allocation. If
        // a later card-pool shortage rolls back this transaction, the paid
        // manual items still remain visibly WAITING_MANUAL.
        manualFulfillmentPreparationService.markWaitingManual(orderId);

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
        for (OrderItem item : items) {
            if (!isManual(item)
                    && item.getFulfillmentStatus() == FulfillmentStatus.DELIVERED
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
            FulfillmentStatus status = item.getFulfillmentStatus();
            if (status == FulfillmentStatus.DELIVERED || status == FulfillmentStatus.CANCELLED) {
                continue;
            }

            if (isManual(item)) {
                item.setFulfillmentStatus(FulfillmentStatus.WAITING_MANUAL);
                continue;
            }

            if (item.getStockMode() != StockMode.CARD_KEY) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "自动发货订单的库存模式无效");
            }
            autoQuantities.merge(new StockPoolKey(item.getProductId(), item.getSpecId()),
                    item.getQuantity(), Integer::sum);
        }

        // Lock each product/spec pool once. A PostgreSQL transaction can see
        // rows it already locked with FOR UPDATE SKIP LOCKED, so taking keys
        // item-by-item while leaving them AVAILABLE would allocate the same
        // key twice when two order items share a pool (for example, different
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
            FulfillmentStatus status = item.getFulfillmentStatus();
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
            if (isManual(item) && item.getFulfillmentStatus() != FulfillmentStatus.DELIVERED) {
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
    public Map<String, Object> completeManualItems(
            UUID orderId, List<FulfillmentService.ManualFulfillmentItem> requestedItems) {
        validateManualRequest(requestedItems);

        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));
        if (order.getStatus() != OrderStatus.PAID) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "只有已支付订单可以完成人工发货");
        }

        List<OrderItem> currentItems = orderItemRepository.findByOrderId(order.getId());
        Map<UUID, OrderItem> itemsById = new LinkedHashMap<>();
        for (OrderItem item : currentItems) {
            itemsById.put(item.getId(), item);
        }

        Set<UUID> waitingManualIds = new HashSet<>();
        for (OrderItem item : currentItems) {
            if (isManual(item) && item.getFulfillmentStatus() == FulfillmentStatus.WAITING_MANUAL) {
                waitingManualIds.add(item.getId());
            }
        }
        if (waitingManualIds.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "当前订单没有待人工发货订单项");
        }

        Set<UUID> requestedIds = new HashSet<>();
        for (FulfillmentService.ManualFulfillmentItem requested : requestedItems) {
            OrderItem item = itemsById.get(requested.orderItemId());
            if (item == null) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "订单项不属于当前订单");
            }
            if (!isManual(item)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "只有人工发货订单项可以手动完成");
            }
            if (item.getStockMode() == StockMode.CARD_KEY) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "人工发货订单项的库存模式无效");
            }
            if (item.getFulfillmentStatus() != FulfillmentStatus.WAITING_MANUAL) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "订单项不是待人工发货状态，不能重复完成");
            }
            requestedIds.add(requested.orderItemId());
        }
        if (!requestedIds.equals(waitingManualIds)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "请求必须包含当前订单全部待人工发货订单项，不能漏发");
        }

        LocalDateTime deliveredAt = LocalDateTime.now();
        List<UUID> newlyCompletedManualItems = new ArrayList<>();
        for (FulfillmentService.ManualFulfillmentItem requested : requestedItems) {
            OrderItem item = itemsById.get(requested.orderItemId());
            int reserved = item.getManualStockReserved();
            if (reserved > 0 && item.getStockMode() == StockMode.MANUAL) {
                manualStockService.consumeReservation(item.getProductId(), item.getSpecId(), reserved);
                item.setManualStockReserved(0);
            }
            item.setCustomerDeliveryContent(normalizeCustomerContent(
                    requested.customerDeliveryContent()));
            item.setFulfillmentStatus(FulfillmentStatus.DELIVERED);
            item.setDeliveredAt(deliveredAt);
            if (item.getDeliveryNotifiedAt() == null) {
                item.setDeliveryNotifiedAt(deliveredAt);
                newlyCompletedManualItems.add(item.getId());
            }
            orderItemRepository.save(item);
        }

        if (allItemsDelivered(currentItems)) {
            markOrderDelivered(order, deliveredAt);
        } else {
            orderRepository.save(order);
        }
        if (!newlyCompletedManualItems.isEmpty()) {
            notifyAfterCommit(order, newlyCompletedManualItems);
        }
        return result(order, buildDeliveryGroups(order.getId()));
    }

    private void validateManualRequest(List<FulfillmentService.ManualFulfillmentItem> requestedItems) {
        if (requestedItems == null || requestedItems.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "至少选择一个待人工发货订单项");
        }
        Set<UUID> ids = new HashSet<>();
        for (FulfillmentService.ManualFulfillmentItem requested : requestedItems) {
            if (requested == null || requested.orderItemId() == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "订单项不能为空");
            }
            if (!ids.add(requested.orderItemId())) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "不能重复提交同一订单项");
            }
            String content = requested.customerDeliveryContent();
            if (content != null && content.length() > AdminManualFulfillRequest.MAX_CUSTOMER_CONTENT_LENGTH) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "发货内容不能超过 20000 个字符");
            }
        }
    }

    private String normalizeCustomerContent(String content) {
        if (content == null) return null;
        String normalized = content.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private void markOrderDelivered(Order order, LocalDateTime deliveredAt) {
        if (order.getStatus() == OrderStatus.DELIVERED) return;
        order.setStatus(OrderStatus.DELIVERED);
        order.setDeliveredAt(deliveredAt);
        orderRepository.save(order);
    }

    private void notifyAfterCommit(Order order, List<UUID> orderItemIds) {
        // Sales-channel delivery is sent by the provider delivery worker;
        // only normal storefront orders receive the MyShop email.
        if (order.getOrderType() != OrderType.NORMAL) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            UUID orderId = order.getId();
            List<UUID> selectedItems = List.copyOf(orderItemIds);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    emailService.sendDeliveryEmail(orderId, selectedItems);
                }
            });
        } else {
            emailService.sendDeliveryEmail(order.getId(), orderItemIds);
        }
    }

    private Map<String, Object> result(Order order, List<Map<String, Object>> groups) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order_id", order.getId());
        result.put("order_no", order.getOrderNo());
        result.put("status", order.getStatus().name());
        result.put("groups", groups);
        result.put("item_statuses", orderItemRepository.findByOrderId(order.getId()).stream().map(item -> {
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

    private List<Map<String, Object>> buildDeliveryGroups(UUID orderId) {
        List<CardKey> keys = cardKeyRepository.findByOrderIdOrderByCreatedAtAsc(orderId);
        List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
        Map<UUID, List<CardKey>> byItem = new LinkedHashMap<>();
        for (CardKey key : keys) {
            if (key.getOrderItemId() != null) {
                byItem.computeIfAbsent(key.getOrderItemId(), ignored -> new ArrayList<>()).add(key);
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (OrderItem item : items) {
            if (isManual(item)) {
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
            List<Map<String, Object>> deliveryItems = itemKeys.stream().map(key -> {
                Map<String, Object> deliveryItem = new LinkedHashMap<>();
                deliveryItem.put("content_parts", List.copyOf(key.getContentParts()));
                return deliveryItem;
            }).toList();
            group.put("delivery_items", deliveryItems);
            result.add(group);
        }
        return result;
    }

    private boolean allItemsDelivered(List<OrderItem> items) {
        return !items.isEmpty() && items.stream()
                .allMatch(item -> item.getFulfillmentStatus() == FulfillmentStatus.DELIVERED);
    }

    private boolean isManual(OrderItem item) {
        return "MANUAL".equalsIgnoreCase(item.getDeliveryType());
    }

    private record Allocation(OrderItem item, List<CardKey> keys) {
    }

    private record StockPoolKey(UUID productId, UUID specId) {
    }
}
