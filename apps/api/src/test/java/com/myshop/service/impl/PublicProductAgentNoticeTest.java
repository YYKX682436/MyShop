package com.myshop.service.impl;

import com.myshop.constant.PricingSource;
import com.myshop.common.PageResult;
import com.myshop.entity.Product;
import com.myshop.entity.UserLevel;
import com.myshop.model.pricing.PriceResolution;
import com.myshop.model.pricing.PriceTarget;
import com.myshop.model.response.PublicProductResponse;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.ProductCategoryRepository;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.ProductSpecRepository;
import com.myshop.service.PricingService;
import com.myshop.service.ProductInventoryBatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublicProductAgentNoticeTest {

    private final ProductRepository products = mock(ProductRepository.class);
    private final ProductSpecRepository specs = mock(ProductSpecRepository.class);
    private final ProductCategoryRepository categories = mock(ProductCategoryRepository.class);
    private final OrderItemRepository orders = mock(OrderItemRepository.class);
    private final ProductInventoryBatchService inventory = mock(ProductInventoryBatchService.class);
    private final PricingService pricing = mock(PricingService.class);
    private final PublicProductServiceImpl service = new PublicProductServiceImpl(
            products, specs, categories, orders, inventory, pricing);

    private Product product;
    private UUID productId;
    private UUID userId;
    private Map<PriceTarget, PriceResolution> prices;

    @BeforeEach
    void setUp() {
        productId = UUID.randomUUID();
        userId = UUID.randomUUID();
        product = new Product();
        product.setId(productId);
        product.setTitle("Notice product");
        product.setCategoryId(UUID.randomUUID());
        product.setRetailPrice(BigDecimal.TEN);
        product.setAgentNoticeText("建议零售价 12 元");
        product.setAgentNoticeShowInDetail(true);
        product.setAgentNoticeVersion(4);

        when(products.findById(productId)).thenReturn(Optional.of(product));
        when(specs.findByProductIdInAndIsDeletedAndVisibleOrderByProductIdAscSortOrderAsc(List.of(productId), 0, true))
                .thenReturn(List.of());
        when(categories.findByIsDeletedOrderBySortOrderAscIdAsc(0)).thenReturn(List.of());
        when(orders.sumQuantityByProductIds(List.of(productId))).thenReturn(List.of());
        when(inventory.load(anyCollection(), anyMap())).thenReturn(Map.of(
                new PriceTarget(productId, null), new ProductInventoryBatchService.StockSnapshot(2L, false)));
        prices = Map.of(new PriceTarget(productId, null), new PriceResolution(
                BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN, PricingSource.RETAIL,
                false, BigDecimal.TEN, null, null));
        when(pricing.resolveBatch(anyCollection(), anyMap(), nullable(UserLevel.class))).thenReturn(prices);
        when(pricing.resolveBatchForUser(anyCollection(), anyMap(), any(UserLevel.class), any(UUID.class)))
                .thenReturn(prices);
    }

    @Test
    void anonymousAndDefaultLevelDetailsDoNotExposeNotice() {
        when(pricing.resolvePublicLevel(nullable(UUID.class))).thenReturn(null);

        PublicProductResponse anonymous = service.getProduct(productId, null);

        UserLevel defaultLevel = new UserLevel();
        defaultLevel.setEnabled(true);
        defaultLevel.setDefault(true);
        when(pricing.resolvePublicLevel(userId)).thenReturn(defaultLevel);
        PublicProductResponse ordinary = service.getProduct(productId, userId);

        assertNull(anonymous.agentNoticeText());
        assertNull(anonymous.agentNoticeVersion());
        assertNull(anonymous.agentNoticeViewerId());
        assertNull(ordinary.agentNoticeText());
        assertNull(ordinary.agentNoticeShowInDetail());
        assertNull(ordinary.agentNoticeViewerId());
    }

    @Test
    void activeNonDefaultLevelDetailIncludesNoticeAndVersion() {
        UserLevel agentLevel = new UserLevel();
        agentLevel.setEnabled(true);
        agentLevel.setDefault(false);
        when(pricing.resolvePublicLevel(userId)).thenReturn(agentLevel);

        PublicProductResponse response = service.getProduct(productId, userId);

        assertEquals("建议零售价 12 元", response.agentNoticeText());
        assertEquals(true, response.agentNoticeShowInDetail());
        assertEquals(4L, response.agentNoticeVersion());
        assertEquals(userId, response.agentNoticeViewerId());
    }

    @Test
    void productListsNeverIncludeNoticeEvenForAnAgent() {
        UserLevel agentLevel = new UserLevel();
        agentLevel.setEnabled(true);
        agentLevel.setDefault(false);
        Pageable pageable = PageRequest.of(0, 10);
        Page<Product> page = new PageImpl<>(List.of(product), pageable, 1);
        when(products.findPublicProducts(isNull(), any(Pageable.class))).thenReturn(page);
        when(pricing.resolvePublicLevel(userId)).thenReturn(agentLevel);

        PageResult<PublicProductResponse> result = service.listProducts(
                null, null, 1, 10, userId, "default", false, null, null);

        assertNull(result.getList().get(0).agentNoticeText());
        assertNull(result.getList().get(0).agentNoticeVersion());
        assertNull(result.getList().get(0).agentNoticeViewerId());
    }
}
