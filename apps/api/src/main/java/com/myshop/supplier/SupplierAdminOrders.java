package com.myshop.supplier;

import com.myshop.constant.ErrorCode;
import com.myshop.entity.SupplierPurchase;
import com.myshop.exception.BusinessException;
import com.myshop.repository.SupplierAccountRepository;
import com.myshop.repository.SupplierPurchaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SupplierAdminOrders {
    private final SupplierPurchaseRepository purchases;
    private final SupplierAccountRepository accounts;
    private final SupplierPurchaseWorker worker;

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
        return result;
    }
}
