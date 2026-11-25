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
        var availability=SupplierStockRules.assess(m,now);
        if ("SYNC_FAILED".equals(availability.status())) throw error("SYNC_FAILED");
        if ("STALE".equals(availability.status())) throw error("STALE_STOCK");
        if ("ACCOUNT_UNAVAILABLE".equals(availability.status())) throw error("SUPPLIER_DISABLED");
        if ("INVALID_DATA".equals(availability.status()) || "LOCAL_UNAVAILABLE".equals(availability.status())) throw error("SUPPLIER_DISABLED");
        if ("UPSTREAM_UNAVAILABLE".equals(availability.status())) throw error("SUPPLIER_DISABLED");
        if ("INPUT_UNSUPPORTED".equals(availability.status())) throw error("SUPPLIER_DISABLED");
        int actual;
        try { actual=Math.multiplyExact(quantity,m.getMultiple()); }
        catch (ArithmeticException e) { throw error("QUANTITY"); }
        if (quantity<1 || m.getMultiple()<1 || actual<1
                || (availability.minQuantity()>0 && quantity<availability.minQuantity())
                || (availability.maxQuantity()>0 && quantity>availability.maxQuantity())) throw error("QUANTITY");
        if ("BELOW_MINIMUM".equals(availability.status())) throw error("QUANTITY");
        if ("OUT_OF_STOCK".equals(availability.status()) || "MULTIPLE_SHORTAGE".equals(availability.status())) throw error("STOCK");
        if (!"AVAILABLE".equals(availability.status()) || availability.quantity()==null) throw error("STALE_STOCK");
        if (quantity>availability.quantity()) throw error("STOCK");
        if (m.getStock()<actual) throw error("STOCK");
        if (m.getCost()==null || m.getCost().signum()<0) throw error("COST");
        BigDecimal cost=m.getCost().multiply(BigDecimal.valueOf(actual));
        if (cost.precision()-cost.scale()>16 || customerTotal==null || customerTotal.compareTo(cost)<=0) throw error("NO_MARGIN");
        return new Quote(actual,cost);
    }
    private SupplierException error(String code) { return new SupplierException(code,"货源状态、库存、数量或价格不符合购买条件"); }
    public record Quote(int actualQuantity,BigDecimal totalCost) {}
}
