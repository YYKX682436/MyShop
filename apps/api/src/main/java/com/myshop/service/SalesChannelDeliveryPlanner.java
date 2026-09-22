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
        SalesChannelOrder row = channelOrderRepository.findByIdForUpdate(channelOrderId).orElse(null);
        if (row == null || row.getOrderId() == null) return;
        Order order = orderRepository.findByIdForUpdate(row.getOrderId()).orElse(null);
        if (order == null || order.getOrderType() != OrderType.SALES_CHANNEL || order.getStatus() != OrderStatus.DELIVERED) return;
        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        Set<UUID> itemIds = new HashSet<>(items.stream().map(OrderItem::getId).toList());
        List<CardKey> keys = cardKeyRepository.findByOrderIdOrderByCreatedAtAsc(order.getId());
        int unit = 0;
        for (CardKey key : keys) {
            if (key.getStatus() != CardKeyStatus.SOLD || key.getOrderId() == null
                    || !order.getId().equals(key.getOrderId()) || key.getOrderItemId() == null
                    || !itemIds.contains(key.getOrderItemId())) continue;
            if (!operationRepository.existsBySalesChannelOrderIdAndOperationTypeAndUnitIndex(
                    row.getId(), SalesChannelDeliveryOperationType.SEND_MESSAGE, unit)) {
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
            unit++;
        }
        if (unit == 0) {
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
