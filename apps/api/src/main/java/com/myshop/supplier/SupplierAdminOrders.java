package com.myshop.supplier;

import com.myshop.constant.ErrorCode;
import com.myshop.entity.SupplierPurchase;
import com.myshop.exception.BusinessException;
import com.myshop.repository.SupplierAccountRepository;
import com.myshop.repository.SupplierPurchaseRepository;
import com.myshop.repository.OperationLogRepository;
import com.myshop.service.OperationLogService;
import com.myshop.context.RequestContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SupplierAdminOrders {
    private final SupplierPurchaseRepository purchases;
    private final SupplierAccountRepository accounts;
    private final SupplierPurchaseWorker worker;
    private final OperationLogRepository logs;
    private final OperationLogService audit;
    private final SupplierAftercareService aftercare;

    public Map<String,Object> forItem(UUID itemId) {
        return purchases.findByOrderItemId(itemId).map(this::view).orElse(null);
    }
    public Map<String,Object> refresh(UUID orderId,UUID purchaseId) {
        SupplierPurchase p=purchases.findById(purchaseId)
                .filter(row -> row.getOrderId().equals(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND,"采购记录不存在"));
        if (p.getSubmitStartedAt()==null) throw new BusinessException(ErrorCode.BAD_REQUEST,"采购尚未发起，无需查询上游");
        worker.refresh(purchaseId);
        return view(purchases.findById(purchaseId).orElseThrow());
    }
    /** Fixed classifications record manual investigation without changing purchase state. */
    @Transactional
    public Map<String,Object> recordReview(UUID orderId,UUID purchaseId,String outcome) {
        var operator=RequestContext.get();
        if (operator==null || operator.getUserId()==null || operator.getRole()==null || !Set.of("ADMIN","SUPER_ADMIN").contains(operator.getRole()))
            throw new BusinessException(ErrorCode.FORBIDDEN,"仅管理员可以记录核实结果");
        SupplierPurchase p=purchases.findForUpdate(purchaseId)
                .filter(row -> row.getOrderId().equals(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND,"采购记录不存在"));
        if (!Set.of("UNKNOWN","SUBMITTING","BLOCKED").contains(p.getStatus()))
            throw new BusinessException(ErrorCode.BAD_REQUEST,"当前采购状态无需记录异常核实");
        if (outcome==null || !Set.of("CONTACTED_SUPPLIER","ORDER_NOT_FOUND_UNCONFIRMED","ORDER_FOUND_VERIFY_BY_QUERY","MANUAL_HANDLING").contains(outcome))
            throw new BusinessException(ErrorCode.BAD_REQUEST,"核实结果无效");
        audit.logCurrentRequest("supplier.purchase.review","SUPPLIER_PURCHASE",p.getId().toString(),outcome);
        return view(p);
    }
    private Map<String,Object> view(SupplierPurchase p) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("id",p.getId()); result.put("account_id",p.getAccountId());
        result.put("account_name",accounts.findById(p.getAccountId()).map(a -> a.getName()).orElse("—"));
        result.put("outer_number",p.getOuterNumber()); result.put("upstream_order_number",p.getUpstreamOrderNumber());
        result.put("status",p.getStatus()); result.put("upstream_status",p.getUpstreamStatus());
        result.put("buyer_quantity",p.getBuyerQuantity()); result.put("actual_quantity",p.getActualQuantity());
        result.put("safe_price",p.getSafePrice()); result.put("actual_cost",p.getActualCost());
        result.put("refunded_amount",p.getRefundedAmount()); result.put("last_checked_at",p.getLastCheckedAt());
        result.put("error_code",p.getErrorCode()); result.put("can_refresh",p.getSubmitStartedAt()!=null);
        result.put("aftercare_operations",aftercare.operations(p.getId()));
        result.put("supplier_cards",aftercare.cards(p.getId()));
        result.put("reviews",logs.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(
                "SUPPLIER_PURCHASE",p.getId().toString(),PageRequest.of(0,20)).getContent().stream()
                .filter(log -> "supplier.purchase.review".equals(log.getAction()))
                .map(log -> Map.of("outcome",log.getDetail(),"operator",log.getUsername(),"created_at",log.getCreatedAt())).toList());
        return result;
    }
}
