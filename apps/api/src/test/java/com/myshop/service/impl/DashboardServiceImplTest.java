package com.myshop.service.impl;

import com.myshop.constant.CardKeyStatus;
import com.myshop.constant.OrderStatus;
import com.myshop.constant.UserRole;
import com.myshop.entity.User;
import com.myshop.entity.VisitStats;
import com.myshop.model.response.DashboardStatsResponse;
import com.myshop.repository.CardKeyRepository;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.UserRepository;
import com.myshop.repository.VisitStatsRepository;
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
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
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
    @Mock private CardKeyRepository cardKeyRepository;
    @Mock private VisitStatsRepository visitStatsRepository;
    @Mock private UserRepository userRepository;

    private DashboardServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DashboardServiceImpl(orderRepository, orderItemRepository, productRepository,
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
                new Object[]{OrderStatus.PAID, 4L}, new Object[]{OrderStatus.EXPIRED, 1L}));
        when(orderRepository.countDistinctBuyersBetween(todayStart, tomorrow)).thenReturn(2L);
        when(orderRepository.countByStatus(OrderStatus.PENDING)).thenReturn(2L);
        when(orderRepository.countByStatus(OrderStatus.PAID)).thenReturn(3L);
        when(orderRepository.countDeliveredBetween(todayStart, tomorrow)).thenReturn(1L);
        when(cardKeyRepository.countByStatus(CardKeyStatus.AVAILABLE)).thenReturn(5L);

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
        when(productRepository.findActiveProductStockSummaries()).thenReturn(List.of(
                new Object[]{zeroProduct, "Zero", 10, 0L},
                new Object[]{stockedProduct, "Stocked", 3, 4L}));
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
        assertEquals(3L, result.paidWaitingDelivery());
        assertEquals(1L, result.deliveredToday());
        assertEquals(1L, result.expiredToday());
        assertEquals(80.0, result.todayPaymentSuccessRate());
        assertEquals(5L, result.todayPaymentSuccessSampleCount());
        assertEquals(2L, result.activeProducts());
        assertEquals(1L, result.zeroStockProducts());
        assertEquals(1, result.lowStockProducts().size());
        assertEquals(zeroProduct, result.lowStockProducts().get(0).productId());
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
    void returnsZeroChangeWhenBothPeriodsAreEmpty() {
        LocalDateTime todayStart = TODAY.atStartOfDay();
        LocalDateTime tomorrow = TODAY.plusDays(1).atStartOfDay();
        LocalDateTime yesterday = TODAY.minusDays(1).atStartOfDay();
        LocalDateTime monthStart = TODAY.withDayOfMonth(1).atStartOfDay();
        LocalDateTime now = LocalDateTime.now(CLOCK);

        when(orderRepository.sumSalesBetween(todayStart, tomorrow)).thenReturn(BigDecimal.ZERO);
        when(orderRepository.sumSalesBetween(yesterday, todayStart)).thenReturn(BigDecimal.ZERO);
        when(orderRepository.sumSalesBetween(monthStart, tomorrow)).thenReturn(BigDecimal.ZERO);
        when(orderRepository.countPaidOrdersBetween(todayStart, tomorrow)).thenReturn(0L);
        when(orderRepository.countPaidOrdersBetween(yesterday, todayStart)).thenReturn(0L);
        when(orderRepository.countPaidOrdersBetween(monthStart, tomorrow)).thenReturn(0L);
        when(orderRepository.countPaymentOutcomesBetween(todayStart, tomorrow)).thenReturn(List.of());
        when(productRepository.findActiveProductStockSummaries()).thenReturn(List.of());
        when(orderItemRepository.findTopProductsBetween(now.minusDays(7), now)).thenReturn(List.of());
        when(userRepository.findTop5ByRoleAndIsDeletedOrderByCreatedAtDesc(UserRole.USER, 0))
                .thenReturn(List.of());
        when(visitStatsRepository.findByVisitDate(TODAY)).thenReturn(Optional.empty());
        when(cardKeyRepository.countByStatus(CardKeyStatus.AVAILABLE)).thenReturn(0L);

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
}
