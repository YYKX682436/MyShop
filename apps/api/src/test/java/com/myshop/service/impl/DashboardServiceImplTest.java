package com.myshop.service.impl;

import com.myshop.constant.OrderStatus;
import com.myshop.constant.StockMode;
import com.myshop.constant.UserRole;
import com.myshop.entity.Product;
import com.myshop.entity.ProductSpec;
import com.myshop.entity.User;
import com.myshop.entity.VisitStats;
import com.myshop.model.pricing.PriceTarget;
import com.myshop.model.response.DashboardStatsResponse;
import com.myshop.repository.CardKeyRepository;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.ProductSpecRepository;
import com.myshop.repository.UserRepository;
import com.myshop.repository.VisitStatsRepository;
import com.myshop.service.ProductInventoryBatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceImplTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 27);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-27T04:00:00Z"), ZONE);

    @Mock private OrderRepository orderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private ProductRepository productRepository;
    @Mock private ProductSpecRepository productSpecRepository;
    @Mock private ProductInventoryBatchService inventoryBatchService;
    @Mock private CardKeyRepository cardKeyRepository;
    @Mock private VisitStatsRepository visitStatsRepository;
    @Mock private UserRepository userRepository;

    private DashboardServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DashboardServiceImpl(orderRepository, orderItemRepository, productRepository,
                productSpecRepository, inventoryBatchService,
                cardKeyRepository, visitStatsRepository, userRepository, CLOCK);
    }

    @Test
    void aggregatesDashboardWithCorrectUserStatusBoundariesAndNoNPlusOneReads() {
        LocalDateTime todayStart = TODAY.atStartOfDay();
        LocalDateTime tomorrow = TODAY.plusDays(1).atStartOfDay();
        LocalDateTime yesterday = TODAY.minusDays(1).atStartOfDay();
        LocalDateTime monthStart = TODAY.withDayOfMonth(1).atStartOfDay();
        LocalDateTime now = LocalDateTime.now(CLOCK);

        when(orderRepository.sumSalesBetween(todayStart, tomorrow)).thenReturn(new BigDecimal("25.00"));
        when(orderRepository.sumSalesBetween(yesterday, todayStart)).thenReturn(BigDecimal.ZERO);
        when(orderRepository.sumSalesBetween(monthStart, tomorrow)).thenReturn(new BigDecimal("25.00"));
        when(orderRepository.countPaidOrdersBetween(todayStart, tomorrow)).thenReturn(5L);
        when(orderRepository.countPaidOrdersBetween(yesterday, todayStart)).thenReturn(0L);
        when(orderRepository.countPaidOrdersBetween(monthStart, tomorrow)).thenReturn(5L);
        when(orderRepository.countPaymentOutcomesBetween(todayStart, tomorrow)).thenReturn(List.of(
                new Object[]{OrderStatus.PAID, 4L}, new Object[]{OrderStatus.REFUNDED, 1L},
                new Object[]{OrderStatus.EXPIRED, 1L}));
        when(orderRepository.countDistinctBuyersBetween(todayStart, tomorrow)).thenReturn(2L);
        when(orderRepository.countByStatus(OrderStatus.PENDING)).thenReturn(2L);
        when(orderRepository.countByStatus(OrderStatus.PAID)).thenReturn(3L);
        when(orderRepository.countByStatus(OrderStatus.AFTER_SALE)).thenReturn(1L);
        when(orderRepository.countDeliveredBetween(todayStart, tomorrow)).thenReturn(1L);
        when(cardKeyRepository.countAvailableForActiveCardKeyProducts()).thenReturn(5L);

        when(userRepository.countByRoleAndIsDeletedAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                eq(UserRole.USER), eq(0), eq(todayStart), eq(tomorrow))).thenReturn(3L);
        when(userRepository.countByRoleAndIsDeletedAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                eq(UserRole.USER), eq(0), eq(yesterday), eq(todayStart))).thenReturn(0L);
        when(userRepository.countByRoleAndIsDeletedAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                eq(UserRole.USER), eq(0), eq(monthStart), eq(tomorrow))).thenReturn(3L);
        when(userRepository.countByRoleAndIsDeleted(UserRole.USER, 0)).thenReturn(4L);

        VisitStats visits = new VisitStats();
        visits.setPv(100);
        visits.setUv(20);
        when(visitStatsRepository.findByVisitDate(TODAY)).thenReturn(Optional.of(visits));

        UUID zeroProduct = UUID.randomUUID();
        UUID stockedProduct = UUID.randomUUID();
        Product zero = product(zeroProduct, "Zero", 10, false, StockMode.CARD_KEY);
        Product stocked = product(stockedProduct, "Stocked", 3, false, StockMode.CARD_KEY);
        List<Product> activeProducts = List.of(zero, stocked);
        when(productRepository.findActiveProductsForDashboard()).thenReturn(activeProducts);
        when(inventoryBatchService.load(activeProducts, Map.of())).thenReturn(Map.of(
                new PriceTarget(zeroProduct, null), new ProductInventoryBatchService.StockSnapshot(0L, false),
                new PriceTarget(stockedProduct, null), new ProductInventoryBatchService.StockSnapshot(4L, false)));
        when(orderItemRepository.findTopProductsBetween(now.minusDays(7), now)).thenReturn(List.<Object[]>of(
                new Object[]{stockedProduct, "Stocked", 8L, new BigDecimal("40.00")}));

        User recent = new User();
        recent.setId(UUID.randomUUID());
        recent.setUsername("buyer");
        recent.setEmail("buyer@example.com");
        recent.setCreatedAt(todayStart.plusHours(2));
        when(userRepository.findTop5ByRoleAndIsDeletedOrderByCreatedAtDesc(UserRole.USER, 0))
                .thenReturn(List.of(recent));

        DashboardStatsResponse result = service.getStats();

        assertEquals(5L, result.todayOrders());
        assertEquals(new BigDecimal("25.00"), result.todaySales());
        assertEquals(3L, result.todayNewUsers());
        assertEquals(4L, result.totalUsers());
        assertEquals(2L, result.todayBuyers());
        assertEquals(2L, result.pendingOrders());
        assertEquals(1L, result.afterSaleOrders());
        assertEquals(3L, result.paidWaitingDelivery());
        assertEquals(1L, result.deliveredToday());
        assertEquals(1L, result.expiredToday());
        assertEquals(83.33, result.todayPaymentSuccessRate());
        assertEquals(6L, result.todayPaymentSuccessSampleCount());
        assertEquals(2L, result.activeProducts());
        assertEquals(1L, result.zeroStockProducts());
        assertEquals(1, result.lowStockProducts().size());
        assertEquals(zeroProduct, result.lowStockProducts().get(0).productId());
        assertNull(result.lowStockProducts().get(0).specId());
        assertNull(result.lowStockProducts().get(0).specName());
        assertEquals("OUT_OF_STOCK", result.lowStockProducts().get(0).stockStatus());
        assertEquals(1, result.topProducts().size());
        assertEquals(8L, result.topProducts().get(0).quantity());
        assertEquals(25.0, result.conversionRate());
        assertNull(result.todaySalesChangePct());
        assertNull(result.todayOrdersChangePct());
        assertNull(result.todayNewUsersChangePct());

        verify(orderRepository, never()).findAll();
        verify(productRepository, never()).findAll();
        verify(cardKeyRepository, never()).countByProductIdAndStatus(any(), any());
        verify(orderItemRepository, never()).findAll();
    }

    @Test
    void countsKnownInventoryAlertsPerVisibleSpecAndPreservesSupplierStates() {
        UUID supplierFullId = UUID.randomUUID();
        UUID supplierLowId = UUID.randomUUID();
        UUID supplierEmptyId = UUID.randomUUID();
        UUID manualBoundaryId = UUID.randomUUID();
        UUID cardAvailableId = UUID.randomUUID();
        UUID specEmptyProductId = UUID.randomUUID();
        UUID specLowProductId = UUID.randomUUID();
        UUID unlimitedId = UUID.randomUUID();
        UUID staleId = UUID.randomUUID();
        UUID syncFailedId = UUID.randomUUID();
        UUID upstreamDownId = UUID.randomUUID();
        UUID belowMinimumId = UUID.randomUUID();
        UUID unsupportedId = UUID.randomUUID();

        List<Product> activeProducts = List.of(
                product(supplierFullId, "Supplier full", 10, false, StockMode.SUPPLIER),
                product(supplierLowId, "Supplier low", 10, false, StockMode.SUPPLIER),
                product(supplierEmptyId, "Supplier empty", 10, false, StockMode.SUPPLIER),
                product(manualBoundaryId, "Manual boundary", 10, false, StockMode.MANUAL),
                product(cardAvailableId, "Card available", 10, false, StockMode.CARD_KEY),
                product(specEmptyProductId, "Plan", 10, true, StockMode.CARD_KEY),
                product(specLowProductId, "Plan low", 10, true, StockMode.MANUAL),
                product(unlimitedId, "Unlimited", 10, false, StockMode.UNLIMITED),
                product(staleId, "Stale supplier", 10, false, StockMode.SUPPLIER),
                product(syncFailedId, "Failed supplier", 10, false, StockMode.SUPPLIER),
                product(upstreamDownId, "Upstream stopped", 10, false, StockMode.SUPPLIER),
                product(belowMinimumId, "Below minimum", 10, false, StockMode.SUPPLIER),
                product(unsupportedId, "Unsupported inputs", 10, false, StockMode.SUPPLIER));
        when(productRepository.findActiveProductsForDashboard()).thenReturn(activeProducts);

        UUID monthId = UUID.randomUUID();
        UUID yearId = UUID.randomUUID();
        UUID lifetimeId = UUID.randomUUID();
        UUID lowMonthId = UUID.randomUUID();
        UUID lowYearId = UUID.randomUUID();
        UUID lowLifetimeId = UUID.randomUUID();
        UUID hiddenSpecId = UUID.randomUUID();
        UUID deletedSpecId = UUID.randomUUID();
        List<ProductSpec> visibleSpecs = List.of(
                spec(monthId, specEmptyProductId, "Month card"),
                spec(yearId, specEmptyProductId, "Year card"),
                spec(lifetimeId, specEmptyProductId, "Lifetime"),
                spec(lowMonthId, specLowProductId, "Month card"),
                spec(lowYearId, specLowProductId, "Year card"),
                spec(lowLifetimeId, specLowProductId, "Lifetime"));
        Map<UUID, List<ProductSpec>> specsByProduct = Map.of(
                specEmptyProductId, visibleSpecs.subList(0, 3),
                specLowProductId, visibleSpecs.subList(3, 6));
        when(productSpecRepository.findByProductIdInAndIsDeletedAndVisibleOrderByProductIdAscSortOrderAsc(
                activeProducts.stream().map(Product::getId).toList(), 0, true)).thenReturn(visibleSpecs);

        Map<PriceTarget, ProductInventoryBatchService.StockSnapshot> snapshots = new java.util.HashMap<>();
        snapshots.put(new PriceTarget(supplierFullId, null), snapshot(100L, "AVAILABLE"));
        snapshots.put(new PriceTarget(supplierLowId, null), snapshot(5L, "AVAILABLE"));
        snapshots.put(new PriceTarget(supplierEmptyId, null), snapshot(0L, "OUT_OF_STOCK"));
        snapshots.put(new PriceTarget(manualBoundaryId, null), snapshot(10L, "AVAILABLE"));
        snapshots.put(new PriceTarget(cardAvailableId, null), snapshot(11L, "AVAILABLE"));
        snapshots.put(new PriceTarget(specEmptyProductId, null), snapshot(0L, "OUT_OF_STOCK"));
        snapshots.put(new PriceTarget(specEmptyProductId, monthId), snapshot(0L, "OUT_OF_STOCK"));
        snapshots.put(new PriceTarget(specEmptyProductId, yearId), snapshot(0L, "OUT_OF_STOCK"));
        snapshots.put(new PriceTarget(specEmptyProductId, lifetimeId), snapshot(100L, "AVAILABLE"));
        snapshots.put(new PriceTarget(specEmptyProductId, hiddenSpecId), snapshot(0L, "OUT_OF_STOCK"));
        snapshots.put(new PriceTarget(specEmptyProductId, deletedSpecId), snapshot(0L, "OUT_OF_STOCK"));
        snapshots.put(new PriceTarget(specLowProductId, null), snapshot(0L, "OUT_OF_STOCK"));
        snapshots.put(new PriceTarget(specLowProductId, lowMonthId), snapshot(3L, "AVAILABLE"));
        snapshots.put(new PriceTarget(specLowProductId, lowYearId), snapshot(8L, "AVAILABLE"));
        snapshots.put(new PriceTarget(specLowProductId, lowLifetimeId), snapshot(100L, "AVAILABLE"));
        snapshots.put(new PriceTarget(unlimitedId, null), new ProductInventoryBatchService.StockSnapshot(null, true));
        snapshots.put(new PriceTarget(staleId, null), snapshot(0L, "STALE"));
        snapshots.put(new PriceTarget(syncFailedId, null), snapshot(0L, "SYNC_FAILED"));
        snapshots.put(new PriceTarget(upstreamDownId, null), snapshot(0L, "UPSTREAM_UNAVAILABLE"));
        snapshots.put(new PriceTarget(belowMinimumId, null), snapshot(0L, "BELOW_MINIMUM"));
        snapshots.put(new PriceTarget(unsupportedId, null), snapshot(0L, "INPUT_UNSUPPORTED"));
        when(inventoryBatchService.load(eq(activeProducts), anyMap())).thenReturn(snapshots);

        DashboardStatsResponse result = service.getStats();

        assertEquals(13L, result.activeProducts());
        assertEquals(10, result.lowStockProducts().size());
        assertEquals(3L, result.zeroStockProducts());
        assertFalse(hasWarning(result, supplierFullId, null));
        assertFalse(hasWarning(result, cardAvailableId, null));
        assertFalse(hasWarning(result, staleId, null));
        assertFalse(hasWarning(result, syncFailedId, null));
        assertTrue(hasWarning(result, supplierLowId, null));
        assertTrue(hasWarning(result, supplierEmptyId, null));
        assertTrue(hasWarning(result, manualBoundaryId, null));
        assertTrue(hasWarning(result, specEmptyProductId, monthId));
        assertTrue(hasWarning(result, specEmptyProductId, yearId));
        assertFalse(hasWarning(result, specEmptyProductId, lifetimeId));
        assertFalse(hasWarning(result, specEmptyProductId, null));
        assertFalse(hasWarning(result, specEmptyProductId, hiddenSpecId));
        assertFalse(hasWarning(result, specEmptyProductId, deletedSpecId));
        assertTrue(hasWarning(result, specLowProductId, lowMonthId));
        assertTrue(hasWarning(result, specLowProductId, lowYearId));
        assertFalse(hasWarning(result, specLowProductId, lowLifetimeId));
        assertEquals("Month card", warning(result, specEmptyProductId, monthId).specName());
        assertEquals("UPSTREAM_UNAVAILABLE", warning(result, upstreamDownId, null).stockStatus());
        assertEquals("BELOW_MINIMUM", warning(result, belowMinimumId, null).stockStatus());
        assertEquals("INPUT_UNSUPPORTED", warning(result, unsupportedId, null).stockStatus());
    }

    @Test
    void reflectsStockRecoveryOnTheNextDashboardRead() {
        UUID id = UUID.randomUUID();
        Product product = product(id, "Recovering stock", 10, false, StockMode.SUPPLIER);
        List<Product> activeProducts = List.of(product);
        when(productRepository.findActiveProductsForDashboard()).thenReturn(activeProducts);
        when(inventoryBatchService.load(activeProducts, Map.of())).thenReturn(
                Map.of(new PriceTarget(id, null), snapshot(5L, "AVAILABLE")),
                Map.of(new PriceTarget(id, null), snapshot(11L, "AVAILABLE")));

        assertEquals(1, service.getStats().lowStockProducts().size());
        DashboardStatsResponse recovered = service.getStats();

        assertTrue(recovered.lowStockProducts().isEmpty());
        assertEquals(0L, recovered.zeroStockProducts());
    }

    @Test
    void returnsZeroChangeWhenBothPeriodsAreEmpty() {
        LocalDateTime todayStart = TODAY.atStartOfDay();
        LocalDateTime tomorrow = TODAY.plusDays(1).atStartOfDay();
        LocalDateTime yesterday = TODAY.minusDays(1).atStartOfDay();
        LocalDateTime monthStart = TODAY.withDayOfMonth(1).atStartOfDay();
        LocalDateTime now = LocalDateTime.now(CLOCK);

        when(productRepository.findActiveProductsForDashboard()).thenReturn(List.of());
        when(inventoryBatchService.load(anyCollection(), anyMap())).thenReturn(Map.of());

        when(orderRepository.sumSalesBetween(todayStart, tomorrow)).thenReturn(BigDecimal.ZERO);
        when(orderRepository.sumSalesBetween(yesterday, todayStart)).thenReturn(BigDecimal.ZERO);
        when(orderRepository.sumSalesBetween(monthStart, tomorrow)).thenReturn(BigDecimal.ZERO);
        when(orderRepository.countPaidOrdersBetween(todayStart, tomorrow)).thenReturn(0L);
        when(orderRepository.countPaidOrdersBetween(yesterday, todayStart)).thenReturn(0L);
        when(orderRepository.countPaidOrdersBetween(monthStart, tomorrow)).thenReturn(0L);
        when(orderRepository.countPaymentOutcomesBetween(todayStart, tomorrow)).thenReturn(List.of());
        when(orderItemRepository.findTopProductsBetween(now.minusDays(7), now)).thenReturn(List.of());
        when(userRepository.findTop5ByRoleAndIsDeletedOrderByCreatedAtDesc(UserRole.USER, 0))
                .thenReturn(List.of());
        when(visitStatsRepository.findByVisitDate(TODAY)).thenReturn(Optional.empty());
        when(cardKeyRepository.countAvailableForActiveCardKeyProducts()).thenReturn(0L);

        DashboardStatsResponse result = service.getStats();

        assertEquals(0.0, result.todaySalesChangePct());
        assertEquals(0.0, result.todayOrdersChangePct());
        assertEquals(0.0, result.todayNewUsersChangePct());
        assertEquals(0.0, result.todayPaymentSuccessRate());
    }

    @Test
    void trendQueriesDatabaseAndFillsMissingDailyBuckets() {
        LocalDateTime start = TODAY.atStartOfDay();
        LocalDateTime endExclusive = TODAY.plusDays(3).atStartOfDay();
        when(orderRepository.findDailySalesTrend(start, endExclusive)).thenReturn(List.<Object[]>of(
                new Object[]{"2026-08-28", new BigDecimal("12.50"), 2L}));

        List<?> result = service.getSalesTrend("daily", TODAY.toString(), "2026-08-29");

        assertEquals(3, result.size());
        assertEquals("2026-08-27", ((java.util.Map<?, ?>) result.get(0)).get("date"));
        assertEquals(BigDecimal.ZERO, ((java.util.Map<?, ?>) result.get(0)).get("sales_amount"));
        assertEquals("2026-08-28", ((java.util.Map<?, ?>) result.get(1)).get("date"));
        assertEquals(2L, ((java.util.Map<?, ?>) result.get(1)).get("order_count"));
        verify(orderRepository).findDailySalesTrend(start, endExclusive);
        verify(orderRepository, never()).findAll();
    }

    private Product product(UUID id, String title, int threshold, boolean specEnabled, StockMode stockMode) {
        Product product = new Product();
        product.setId(id);
        product.setTitle(title);
        product.setLowStockThreshold(threshold);
        product.setSpecEnabled(specEnabled);
        product.setStockMode(stockMode);
        return product;
    }

    private ProductSpec spec(UUID id, UUID productId, String name) {
        ProductSpec spec = new ProductSpec();
        spec.setId(id);
        spec.setProductId(productId);
        spec.setName(name);
        return spec;
    }

    private ProductInventoryBatchService.StockSnapshot snapshot(long available, String status) {
        return new ProductInventoryBatchService.StockSnapshot(available, false, status, null, null);
    }

    private boolean hasWarning(DashboardStatsResponse result, UUID productId, UUID specId) {
        return result.lowStockProducts().stream().anyMatch(item -> item.productId().equals(productId)
                && java.util.Objects.equals(item.specId(), specId));
    }

    private DashboardStatsResponse.LowStockProduct warning(
            DashboardStatsResponse result, UUID productId, UUID specId) {
        return result.lowStockProducts().stream().filter(item -> item.productId().equals(productId)
                && java.util.Objects.equals(item.specId(), specId)).findFirst().orElseThrow();
    }
}
