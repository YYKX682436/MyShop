package com.myshop.service.impl;

import com.myshop.constant.CardKeyStatus;
import com.myshop.constant.ErrorCode;
import com.myshop.constant.StockMode;
import com.myshop.entity.Product;
import com.myshop.entity.ProductCategory;
import com.myshop.entity.ProductLevelPriceOverride;
import com.myshop.entity.ProductSpec;
import com.myshop.exception.BusinessException;
import com.myshop.model.request.AdminProductSpecRequest;
import com.myshop.model.request.AdminProductStatusRequest;
import com.myshop.model.request.AdminProductUpsertRequest;
import com.myshop.repository.CardKeyRepository;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.ProductCategoryRepository;
import com.myshop.repository.ProductLevelPriceOverrideRepository;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.ProductSpecRepository;
import com.myshop.repository.UserLevelRepository;
import com.myshop.service.ProductInventoryBatchService;
import com.myshop.service.ManualStockService;
import com.myshop.service.PricingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminProductServiceImplTest {

    @Mock private ProductRepository productRepository;
    @Mock private ProductSpecRepository productSpecRepository;
    @Mock private ProductLevelPriceOverrideRepository overrideRepository;
    @Mock private UserLevelRepository userLevelRepository;
    @Mock private ProductCategoryRepository categoryRepository;
    @Mock private CardKeyRepository cardKeyRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private ProductInventoryBatchService inventoryBatchService;
    @Mock private PricingService pricingService;
    @Mock private ManualStockService manualStockService;

    private AdminProductServiceImpl service() {
        return new AdminProductServiceImpl(productRepository, productSpecRepository, overrideRepository,
                userLevelRepository, categoryRepository, cardKeyRepository, orderItemRepository,
                inventoryBatchService, pricingService, manualStockService);
    }

    @Test
    void cannotEnableSpecProductWhenAllSpecsAreHidden() {
        UUID productId = UUID.randomUUID();
        Product product = product(productId, true, false);
        when(productRepository.findByIdForUpdate(productId)).thenReturn(Optional.of(product));
        when(productSpecRepository.existsByProductIdAndIsDeletedAndVisible(productId, 0, true)).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service().setStatus(productId, new AdminProductStatusRequest(true)));

        assertEquals(ErrorCode.BAD_REQUEST, exception.getCode());
        assertEquals("启用规格的商品至少需要一个可见可售规格", exception.getMessage());
        verify(productRepository, never()).saveAndFlush(any(Product.class));
    }

    @Test
    void canEnableSpecProductWhenAnActiveVisibleSpecExists() {
        UUID productId = UUID.randomUUID();
        Product product = product(productId, true, false);
        ProductSpec visibleSpec = spec(UUID.randomUUID(), productId, "80.00", "100.00", true);
        when(productRepository.findByIdForUpdate(productId)).thenReturn(Optional.of(product));
        when(productSpecRepository.existsByProductIdAndIsDeletedAndVisible(productId, 0, true)).thenReturn(true);
        stubResponseData(product, List.of(visibleSpec));
        when(productRepository.saveAndFlush(product)).thenReturn(product);

        var response = service().setStatus(productId, new AdminProductStatusRequest(true));

        assertTrue(product.isEnabled());
        assertTrue(response.enabled());
        verify(productSpecRepository).existsByProductIdAndIsDeletedAndVisible(productId, 0, true);
        verify(productRepository).saveAndFlush(product);
    }

    @Test
    void canEnableProductWithoutSpecsWithoutTheVisibleSpecCheck() {
        UUID productId = UUID.randomUUID();
        Product product = product(productId, false, false);
        when(productRepository.findByIdForUpdate(productId)).thenReturn(Optional.of(product));
        stubResponseData(product, List.of());
        when(productRepository.saveAndFlush(product)).thenReturn(product);

        service().setStatus(productId, new AdminProductStatusRequest(true));

        assertTrue(product.isEnabled());
        verify(productSpecRepository, never()).existsByProductIdAndIsDeletedAndVisible(any(), eq(0), eq(true));
    }

    @Test
    void disablingSpecProductDoesNotRequireAVisibleSpec() {
        UUID productId = UUID.randomUUID();
        Product product = product(productId, true, true);
        ProductSpec hiddenSpec = spec(UUID.randomUUID(), productId, "80.00", "100.00", false);
        when(productRepository.findByIdForUpdate(productId)).thenReturn(Optional.of(product));
        stubResponseData(product, List.of(hiddenSpec));
        when(productRepository.saveAndFlush(product)).thenReturn(product);

        service().setStatus(productId, new AdminProductStatusRequest(false));

        assertFalse(product.isEnabled());
        verify(productSpecRepository, never()).existsByProductIdAndIsDeletedAndVisible(any(), eq(0), eq(true));
    }

    @Test
    void aggregateRootRetailPriceUsesTheLowestVisibleSpecOnly() {
        UUID productId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        Product product = product(productId, true, true);
        ProductSpecRequestData hidden = new ProductSpecRequestData("hidden", "0.00", "1.00", false);
        ProductSpecRequestData visible = new ProductSpecRequestData("visible", "80.00", "100.00", true);
        AdminProductUpsertRequest request = request(categoryId, true, true, null,
                List.of(specRequest(hidden), specRequest(visible)));
        stubResponseData(product, List.of());
        when(productRepository.saveAndFlush(any(Product.class))).thenAnswer(invocation -> {
            Product saved = invocation.getArgument(0);
            if (saved.getId() == null) saved.setId(productId);
            return saved;
        });
        when(productSpecRepository.saveAndFlush(any(ProductSpec.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service().createProduct(request);

        assertEquals(new BigDecimal("100.00"), response.retailPrice());
        assertEquals(new BigDecimal("100.00"), product.getRetailPrice());
    }

    @Test
    void disabledSpecProductCanBeSavedWithAllSpecsHidden() {
        UUID productId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        Product product = product(productId, true, false);
        ProductSpec hiddenSpec = spec(UUID.randomUUID(), productId, "80.00", "100.00", false);
        when(productRepository.findByIdForUpdate(productId)).thenReturn(Optional.of(product));
        stubResponseData(product, List.of(hiddenSpec));
        when(productRepository.saveAndFlush(product)).thenReturn(product);
        when(productSpecRepository.saveAndFlush(any(ProductSpec.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service().updateProduct(productId,
                request(categoryId, true, false, null, List.of(specRequest(hiddenSpec))));

        assertEquals(new BigDecimal("100.00"), response.retailPrice());
        assertFalse(response.enabled());
    }

    @Test
    void adminSpecMarginRateUsesRetailDenominatorIncludingZeroCostAndNegativeMargin() {
        UUID productId = UUID.randomUUID();
        Product product = product(productId, true, true);
        List<ProductSpec> specs = List.of(
                spec(UUID.randomUUID(), productId, "80.00", "100.00", true),
                spec(UUID.randomUUID(), productId, "0.00", "100.00", true),
                spec(UUID.randomUUID(), productId, "120.00", "100.00", true));
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        stubResponseData(product, specs);

        var response = service().getProduct(productId);

        assertEquals(new BigDecimal("20.00"), response.specs().get(0).margin());
        assertEquals(new BigDecimal("20.00"), response.specs().get(0).marginRate());
        assertEquals(new BigDecimal("100.00"), response.specs().get(1).marginRate());
        assertEquals(new BigDecimal("-20.00"), response.specs().get(2).marginRate());
    }

    private void stubResponseData(Product product, List<ProductSpec> specs) {
        when(productSpecRepository.findByProductIdAndIsDeletedOrderBySortOrderAsc(nullable(UUID.class), eq(0)))
                .thenReturn(specs);
        when(overrideRepository.findByProductId(nullable(UUID.class))).thenReturn(List.<ProductLevelPriceOverride>of());
        when(userLevelRepository.findAllOrdered()).thenReturn(List.of());
        when(inventoryBatchService.load(anyCollection(), anyMap())).thenReturn(Map.of());
        ProductCategory category = new ProductCategory();
        category.setId(product.getCategoryId());
        category.setName("Test category");
        when(categoryRepository.findAllById(anyIterable())).thenReturn(List.of(category));
        when(orderItemRepository.sumQuantityByProductId(any())).thenReturn(0);
        if (!specs.isEmpty()) {
            when(cardKeyRepository.countByProductIdAndSpecIdExcludingStatus(any(), any(), eq(CardKeyStatus.INVALID)))
                    .thenReturn(0L);
        }
    }

    private Product product(UUID id, boolean specEnabled, boolean enabled) {
        Product product = new Product();
        product.setId(id);
        product.setTitle("Test product");
        product.setRetailPrice(new BigDecimal("100.00"));
        product.setCostPrice(specEnabled ? null : new BigDecimal("80.00"));
        product.setCategoryId(UUID.randomUUID());
        product.setStockMode(StockMode.CARD_KEY);
        product.setDeliveryType("AUTO");
        product.setSpecEnabled(specEnabled);
        product.setEnabled(enabled);
        product.setIsDeleted(0);
        return product;
    }

    private ProductSpec spec(UUID id, UUID productId, String cost, String retail, boolean visible) {
        ProductSpec spec = new ProductSpec();
        spec.setId(id);
        spec.setProductId(productId);
        spec.setName("Spec " + id.toString().substring(0, 8));
        spec.setCostPrice(new BigDecimal(cost));
        spec.setRetailPrice(new BigDecimal(retail));
        spec.setVisible(visible);
        spec.setIsDeleted(0);
        return spec;
    }

    private AdminProductUpsertRequest request(UUID categoryId, boolean specEnabled, boolean enabled,
                                              BigDecimal retailPrice, List<AdminProductSpecRequest> specs) {
        return new AdminProductUpsertRequest("Test product", null, null, null, null, retailPrice,
                categoryId, 10, specEnabled, enabled, 0, 0, "AUTO", List.of(), StockMode.CARD_KEY,
                null, specs, List.of());
    }

    private AdminProductSpecRequest specRequest(ProductSpec spec) {
        return new AdminProductSpecRequest(spec.getId(), spec.getName(), spec.getCostPrice(),
                spec.getRetailPrice(), spec.isVisible(), spec.getSortOrder(), null);
    }

    private AdminProductSpecRequest specRequest(ProductSpecRequestData spec) {
        return new AdminProductSpecRequest(null, spec.name(), new BigDecimal(spec.cost()),
                new BigDecimal(spec.retail()), spec.visible(), 0, null);
    }

    private record ProductSpecRequestData(String name, String cost, String retail, boolean visible) {
    }
}
