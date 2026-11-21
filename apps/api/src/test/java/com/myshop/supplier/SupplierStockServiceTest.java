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
    @Test void eachSpecUsesItsOwnSupplierFieldsAndUnboundSpecsHaveNoStock() throws Exception {
        var mappings=mock(SupplierProductMappingRepository.class);
        var suppliers=mock(SupplierService.class);
        var service=new SupplierStockService(mappings,suppliers,new SupplierRechargeFields());
        UUID product=UUID.randomUUID(),first=UUID.randomUUID(),second=UUID.randomUUID(),missing=UUID.randomUUID();
        UUID siteA=UUID.randomUUID(),siteB=UUID.randomUUID();
        var json=new ObjectMapper(); var a=mapping(siteA,product); var b=mapping(siteB,product);
        a.setSpecId(first); a.setSnapshot(json.readTree("{\"template\":\"a\"}"));
        b.setSpecId(second); b.setSnapshot(json.readTree("{\"template\":\"b\"}"));
        when(mappings.findLocal(product,first)).thenReturn(java.util.Optional.of(a));
        when(mappings.findLocal(product,second)).thenReturn(java.util.Optional.of(b));
        var fieldA=new com.myshop.model.CustomerInputField("a","账号 A");
        var fieldB=new com.myshop.model.CustomerInputField("b","账号 B");
        when(suppliers.customerFields(siteA,a.getSnapshot())).thenReturn(List.of(fieldA));
        when(suppliers.customerFields(siteB,b.getSnapshot())).thenReturn(List.of(fieldB));
        assertEquals(List.of(fieldA),service.fields(product,first)); assertEquals(List.of(fieldB),service.fields(product,second));
        assertTrue(service.fields(product,missing).isEmpty());
        when(mappings.findByProductIdIn(anyList())).thenReturn(List.of(a,b));
        var provider=mock(SupplierProvider.class);
        var runtime=new SupplierService.RuntimeAccount(true,provider,new SupplierModels.Connection("https://test.invalid","test","test"));
        when(suppliers.runtime(any(),eq(true))).thenReturn(runtime);
        when(provider.customerFields(any())).thenReturn(List.of());
        var available=service.available(List.of(product));
        assertEquals(5L,available.get(new PriceTarget(product,first)));
        assertEquals(5L,available.get(new PriceTarget(product,second)));
        assertFalse(available.containsKey(new PriceTarget(product,missing)));
    }
    private SupplierProductMapping mapping(UUID account,UUID product) {
        var m=new SupplierProductMapping(); m.setAccountId(account);m.setProductId(product);
        m.setGoodsType(1);m.setGoodsStatus(1);m.setSkuStatus(1);m.setMultiple(2);m.setStock(10);
        m.setMaxQuantity(20);m.setLastSyncedAt(LocalDateTime.now());return m;
    }
}
