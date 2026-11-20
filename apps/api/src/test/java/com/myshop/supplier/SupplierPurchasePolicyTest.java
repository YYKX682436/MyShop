package com.myshop.supplier;

import com.myshop.entity.SupplierProductMapping;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

class SupplierPurchasePolicyTest {
    private final SupplierPurchasePolicy policy=new SupplierPurchasePolicy();
    private SupplierProductMapping mapping() {
        var m=new SupplierProductMapping(); m.setGoodsStatus(1);m.setSkuStatus(1);m.setMultiple(3);
        m.setMinQuantity(1);m.setMaxQuantity(12);m.setStock(12);m.setCost(new BigDecimal("0.01234567"));
        m.setLastSyncedAt(LocalDateTime.now());return m;
    }
    @Test void multipleUsesActualQuantityAndPreciseTotalCost() {
        var result=policy.quote(mapping(),true,2,new BigDecimal("1.00"),LocalDateTime.now());
        assertEquals(6,result.actualQuantity()); assertEquals(new BigDecimal("0.07407402"),result.totalCost());
    }
    @Test void rejectsQuantityStockStaleAndNonProfitableOrders() {
        var m=mapping();
        assertThrows(SupplierException.class,()->policy.quote(m,true,5,BigDecimal.TEN,LocalDateTime.now()));
        m.setStock(5); assertThrows(SupplierException.class,()->policy.quote(m,true,2,BigDecimal.TEN,LocalDateTime.now()));
        m.setStock(12); m.setLastSyncedAt(LocalDateTime.now().minusMinutes(31));
        assertThrows(SupplierException.class,()->policy.quote(m,true,1,BigDecimal.TEN,LocalDateTime.now()));
        m.setLastSyncedAt(LocalDateTime.now());
        assertThrows(SupplierException.class,()->policy.quote(m,true,1,new BigDecimal("0.01"),LocalDateTime.now()));
        assertThrows(SupplierException.class,()->policy.quote(m,false,1,BigDecimal.TEN,LocalDateTime.now()));
    }
}
