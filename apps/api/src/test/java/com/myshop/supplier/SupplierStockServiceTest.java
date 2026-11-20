package com.myshop.supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.entity.SupplierProductMapping;
import com.myshop.model.pricing.PriceTarget;
import com.myshop.repository.SupplierProductMappingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SupplierStockServiceTest {
    @Test void malformedOneProductDoesNotHideHealthyProductsFromSameSite() throws Exception {
        var mappings=mock(SupplierProductMappingRepository.class);
        var suppliers=mock(SupplierService.class);
        var provider=mock(SupplierProvider.class);
        var service=new SupplierStockService(mappings,suppliers,new SupplierRechargeFields());
        ReflectionTestUtils.setField(service,"purchaseEnabled",true);
        UUID account=UUID.randomUUID(), broken=UUID.randomUUID(), good=UUID.randomUUID();
        var json=new ObjectMapper();
        var a=mapping(account,broken); a.setSnapshot(json.readTree("{\"broken\":true}"));
        var b=mapping(account,good); b.setSnapshot(json.readTree("{}"));
        when(mappings.findByProductIdIn(anyList())).thenReturn(List.of(a,b));
        when(suppliers.runtime(account,true)).thenReturn(new SupplierService.RuntimeAccount(true,provider,
                new SupplierModels.Connection("https://test.invalid","test","test")));
        when(provider.customerFields(a.getSnapshot())).thenThrow(new SupplierException("DATA","invalid"));
        when(provider.customerFields(b.getSnapshot())).thenReturn(List.of());
        var stock=service.available(List.of(broken,good));
        assertFalse(stock.containsKey(new PriceTarget(broken,null)));
        assertEquals(5L,stock.get(new PriceTarget(good,null)));
    }
    private SupplierProductMapping mapping(UUID account,UUID product) {
        var m=new SupplierProductMapping(); m.setAccountId(account);m.setProductId(product);
        m.setGoodsType(1);m.setGoodsStatus(1);m.setSkuStatus(1);m.setMultiple(2);m.setStock(10);
        m.setMaxQuantity(20);m.setLastSyncedAt(LocalDateTime.now());return m;
    }
}
