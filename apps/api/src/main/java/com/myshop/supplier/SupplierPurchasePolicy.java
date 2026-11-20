package com.myshop.supplier;

import com.myshop.entity.SupplierProductMapping;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Conservative checkout limits; a fresh provider detail is checked again after payment. */
@Component
public class SupplierPurchasePolicy {
    public Quote quote(SupplierProductMapping m,boolean enabled,int quantity,BigDecimal customerTotal,LocalDateTime now) {
        if (!enabled || m.getGoodsStatus()!=1 || m.getSkuStatus()!=1) throw error("SUPPLIER_DISABLED");
        if (m.getLastSyncedAt()==null || m.getLastSyncedAt().isBefore(now.minusMinutes(30))
                || m.getLastSyncedAt().isAfter(now.plusMinutes(1)) || m.getSyncError()!=null) throw error("STALE_STOCK");
        int actual;
        try { actual=Math.multiplyExact(quantity,m.getMultiple()); }
        catch (ArithmeticException e) { throw error("QUANTITY"); }
        if (quantity<1 || m.getMultiple()<1 || actual<1
                || (m.getMinQuantity()>0 && (quantity<m.getMinQuantity() || actual<m.getMinQuantity()))
                || (m.getMaxQuantity()>0 && (quantity>m.getMaxQuantity() || actual>m.getMaxQuantity()))) throw error("QUANTITY");
        if (m.getStock()<actual) throw error("STOCK");
        if (m.getCost()==null || m.getCost().signum()<0) throw error("COST");
        BigDecimal cost=m.getCost().multiply(BigDecimal.valueOf(actual));
        if (cost.precision()-cost.scale()>16 || customerTotal==null || customerTotal.compareTo(cost)<=0) throw error("NO_MARGIN");
        return new Quote(actual,cost);
    }
    private SupplierException error(String code) { return new SupplierException(code,"货源状态、库存、数量或价格不符合购买条件"); }
    public record Quote(int actualQuantity,BigDecimal totalCost) {}
}
