package com.myshop.supplier;

import com.myshop.constant.ErrorCode;
import com.myshop.constant.FulfillmentStatus;
import com.myshop.constant.StockMode;
import com.myshop.entity.OrderItem;
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
    @Transactional(propagation=Propagation.MANDATORY)
    public void validate(List<OrderItem> items) {
        for (OrderItem item:items) {
            if (item.getStockMode()!=StockMode.SUPPLIER) continue;
            var id=purchases.findIdByOrderItemId(item.getId()).orElseThrow(this::unsafe);
            var p=purchases.findForUpdate(id).orElseThrow(this::unsafe);
            if (!Objects.equals(p.getOrderId(),item.getOrderId()) || !"REFUNDED".equals(p.getStatus())
                    || !Objects.equals(p.getUpstreamStatus(),5) || p.getLastCheckedAt()==null
                    || p.getActualCost()==null || p.getActualCost().signum()<=0 || p.getRefundedAmount()==null
                    || p.getRefundedAmount().compareTo(p.getActualCost())!=0) throw unsafe();
            if (operations.findByPurchaseIdOrderByCreatedAtAsc(id).stream().anyMatch(o ->
                    Set.of("REFUND","CARD_VOID").contains(o.getKind())
                            && Set.of("INTENT","SUBMITTING","UNKNOWN","ACCEPTED").contains(o.getStatus()))) throw unsafe();
            var delivered=cards.findByPurchaseIdOrderByCreatedAtAsc(id);
            if (item.getFulfillmentStatus()==FulfillmentStatus.DELIVERED && p.getGoodsType()==1
                    && (delivered.size()!=p.getActualQuantity() || delivered.stream().anyMatch(c ->
                    c.getCardStatus()!=2 || c.getLastCheckedAt()==null))) throw unsafe();
        }
    }
    private BusinessException unsafe() {
        return new BusinessException(ErrorCode.ORDER_REFUND_NOT_ALLOWED,
                "请先逐项核实供应商全额退款和交付状态；部分退款、零元退款或结果未知需人工处理");
    }
}
