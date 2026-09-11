package com.myshop.service.impl;

import com.myshop.constant.PricingSource;
import com.myshop.entity.Product;
import com.myshop.entity.ProductCategory;
import com.myshop.entity.ProductSpec;
import com.myshop.entity.UserLevel;
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
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
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
        when(categoryRepository.findByIsDeletedOrderBySortOrderAscIdAsc(0)).thenReturn(List.of());

        var result = new PublicProductServiceImpl(productRepository, productSpecRepository,
                categoryRepository, orderItemRepository, inventoryBatchService, pricingService)
                .listProducts(null, null, 1, 100, null);

        assertEquals(100, result.getList().size());
        var pageableCaptor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(productRepository).findPublicProducts(isNull(), pageableCaptor.capture());
        assertEquals(List.of("sortWeight", "createdAt", "id"),
                pageableCaptor.getValue().getSort().stream().map(order -> order.getProperty()).toList());
        assertTrue(pageableCaptor.getValue().getSort().stream()
                .filter(order -> !"id".equals(order.getProperty()))
                .allMatch(order -> order.isDescending()));
        assertTrue(pageableCaptor.getValue().getSort().getOrderFor("id").isAscending());
        verify(productSpecRepository, times(1))
                .findByProductIdInAndIsDeletedAndVisibleOrderByProductIdAscSortOrderAsc(any(), eq(0), eq(true));
        verify(pricingService, times(1)).resolveBatch(anyCollection(), anyMap(), isNull());
        verify(inventoryBatchService, times(1)).load(anyCollection(), anyMap());
        verify(orderItemRepository, times(1)).sumQuantityByProductIds(any());
        verify(categoryRepository, times(1)).findByIsDeletedOrderBySortOrderAscIdAsc(0);
    }

    @Test
    void publicSpecPricesAndRetailStartingPriceUseOnlyVisibleActiveSpecs() {
        Product product = product(0);
        product.setRetailPrice(money("1.00"));
        product.setSpecEnabled(true);
        ProductSpec visibleHundred = spec(product, "Visible 100", "80.00", "100.00");
        ProductSpec visibleTwoHundred = spec(product, "Visible 200", "150.00", "200.00");
        when(productRepository.findById(product.getId())).thenReturn(java.util.Optional.of(product));
        when(productSpecRepository.findByProductIdInAndIsDeletedAndVisibleOrderByProductIdAscSortOrderAsc(
                any(), eq(0), eq(true))).thenReturn(List.of(visibleHundred, visibleTwoHundred));
        when(pricingService.resolvePublicLevel(null)).thenReturn(null);
        Map<PriceTarget, PriceResolution> prices = Map.of(
                new PriceTarget(product.getId(), visibleHundred.getId()),
                new PriceResolution(money("90.00"), money("80.00"), money("100.00"), PricingSource.LEVEL_MARKUP,
                        false, money("90.00"), null, null),
                new PriceTarget(product.getId(), visibleTwoHundred.getId()),
                new PriceResolution(money("150.00"), money("150.00"), money("200.00"), PricingSource.RETAIL,
                        false, null, null, null));
        when(pricingService.resolveBatch(anyCollection(), anyMap(), isNull())).thenReturn(prices);
        when(pricingService.resolve(any(Product.class), isNull(ProductSpec.class), isNull(UserLevel.class)))
                .thenReturn(new PriceResolution(money("1.00"), money("0.00"), money("1.00"), PricingSource.RETAIL,
                        false, null, null, null));
        when(inventoryBatchService.load(anyCollection(), anyMap())).thenReturn(Map.of());
        when(orderItemRepository.sumQuantityByProductIds(any())).thenReturn(List.of());
        when(categoryRepository.findByIsDeletedOrderBySortOrderAscIdAsc(0)).thenReturn(List.of());

        PublicProductResponse response = new PublicProductServiceImpl(productRepository, productSpecRepository,
                categoryRepository, orderItemRepository, inventoryBatchService, pricingService)
                .getProduct(product.getId(), null);

        assertEquals(money("90.00"), response.price());
        assertEquals(money("100.00"), response.retailPrice());
        assertEquals(List.of("Visible 100", "Visible 200"),
                response.specs().stream().map(com.myshop.model.response.PublicProductSpecResponse::name).toList());
        assertIterableEquals(List.of(visibleHundred.getId(), visibleTwoHundred.getId()),
                response.specs().stream().map(com.myshop.model.response.PublicProductSpecResponse::id).toList());
        verify(productSpecRepository).findByProductIdInAndIsDeletedAndVisibleOrderByProductIdAscSortOrderAsc(
                any(), eq(0), eq(true));
    }

    @Test
    void productResponseExposesDerivedPrimaryAndSecondaryCategoryPath() {
        Product product = product(0);
        ProductCategory root = category(UUID.randomUUID(), "Software", null);
        ProductCategory child = category(UUID.randomUUID(), "AI tools", root.getId());
        product.setCategoryId(child.getId());
        when(productRepository.findById(product.getId())).thenReturn(java.util.Optional.of(product));
        when(productSpecRepository.findByProductIdInAndIsDeletedAndVisibleOrderByProductIdAscSortOrderAsc(
                any(), eq(0), eq(true))).thenReturn(List.of());
        when(pricingService.resolvePublicLevel(null)).thenReturn(null);
        when(pricingService.resolveBatch(anyCollection(), anyMap(), isNull())).thenReturn(Map.of(
                new PriceTarget(product.getId(), null),
                new PriceResolution(money("10.00"), money("5.00"), money("10.00"), PricingSource.RETAIL,
                        false, null, null, null)));
        when(inventoryBatchService.load(anyCollection(), anyMap())).thenReturn(Map.of());
        when(orderItemRepository.sumQuantityByProductIds(any())).thenReturn(List.of());
        when(categoryRepository.findByIsDeletedOrderBySortOrderAscIdAsc(0)).thenReturn(List.of(root, child));

        PublicProductResponse response = new PublicProductServiceImpl(productRepository, productSpecRepository,
                categoryRepository, orderItemRepository, inventoryBatchService, pricingService)
                .getProduct(product.getId(), null);

        assertEquals(root.getId(), response.primaryCategoryId());
        assertEquals("Software", response.primaryCategoryName());
        assertEquals(child.getId(), response.secondaryCategoryId());
        assertEquals("AI tools", response.secondaryCategoryName());
    }

    private Product product(int index) {
        Product product = new Product();
        product.setId(UUID.randomUUID());
        product.setProductNo((long) index + 1);
        product.setTitle("Product " + index);
        product.setRetailPrice(money("10.00"));
        product.setCostPrice(money("5.00"));
        product.setCategoryId(UUID.randomUUID());
        product.setIsDeleted(0);
        product.setEnabled(true);
        return product;
    }

    private ProductSpec spec(Product product, String name, String cost, String retail) {
        ProductSpec spec = new ProductSpec();
        spec.setId(UUID.randomUUID());
        spec.setProductId(product.getId());
        spec.setName(name);
        spec.setCostPrice(money(cost));
        spec.setRetailPrice(money(retail));
        spec.setVisible(true);
        spec.setIsDeleted(0);
        return spec;
    }

    private ProductCategory category(UUID id, String name, UUID parentId) {
        ProductCategory category = new ProductCategory();
        category.setId(id);
        category.setName(name);
        category.setParentId(parentId);
        category.setIsDeleted(0);
        return category;
    }

    private BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
