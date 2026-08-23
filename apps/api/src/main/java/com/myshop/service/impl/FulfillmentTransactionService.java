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
import com.myshop.service.EmailService;
import com.myshop.service.FulfillmentService;
import lombok.RequiredArgsConstructor;
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
import java.util.stream.Collectors;

/**
 * The only service that changes a PAID order to DELIVERED and consumes card
 * keys. It deliberately has a separate transaction boundary from payment
 * confirmation so an inventory shortage can never roll back the PAID fact.
 */
@Service
@RequiredArgsConstructor
public class FulfillmentTransactionService implements FulfillmentService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CardKeyRepository cardKeyRepository;
    private final UserRepository userRepository;
    private final PointsLogRepository pointsLogRepository;
    private final SiteConfigRepository siteConfigRepository;
    private final EmailService emailService;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Map<String, Object> fulfillOrder(UUID orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));

        if (order.getStatus() == OrderStatus.DELIVERED) {
            return result(order, buildCardKeyGroups(order.getId()));
        }
        if (order.getStatus() != OrderStatus.PAID) {
            return result(order, List.of());
        }

        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        List<Allocation> allocations = new ArrayList<>();

        // Collect every allocation before mutating any card key. If a later
        // product/spec pool is short, the exception rolls this transaction back
        // and no earlier pool is consumed.
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

        // The transaction has to commit before an email can claim that the
        // keys are available. The plaintext token is passed only in memory to
        // the mail service and is never logged here.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    emailService.sendDeliveryEmail(order.getId(), order.getGuestLookupToken());
                }
            });
        } else {
            // Makes the class deterministic in narrow unit tests that invoke
            // the method without a Spring transaction proxy.
            emailService.sendDeliveryEmail(order.getId(), order.getGuestLookupToken());
        }

        return result(order, buildCardKeyGroups(order.getId()));
    }

    private Map<String, Object> result(Order order, List<Map<String, Object>> groups) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order_id", order.getId());
        result.put("status", order.getStatus().name());
        result.put("groups", groups);
        return result;
    }

    private List<Map<String, Object>> buildCardKeyGroups(UUID orderId) {
        List<CardKey> keys = cardKeyRepository.findByOrderId(orderId);
        List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
        Map<UUID, OrderItem> itemMap = items.stream()
                .collect(Collectors.toMap(OrderItem::getId, item -> item));
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
}
