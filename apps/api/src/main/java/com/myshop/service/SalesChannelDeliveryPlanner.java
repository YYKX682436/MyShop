package com.myshop.service;

import com.myshop.constant.CardKeyStatus;
import com.myshop.constant.OrderStatus;
import com.myshop.constant.OrderType;
import com.myshop.constant.SalesChannelDeliveryOperationType;
import com.myshop.constant.SalesChannelOrderDeliveryStatus;
import com.myshop.entity.CardKey;
import com.myshop.entity.Order;
import com.myshop.entity.OrderItem;
import com.myshop.entity.SalesChannelDeliveryOperation;
import com.myshop.entity.SalesChannelOrder;
import com.myshop.repository.CardKeyRepository;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.SalesChannelDeliveryOperationRepository;
import com.myshop.repository.SalesChannelOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Creates one durable message unit per already-sold CardKey. */
@Service
@RequiredArgsConstructor
public class SalesChannelDeliveryPlanner {
    private final SalesChannelOrderRepository channelOrderRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CardKeyRepository cardKeyRepository;
    private final SalesChannelDeliveryOperationRepository operationRepository;

    @Transactional
    public void planPending() {
        List<SalesChannelOrder> rows = channelOrderRepository.findByDeliveryStatusIn(List.of(
                SalesChannelOrderDeliveryStatus.FULFILLMENT_PENDING,
                SalesChannelOrderDeliveryStatus.FULFILLED,
                SalesChannelOrderDeliveryStatus.MESSAGE_PENDING,
                SalesChannelOrderDeliveryStatus.MESSAGE_UNKNOWN));
        for (SalesChannelOrder row : rows) plan(row.getId());
    }

    @Transactional
    public void plan(UUID channelOrderId) {
        // Read the relation without a lock, then acquire the business Order
        // lock before the channel row. Terminal refund handling uses the same
        // Order -> CardKey -> channel-row order and must not deadlock with the
        // planner.
        SalesChannelOrder relation = channelOrderRepository.findById(channelOrderId).orElse(null);
        if (relation == null || relation.getOrderId() == null) return;
        Order order = orderRepository.findByIdForUpdate(relation.getOrderId()).orElse(null);
        if (order == null || order.getOrderType() != OrderType.SALES_CHANNEL || order.getStatus() != OrderStatus.DELIVERED) return;
        SalesChannelOrder row = channelOrderRepository.findByIdForUpdate(channelOrderId).orElse(null);
        if (row == null) return;
        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        Set<UUID> itemIds = new HashSet<>(items.stream().map(OrderItem::getId).toList());
        List<CardKey> keys = cardKeyRepository.findByOrderIdOrderByCreatedAtAsc(order.getId()).stream()
                .sorted(Comparator.comparing(CardKey::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(CardKey::getId))
                .toList();
        List<SalesChannelDeliveryOperation> existingOperations = operationRepository
                .findBySalesChannelOrderIdAndOperationTypeOrderByUnitIndexAsc(
                        row.getId(), SalesChannelDeliveryOperationType.SEND_MESSAGE);
        Map<UUID, Integer> unitByCardKey = new LinkedHashMap<>();
        Set<Integer> usedUnits = new HashSet<>();
        for (SalesChannelDeliveryOperation operation : existingOperations) {
            if (operation.getCardKeyId() != null && operation.getUnitIndex() != null) {
                unitByCardKey.put(operation.getCardKeyId(), operation.getUnitIndex());
                usedUnits.add(operation.getUnitIndex());
            }
        }
        int nextUnit = usedUnits.stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
        int validUnits = 0;
        for (CardKey key : keys) {
            if (key.getStatus() != CardKeyStatus.SOLD || key.getOrderId() == null
                    || !order.getId().equals(key.getOrderId()) || key.getOrderItemId() == null
                    || !itemIds.contains(key.getOrderItemId())) continue;
            Integer unit = unitByCardKey.get(key.getId());
            if (unit == null) {
                while (usedUnits.contains(nextUnit)) nextUnit++;
                unit = nextUnit++;
                unitByCardKey.put(key.getId(), unit);
                usedUnits.add(unit);
                SalesChannelDeliveryOperation operation = new SalesChannelDeliveryOperation();
                operation.setSalesChannelOrderId(row.getId());
                operation.setOperationType(SalesChannelDeliveryOperationType.SEND_MESSAGE);
                operation.setUnitIndex(unit);
                operation.setCardKeyId(key.getId());
                operation.setRequestId("XIANYU_DELIVERY:" + row.getId() + ":" + unit);
                operation.setPayloadFingerprint(digest(row.getId() + ":" + unit + ":" + key.getId()));
                operation.setStatus(com.myshop.constant.SalesChannelDeliveryOperationStatus.PENDING);
                operationRepository.save(operation);
            }
            validUnits++;
        }
        if (validUnits == 0) {
            row.setDeliveryStatus(SalesChannelOrderDeliveryStatus.REVIEW_REQUIRED);
            row.setLastErrorCode("DATA_INTEGRITY_ERROR");
            row.setLastErrorMessage("Delivered sales-channel order has no valid SOLD CardKey binding");
        } else {
            row.setDeliveryStatus(SalesChannelOrderDeliveryStatus.MESSAGE_PENDING);
        }
        channelOrderRepository.save(row);
    }

    private String digest(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }
}
