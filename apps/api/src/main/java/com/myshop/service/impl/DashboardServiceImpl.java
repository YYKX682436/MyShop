package com.myshop.service.impl;

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
import com.myshop.service.DashboardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class DashboardServiceImpl implements DashboardService {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductRepository productRepository;
    private final CardKeyRepository cardKeyRepository;
    private final VisitStatsRepository visitStatsRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    @Autowired
    public DashboardServiceImpl(OrderRepository orderRepository,
                                OrderItemRepository orderItemRepository,
                                ProductRepository productRepository,
                                CardKeyRepository cardKeyRepository,
                                VisitStatsRepository visitStatsRepository,
                                UserRepository userRepository,
                                Clock clock) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.productRepository = productRepository;
        this.cardKeyRepository = cardKeyRepository;
        this.visitStatsRepository = visitStatsRepository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @Override
    public DashboardStatsResponse getStats() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        LocalDate yesterday = today.minusDays(1);
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime tomorrowStart = today.plusDays(1).atStartOfDay();
        LocalDateTime yesterdayStart = yesterday.atStartOfDay();
        LocalDateTime monthStart = today.withDayOfMonth(1).atStartOfDay();

        BigDecimal todaySales = zeroIfNull(orderRepository.sumSalesBetween(todayStart, tomorrowStart));
        BigDecimal yesterdaySales = zeroIfNull(orderRepository.sumSalesBetween(yesterdayStart, todayStart));
        BigDecimal monthSales = zeroIfNull(orderRepository.sumSalesBetween(monthStart, tomorrowStart));
        long todayOrders = orderRepository.countPaidOrdersBetween(todayStart, tomorrowStart);
        long yesterdayOrders = orderRepository.countPaidOrdersBetween(yesterdayStart, todayStart);
        long monthOrders = orderRepository.countPaidOrdersBetween(monthStart, tomorrowStart);

        long todayNewUsers = userRepository
                .countByRoleAndIsDeletedAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                        UserRole.USER, 0, todayStart, tomorrowStart);
        long yesterdayNewUsers = userRepository
                .countByRoleAndIsDeletedAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                        UserRole.USER, 0, yesterdayStart, todayStart);
        long monthNewUsers = userRepository
                .countByRoleAndIsDeletedAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                        UserRole.USER, 0, monthStart, tomorrowStart);
        long totalUsers = userRepository.countByRoleAndIsDeleted(UserRole.USER, 0);

        VisitStats todayVisit = visitStatsRepository.findByVisitDate(today).orElse(null);
        long todayPv = todayVisit != null ? todayVisit.getPv() : 0;
        long todayUv = todayVisit != null ? todayVisit.getUv() : 0;
        double conversionRate = todayUv > 0 ? (double) todayOrders / todayUv * 100 : 0;

        Map<OrderStatus, Long> outcomes = paymentOutcomes(todayStart, tomorrowStart);
        long successfulPayments = outcomes.getOrDefault(OrderStatus.PAID, 0L)
                + outcomes.getOrDefault(OrderStatus.DELIVERED, 0L)
                + outcomes.getOrDefault(OrderStatus.REFUNDED, 0L);
        long failedPayments = outcomes.getOrDefault(OrderStatus.EXPIRED, 0L);
        long paymentSampleCount = successfulPayments + failedPayments;
        double paymentSuccessRate = paymentSampleCount == 0
                ? 0
                : (double) successfulPayments / paymentSampleCount * 100;

        // One grouped PostgreSQL read replaces the per-product loop plus one
        // stock count per product. It selects the source by stock mode;
        // unlimited products return NULL and are excluded from both counters.
        List<Object[]> stockRows = safeRows(productRepository.findActiveProductStockSummaries());
        List<DashboardStatsResponse.LowStockProduct> lowStockProducts = new ArrayList<>();
        long zeroStockProducts = 0;
        for (Object[] row : stockRows) {
            UUID productId = (UUID) row[0];
            String title = String.valueOf(row[1]);
            int threshold = number(row[2]).intValue();
            // UNLIMITED products intentionally return SQL NULL and never
            // participate in zero/low-stock counters.
            if (row[3] == null) continue;
            long availableStock = number(row[3]).longValue();
            if (availableStock == 0) {
                zeroStockProducts++;
            }
            if (availableStock <= threshold) {
                lowStockProducts.add(new DashboardStatsResponse.LowStockProduct(
                        productId, title, availableStock, threshold));
            }
        }

        // TOP 5 is grouped, filtered, sorted, and limited by PostgreSQL; no
        // order item collection is loaded into the JVM for this panel.
        List<DashboardStatsResponse.TopProduct> topProducts = safeRows(
                orderItemRepository.findTopProductsBetween(now.minusDays(7), now)).stream()
                .map(row -> new DashboardStatsResponse.TopProduct(
                        (UUID) row[0],
                        String.valueOf(row[1]),
                        number(row[2]).longValue(),
                        decimal(row[3])))
                .toList();

        List<DashboardStatsResponse.RecentUser> recentUsers = userRepository
                .findTop5ByRoleAndIsDeletedOrderByCreatedAtDesc(UserRole.USER, 0)
                .stream()
                .map(this::toRecentUser)
                .toList();

        return new DashboardStatsResponse(
                todaySales,
                monthSales,
                todayOrders,
                monthOrders,
                rounded(conversionRate),
                todayPv,
                todayUv,
                changePercentage(todaySales, yesterdaySales),
                changePercentage(BigDecimal.valueOf(todayOrders), BigDecimal.valueOf(yesterdayOrders)),
                todayNewUsers,
                changePercentage(BigDecimal.valueOf(todayNewUsers), BigDecimal.valueOf(yesterdayNewUsers)),
                monthNewUsers,
                totalUsers,
                orderRepository.countDistinctBuyersBetween(todayStart, tomorrowStart),
                orderRepository.countByStatus(OrderStatus.PENDING),
                orderRepository.countByStatus(OrderStatus.PAID),
                orderRepository.countDeliveredBetween(todayStart, tomorrowStart),
                // EXPIRED has no transition timestamp in the current schema;
                // use today's created_at plus terminal status as the reliable
                // observable definition for this dashboard metric.
                outcomes.getOrDefault(OrderStatus.EXPIRED, 0L),
                rounded(paymentSuccessRate),
                paymentSampleCount,
                stockRows.size(),
                cardKeyRepository.countAvailableForActiveCardKeyProducts(),
                zeroStockProducts,
                lowStockProducts,
                topProducts,
                recentUsers);
    }

    @Override
    public List<?> getSalesTrend(String period, String startDate, String endDate) {
        LocalDate currentDate = LocalDateTime.now(clock).toLocalDate();
        LocalDate start = startDate != null ? LocalDate.parse(startDate) : currentDate.minusDays(30);
        LocalDate end = endDate != null ? LocalDate.parse(endDate) : currentDate;
        LocalDateTime startDateTime = start.atStartOfDay();
        LocalDateTime endDateTime = end.plusDays(1).atStartOfDay();

        List<Object[]> rows = "monthly".equalsIgnoreCase(period)
                ? orderRepository.findMonthlySalesTrend(startDateTime, endDateTime)
                : orderRepository.findDailySalesTrend(startDateTime, endDateTime);

        Map<String, TrendValue> grouped = new TreeMap<>();
        for (Object[] row : safeRows(rows)) {
            grouped.put(String.valueOf(row[0]), new TrendValue(decimal(row[1]), number(row[2]).longValue()));
        }

        if ("monthly".equalsIgnoreCase(period)) {
            YearMonth month = YearMonth.from(start);
            YearMonth lastMonth = YearMonth.from(end);
            while (!month.isAfter(lastMonth)) {
                grouped.putIfAbsent(month.toString(), TrendValue.ZERO);
                month = month.plusMonths(1);
            }
        } else {
            LocalDate date = start;
            while (!date.isAfter(end)) {
                grouped.putIfAbsent(date.toString(), TrendValue.ZERO);
                date = date.plusDays(1);
            }
        }

        return grouped.entrySet().stream().map(entry -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("date", entry.getKey());
            value.put("sales_amount", entry.getValue().salesAmount());
            value.put("order_count", entry.getValue().orderCount());
            return value;
        }).collect(Collectors.toList());
    }

    private Map<OrderStatus, Long> paymentOutcomes(LocalDateTime start, LocalDateTime end) {
        Map<OrderStatus, Long> result = new LinkedHashMap<>();
        for (Object[] row : safeRows(orderRepository.countPaymentOutcomesBetween(start, end))) {
            OrderStatus status = row[0] instanceof OrderStatus
                    ? (OrderStatus) row[0]
                    : OrderStatus.valueOf(String.valueOf(row[0]));
            result.put(status, number(row[1]).longValue());
        }
        return result;
    }

    private DashboardStatsResponse.RecentUser toRecentUser(User user) {
        return new DashboardStatsResponse.RecentUser(
                user.getId(), user.getUsername(), user.getEmail(), user.getCreatedAt());
    }

    private static List<Object[]> safeRows(List<Object[]> rows) {
        return rows == null ? Collections.emptyList() : rows;
    }

    private static Number number(Object value) {
        return value instanceof Number number ? number : 0;
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return BigDecimal.ZERO;
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static Double changePercentage(BigDecimal current, BigDecimal previous) {
        if (previous.signum() > 0) {
            return rounded(current.subtract(previous)
                    .multiply(ONE_HUNDRED)
                    .divide(previous, 8, RoundingMode.HALF_UP)
                    .doubleValue());
        }
        return current.signum() == 0 ? 0d : null;
    }

    private static double rounded(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record TrendValue(BigDecimal salesAmount, long orderCount) {
        private static final TrendValue ZERO = new TrendValue(BigDecimal.ZERO, 0);
    }
}
