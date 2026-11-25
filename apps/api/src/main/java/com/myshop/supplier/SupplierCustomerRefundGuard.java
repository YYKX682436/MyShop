package com.myshop.supplier;

import com.myshop.constant.ErrorCode;
import com.myshop.constant.FulfillmentStatus;
import com.myshop.constant.OrderStatus;
import com.myshop.constant.OrderType;
import com.myshop.constant.StockMode;
import com.myshop.entity.Order;
import com.myshop.entity.OrderItem;
import com.myshop.entity.SupplierAftercareOperation;
import com.myshop.entity.SupplierDeliveryCard;
import com.myshop.entity.SupplierPurchase;
import com.myshop.exception.BusinessException;
import com.myshop.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Runs under the existing order and user locks, without calling a supplier. */
@Service @RequiredArgsConstructor
public class SupplierCustomerRefundGuard {
    private final SupplierPurchaseRepository purchases;
    private final SupplierDeliveryCardRepository cards;
    private final SupplierAftercareOperationRepository operations;
    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final WebhookEventRepository paymentEvents;

    @Transactional(propagation=Propagation.MANDATORY)
    public void validate(List<OrderItem> items) {
        for (OrderItem item:items) {
            if (item.getStockMode()!=StockMode.SUPPLIER) continue;
            var id=purchases.findIdByOrderItemId(item.getId()).orElseThrow(this::unsafe);
            var p=purchases.findForUpdate(id).orElseThrow(this::unsafe);
            Order order=orders.findById(item.getOrderId()).orElseThrow(this::unsafe);
            List<SupplierDeliveryCard> delivered=cards.findByPurchaseIdOrderByCreatedAtAsc(id);
            var pending=operations.findByPurchaseIdOrderByCreatedAtAsc(id);
            if (!Objects.equals(p.getOrderId(),item.getOrderId())) throw unsafe();
            if (neverSubmitted(order,item,p,delivered,pending)) {
                p.setStatus("WITHDRAWN"); p.setErrorCode("CUSTOMER_REFUND"); p.setLastCheckedAt(java.time.LocalDateTime.now());
                continue;
            }
            if (!upstreamRefunded(item,p,delivered,pending)) throw unsafe();
        }
    }

    @Transactional(readOnly=true)
    public boolean canRefund(SupplierPurchase purchase) {
        try {
            Order order=orders.findById(purchase.getOrderId()).orElse(null);
            OrderItem item=items.findById(purchase.getOrderItemId()).orElse(null);
            if (order==null || item==null || !Objects.equals(order.getId(),item.getOrderId())) return false;
            var delivered=cards.findByPurchaseIdOrderByCreatedAtAsc(purchase.getId());
            var pending=operations.findByPurchaseIdOrderByCreatedAtAsc(purchase.getId());
            return neverSubmitted(order,item,purchase,delivered,pending)
                    || upstreamRefunded(item,purchase,delivered,pending);
        } catch (RuntimeException ignored) { return false; }
    }

    private boolean neverSubmitted(Order order,OrderItem item,SupplierPurchase p,
                                   List<SupplierDeliveryCard> delivered,List<SupplierAftercareOperation> pending) {
        return order.getUserId()!=null && order.getOrderType()==OrderType.NORMAL && !order.isRiskFlagged()
                && Set.of(OrderStatus.PAID,OrderStatus.DELIVERED,OrderStatus.AFTER_SALE).contains(order.getStatus())
                && Set.of("WAITING_PAYMENT","BLOCKED").contains(p.getStatus())
                && !"LATE_PAYMENT".equals(p.getErrorCode()) && !"ORDER_STATE".equals(p.getErrorCode())
                && p.getSubmitStartedAt()==null && p.getUpstreamOrderNumber()==null
                && item.getFulfillmentStatus()!=FulfillmentStatus.DELIVERED && delivered.isEmpty() && pending.isEmpty()
                && !paymentEvents.existsByOrderIdAndProcessResultStartingWith(order.getId(),"LATE_PAID_");
    }

    private boolean upstreamRefunded(OrderItem item,SupplierPurchase p,List<SupplierDeliveryCard> delivered,
                                     List<SupplierAftercareOperation> pending) {
        if (!"REFUNDED".equals(p.getStatus()) || !Objects.equals(p.getUpstreamStatus(),5) || p.getLastCheckedAt()==null
                || p.getActualCost()==null || p.getActualCost().signum()<=0 || p.getRefundedAmount()==null
                || p.getRefundedAmount().compareTo(p.getActualCost())!=0) return false;
        if (pending.stream().anyMatch(o -> Set.of("REFUND","CARD_VOID").contains(o.getKind())
                && Set.of("INTENT","SUBMITTING","UNKNOWN","ACCEPTED").contains(o.getStatus()))) return false;
        return item.getFulfillmentStatus()!=FulfillmentStatus.DELIVERED || p.getGoodsType()!=1
                || (delivered.size()==p.getActualQuantity() && delivered.stream().noneMatch(c ->
                c.getCardStatus()!=2 || c.getLastCheckedAt()==null));
    }
    private BusinessException unsafe() {
        return new BusinessException(ErrorCode.ORDER_REFUND_NOT_ALLOWED,
                "请先逐项核实供应商全额退款和交付状态；部分退款、零元退款或结果未知需人工处理");
    }
}
