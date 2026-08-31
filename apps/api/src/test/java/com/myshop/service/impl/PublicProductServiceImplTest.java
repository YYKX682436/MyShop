package com.myshop.service.impl;

import com.myshop.constant.PricingSource;
import com.myshop.entity.Product;
import com.myshop.model.pricing.PriceResolution;
import com.myshop.model.pricing.PriceTarget;
import com.myshop.model.response.PublicProductResponse;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.ProductCategoryRepository;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.ProductSpecRepository;
import com.myshop.service.ProductInventoryBatchService;
import com.myshop.service.PricingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicProductServiceImplTest {

    @Mock private ProductRepository productRepository;
    @Mock private ProductSpecRepository productSpecRepository;
    @Mock private ProductCategoryRepository categoryRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private ProductInventoryBatchService inventoryBatchService;
    @Mock private PricingService pricingService;

    @Test
    void listOfOneHundredProductsUsesGroupedPricingInventorySalesAndCategoryReads() {
        List<Product> products = java.util.stream.IntStream.range(0, 100)
                .mapToObj(index -> product(index))
                .toList();
        PageRequest pageRequest = PageRequest.of(0, 100);
        when(productRepository.findPublicProducts(isNull(), any())).thenReturn(
                new PageImpl<>(products, pageRequest, products.size()));
        when(productSpecRepository.findByProductIdInAndIsDeletedAndVisibleOrderByProductIdAscSortOrderAsc(
                any(), eq(0), eq(true))).thenReturn(List.of());
        when(pricingService.resolvePublicLevel(null)).thenReturn(null);
        Map<PriceTarget, PriceResolution> prices = new HashMap<>();
        products.forEach(product -> prices.put(new PriceTarget(product.getId(), null),
                new PriceResolution(money("10.00"), money("5.00"), money("10.00"),
                        PricingSource.RETAIL, false, null, null, null)));
        when(pricingService.resolveBatch(anyCollection(), anyMap(), isNull())).thenReturn(prices);
        when(inventoryBatchService.load(anyCollection(), anyMap())).thenReturn(Map.of());
        when(orderItemRepository.sumQuantityByProductIds(any())).thenReturn(List.of());
        when(categoryRepository.findAllById(anyIterable())).thenReturn(List.of());

        var result = new PublicProductServiceImpl(productRepository, productSpecRepository,
                categoryRepository, orderItemRepository, inventoryBatchService, pricingService)
                .listProducts(null, null, 1, 100, null);

        assertEquals(100, result.getList().size());
        verify(productSpecRepository, times(1))
                .findByProductIdInAndIsDeletedAndVisibleOrderByProductIdAscSortOrderAsc(any(), eq(0), eq(true));
        verify(pricingService, times(1)).resolveBatch(anyCollection(), anyMap(), isNull());
        verify(inventoryBatchService, times(1)).load(anyCollection(), anyMap());
        verify(orderItemRepository, times(1)).sumQuantityByProductIds(any());
        verify(categoryRepository, times(1)).findAllById(anyIterable());
    }

    private Product product(int index) {
        Product product = new Product();
        product.setId(UUID.randomUUID());
        product.setTitle("Product " + index);
        product.setRetailPrice(money("10.00"));
        product.setCostPrice(money("5.00"));
        product.setCategoryId(UUID.randomUUID());
        product.setIsDeleted(0);
        product.setEnabled(true);
        return product;
    }

    private BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
