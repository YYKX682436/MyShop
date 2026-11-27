package com.myshop.service.impl;

import com.myshop.model.response.DashboardStatsResponse;
import com.myshop.repository.OrderRepository;
import com.myshop.service.DashboardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import com.myshop.utils.CardContentParser;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@org.junit.jupiter.api.condition.EnabledIf("postgresAvailable")
@ExtendWith(SpringExtension.class)
@SpringBootTest
@Import(DashboardAggregateIntegrationTest.FixedDashboardClockConfiguration.class)
class DashboardAggregateIntegrationTest {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-27T04:00:00Z"), BUSINESS_ZONE);

    static final IntegrationPostgres POSTGRES = new IntegrationPostgres();
    static boolean postgresAvailable() { return POSTGRES.available(); }
    @org.junit.jupiter.api.AfterAll static void stopPostgres() { POSTGRES.close(); }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private OrderRepository orderRepository;

    private long nextUserNo;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("jwt.secret", () -> "dashboard-integration-secret-with-at-least-64-bytes-and-random-looking-value-2026");
        registry.add("mail.enabled", () -> false);
        registry.add("bootstrap.admin.username", () -> "");
        registry.add("bootstrap.admin.email", () -> "");
        registry.add("bootstrap.admin.password", () -> "");
    }

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("DELETE FROM card_keys");
        jdbcTemplate.execute("DELETE FROM manual_stocks");
        jdbcTemplate.execute("DELETE FROM order_items");
        jdbcTemplate.execute("DELETE FROM after_sale_attachments");
        jdbcTemplate.execute("DELETE FROM after_sale_requests");
        jdbcTemplate.execute("DELETE FROM orders");
        jdbcTemplate.execute("DELETE FROM product_specs");
        jdbcTemplate.execute("DELETE FROM products");
        jdbcTemplate.execute("DELETE FROM users");
        jdbcTemplate.execute("DELETE FROM visit_stats");
        nextUserNo = 10000L;
    }

    @Test
    void postgresAggregatesFilterStatusesUsersAndStockInDatabase() {
        LocalDate today = LocalDate.now(CLOCK);
        LocalDateTime start = today.atStartOfDay();
        LocalDateTime now = LocalDateTime.now(CLOCK);
        UUID product = UUID.randomUUID();
        UUID emptyProduct = UUID.randomUUID();
        UUID normalUserId = insertUser("normal", "Buyer@Example.com", "USER", 0, start.plusHours(1));
        insertUser("admin", "admin@example.com", "ADMIN", 0, start.plusHours(1));
        insertUser("deleted", "deleted@example.com", "USER", 1, start.plusHours(1));
        insertProduct(product, "Popular", 5, true, 0, start.plusHours(1));
        insertProduct(emptyProduct, "Empty", 3, true, 0, start.plusHours(1));
        insertProduct(UUID.randomUUID(), "Disabled", 3, false, 0, start.plusHours(1));
        insertCard(product, "available", "AVAILABLE", start.plusHours(1).plusMinutes(1));

        UUID paidOrder = insertOrder(normalUserId, "PAID", start.plusHours(2), start.plusHours(3), "10.00");
        insertOrderItem(paidOrder, product, "Popular", 2, "10.00", start.plusHours(3));
        UUID deliveredOrder = insertOrder(normalUserId, "DELIVERED", start.plusHours(4), start.plusHours(5), "5.00");
        insertOrderItem(deliveredOrder, product, "Popular", 1, "5.00", start.plusHours(5));
        UUID adminPickupOrder = insertOrder(null, "DELIVERED", start.plusHours(4), start.plusHours(5), "100.00", "ADMIN_PICKUP");
        insertOrderItem(adminPickupOrder, product, "Popular", 99, "9900.00", start.plusHours(5));
        UUID refundedOrder = insertOrder(normalUserId, "REFUNDED", start.plusHours(5), start.plusHours(6), "7.00");
        insertOrderItem(refundedOrder, product, "Popular", 50, "350.00", start.plusHours(6));
        insertOrder(null, "EXPIRED", start.plusHours(6), null, "3.00");
        insertOrder(null, "PENDING", start.plusHours(7), null, "7.00");
        insertOrder(null, "PAID", start.minusDays(10), start.minusDays(9), "99.00");
        insertVisit(today, 120, 20, now.minusMinutes(1));

        DashboardStatsResponse stats = dashboardService.getStats();

        assertEquals(2L, stats.todayOrders());
        assertEquals(new BigDecimal("15.00"), stats.todaySales());
        assertEquals(1L, stats.todayBuyers());
        assertEquals(1L, stats.todayNewUsers());
        assertEquals(1L, stats.totalUsers());
        assertEquals(1L, stats.pendingOrders());
        assertEquals(2L, stats.paidWaitingDelivery());
        assertEquals(1L, stats.deliveredToday());
        assertEquals(1L, stats.expiredToday());
        assertEquals(75.0, stats.todayPaymentSuccessRate());
        assertEquals(4L, stats.todayPaymentSuccessSampleCount());
        assertEquals(2L, stats.activeProducts());
        assertEquals(1L, stats.availableCardKeys());
        assertEquals(1L, stats.zeroStockProducts());
        assertEquals(2, stats.lowStockProducts().size());
        assertEquals(1, stats.topProducts().size());
        assertEquals(3L, stats.topProducts().get(0).quantity());
        assertEquals(new BigDecimal("15.00"), stats.topProducts().get(0).salesAmount());

        List<?> trend = dashboardService.getSalesTrend("daily", today.toString(), today.toString());
        assertEquals(1, trend.size());
        assertEquals(new BigDecimal("15.00"), ((java.util.Map<?, ?>) trend.get(0)).get("sales_amount"));
        assertEquals(start.plusHours(12), now);
    }

    @Test
    void dashboardUsesVisibleSpecAndUnifiedLocalInventorySnapshots() {
        LocalDate today = LocalDate.now(CLOCK);
        LocalDateTime start = today.atStartOfDay();
        UUID cardProduct = UUID.randomUUID();
        UUID manualProduct = UUID.randomUUID();
        UUID specProduct = UUID.randomUUID();
        UUID unlimitedProduct = UUID.randomUUID();
        UUID disabledProduct = UUID.randomUUID();
        UUID deletedProduct = UUID.randomUUID();
        insertProduct(cardProduct, "Card stock", 10, true, 0, start, "CARD_KEY", false);
        insertProduct(manualProduct, "Manual stock", 10, true, 0, start, "MANUAL", false);
        insertProduct(specProduct, "Plans", 10, true, 0, start, "CARD_KEY", true);
        insertProduct(unlimitedProduct, "Unlimited", 10, true, 0, start, "UNLIMITED", false);
        insertProduct(disabledProduct, "Disabled", 10, false, 0, start, "CARD_KEY", false);
        insertProduct(deletedProduct, "Deleted", 10, true, 1, start, "CARD_KEY", false);
        jdbcTemplate.update("INSERT INTO manual_stocks(product_id, spec_id, available_quantity, reserved_quantity) VALUES (?, NULL, 8, 0)",
                manualProduct);

        UUID monthSpec = insertSpec(specProduct, "Month card", true, 0);
        UUID yearSpec = insertSpec(specProduct, "Year card", true, 0);
        UUID lifetimeSpec = insertSpec(specProduct, "Lifetime", true, 0);
        UUID hiddenSpec = insertSpec(specProduct, "Hidden", false, 0);
        UUID deletedSpec = insertSpec(specProduct, "Deleted spec", true, 1);
        insertCard(cardProduct, "card-stock-1", "AVAILABLE", start.plusMinutes(1));
        insertCard(cardProduct, "card-stock-2", "AVAILABLE", start.plusMinutes(2));
        insertCard(cardProduct, "card-stock-3", "AVAILABLE", start.plusMinutes(3));
        insertCard(cardProduct, "card-stock-4", "AVAILABLE", start.plusMinutes(4));
        insertCard(cardProduct, "card-stock-5", "AVAILABLE", start.plusMinutes(5));
        for (int index = 0; index < 100; index++) {
            insertCard(specProduct, "lifetime-stock-" + index, "AVAILABLE", start.plusMinutes(8 + index), lifetimeSpec);
        }
        insertCard(specProduct, "hidden-stock", "AVAILABLE", start.plusMinutes(120), hiddenSpec);
        insertCard(specProduct, "deleted-spec-stock", "AVAILABLE", start.plusMinutes(121), deletedSpec);
        insertCard(disabledProduct, "disabled-stock", "AVAILABLE", start.plusMinutes(122));
        insertCard(deletedProduct, "deleted-stock", "AVAILABLE", start.plusMinutes(123));

        DashboardStatsResponse stats = dashboardService.getStats();

        assertEquals(4L, stats.activeProducts());
        assertEquals(4, stats.lowStockProducts().size());
        assertEquals(2L, stats.zeroStockProducts());
        assertEquals(1L, stats.lowStockProducts().stream().filter(item -> cardProduct.equals(item.productId())).count());
        assertEquals(1L, stats.lowStockProducts().stream().filter(item -> manualProduct.equals(item.productId())).count());
        assertEquals(2L, stats.lowStockProducts().stream().filter(item -> specProduct.equals(item.productId())).count());
        assertEquals(List.of(monthSpec, yearSpec), stats.lowStockProducts().stream()
                .filter(item -> specProduct.equals(item.productId()))
                .map(DashboardStatsResponse.LowStockProduct::specId).toList());
        assertEquals(0L, stats.lowStockProducts().stream()
                .filter(item -> unlimitedProduct.equals(item.productId())
                        || disabledProduct.equals(item.productId()) || deletedProduct.equals(item.productId()))
                .count());
    }

    @Test
    void postgresDistinctBuyerCountUsesUserSessionAndOrderFallback() {
        LocalDate today = LocalDate.now(CLOCK);
        LocalDateTime start = today.atStartOfDay();
        UUID registeredUserId = insertUser("registered", "registered@example.com", "USER", 0,
                start.plusHours(1));

        insertOrder(registeredUserId, null, "PAID", start.plusHours(1), start.plusHours(2), "1.00");
        insertOrder(registeredUserId, null, "DELIVERED", start.plusHours(3), start.plusHours(4), "2.00");

        insertOrder(null, "guest-session-a", "PAID", start.plusHours(5), start.plusHours(6), "3.00");
        insertOrder(null, "guest-session-a", "DELIVERED", start.plusHours(7), start.plusHours(8), "4.00");
        insertOrder(null, "guest-session-b", "PAID", start.plusHours(9), start.plusHours(10), "5.00");

        insertOrder(null, null, "PAID", start.plusHours(11), start.plusHours(12), "6.00");
        insertOrder(null, null, "DELIVERED", start.plusHours(13), start.plusHours(14), "7.00");
        insertOrder(null, "", "PAID", start.plusHours(15), start.plusHours(16), "8.00");

        assertEquals(6L, orderRepository.countDistinctBuyersBetween(start, start.plusDays(1)));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedDashboardClockConfiguration {
        @Bean
        @Primary
        Clock fixedDashboardClock() {
            return CLOCK;
        }
    }

    private UUID insertUser(String username, String email, String role, int deleted, LocalDateTime createdAt) {
        UUID id = UUID.randomUUID();
        Long userNo = "USER".equals(role) ? nextUserNo++ : null;
        UUID userLevelId = "USER".equals(role)
                ? jdbcTemplate.queryForObject("SELECT id FROM user_levels WHERE is_default = true", UUID.class)
                : null;
        jdbcTemplate.update("""
                INSERT INTO users(id, created_at, updated_at, username, email, password_hash, role, is_deleted,
                                  user_no, user_level_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, createdAt, createdAt, username, email, "hash", role, deleted, userNo,
                userLevelId);
        return id;
    }

    private void insertProduct(UUID id, String title, int threshold, boolean enabled, int deleted,
                               LocalDateTime createdAt) {
        insertProduct(id, title, threshold, enabled, deleted, createdAt, "CARD_KEY", false);
    }

    private void insertProduct(UUID id, String title, int threshold, boolean enabled, int deleted,
                               LocalDateTime createdAt, String stockMode, boolean specEnabled) {
        jdbcTemplate.update("""
                INSERT INTO products(id, created_at, updated_at, title, retail_price, cost_price, category_id,
                                     low_stock_threshold, is_enabled, is_deleted, stock_mode, spec_enabled)
                VALUES (?, ?, ?, ?, 10.00, 10.00, ?, ?, ?, ?, ?, ?)
                """, id, createdAt, createdAt, title, UUID.randomUUID(), threshold, enabled, deleted,
                stockMode, specEnabled);
    }

    private UUID insertSpec(UUID productId, String name, boolean visible, int deleted) {
        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(CLOCK);
        jdbcTemplate.update("""
                INSERT INTO product_specs(id, created_at, updated_at, product_id, name, cost_price, retail_price,
                                          is_visible, sort_order, is_deleted)
                VALUES (?, ?, ?, ?, ?, 1.00, 10.00, ?, 0, ?)
                """, id, now, now, productId, name, visible, deleted);
        return id;
    }

    private void insertCard(UUID productId, String content, String status, LocalDateTime createdAt) {
        insertCard(productId, content, status, createdAt, null);
    }

    private void insertCard(UUID productId, String content, String status, LocalDateTime createdAt, UUID specId) {
        jdbcTemplate.update("""
                INSERT INTO card_keys(id, created_at, updated_at, product_id, spec_id, content_parts,
                                      content_fingerprint, status)
                VALUES (?, ?, ?, ?, ?, jsonb_build_array(?), ?, ?)
                """, UUID.randomUUID(), createdAt, createdAt, productId, specId, content,
                CardContentParser.fingerprint(List.of(content)), status);
    }

    private UUID insertOrder(UUID userId, String status, LocalDateTime createdAt,
                             LocalDateTime paidAt, String amount) {
        return insertOrder(userId, null, status, createdAt, paidAt, amount, "NORMAL");
    }

    private UUID insertOrder(UUID userId, String status, LocalDateTime createdAt,
                             LocalDateTime paidAt, String amount, String orderType) {
        return insertOrder(userId, null, status, createdAt, paidAt, amount, orderType);
    }

    private UUID insertOrder(UUID userId, String sessionToken, String status,
                             LocalDateTime createdAt, LocalDateTime paidAt, String amount) {
        return insertOrder(userId, sessionToken, status, createdAt, paidAt, amount, "NORMAL");
    }

    private UUID insertOrder(UUID userId, String sessionToken, String status,
                             LocalDateTime createdAt, LocalDateTime paidAt, String amount, String orderType) {
        UUID id = UUID.randomUUID();
        LocalDateTime deliveredAt = "DELIVERED".equals(status) ? paidAt : null;
        String paymentMethod = "ADMIN_PICKUP".equals(orderType) ? null : "ONLINE";
        String paymentChannelCode = "ADMIN_PICKUP".equals(orderType) ? null : "dashboard-channel";
        jdbcTemplate.update("""
                INSERT INTO orders(id, created_at, updated_at, user_id, session_token, actual_amount, total_amount,
                                   status, order_type, payment_method, payment_channel_code, paid_at, delivered_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, createdAt, createdAt, userId, sessionToken,
                "ADMIN_PICKUP".equals(orderType) ? BigDecimal.ZERO : new BigDecimal(amount),
                new BigDecimal(amount), status, orderType, paymentMethod, paymentChannelCode, paidAt, deliveredAt);
        return id;
    }

    private void insertOrderItem(UUID orderId, UUID productId, String title, int quantity, String subtotal,
                                 LocalDateTime createdAt) {
        jdbcTemplate.update("""
                INSERT INTO order_items(id, created_at, updated_at, order_id, product_id, product_no, product_title,
                                        quantity, unit_price, cost_price_snapshot, retail_price_snapshot, pricing_source, subtotal)
                VALUES (?, ?, ?, ?, ?, (SELECT product_no FROM products WHERE id = ?), ?, ?, 5.00, 5.00, 5.00, 'RETAIL', ?)
                """, UUID.randomUUID(), createdAt, createdAt, orderId, productId, productId, title, quantity,
                new BigDecimal(subtotal));
    }

    private void insertVisit(LocalDate date, int pv, int uv, LocalDateTime createdAt) {
        jdbcTemplate.update("""
                INSERT INTO visit_stats(id, created_at, updated_at, visit_date, pv, uv)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), createdAt, createdAt, date, pv, uv);
    }
}
