package com.myshop.service.impl;

import com.myshop.constant.OrderStatus;
import com.myshop.constant.OrderType;
import com.myshop.entity.Order;
import com.myshop.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves that concurrent JPA inserts read the PostgreSQL-generated business number back immediately. */
@org.junit.jupiter.api.condition.EnabledIf("postgresAvailable")
@ExtendWith(SpringExtension.class)
@SpringBootTest
class OrderNumberConcurrencyIntegrationTest {

    private static final int ORDER_COUNT = 32;
    private static final long MAX_JS_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final ZoneId ORDER_NUMBER_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter ORDER_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    static final IntegrationPostgres POSTGRES = new IntegrationPostgres();
    static boolean postgresAvailable() { return POSTGRES.available(); }
    @org.junit.jupiter.api.AfterAll static void stopPostgres() { POSTGRES.close(); }

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("jwt.secret", () -> "order-number-integration-secret-with-at-least-64-bytes-and-random-looking-value-2026");
        registry.add("mail.enabled", () -> false);
        registry.add("bootstrap.admin.username", () -> "");
        registry.add("bootstrap.admin.email", () -> "");
        registry.add("bootstrap.admin.password", () -> "");
    }

    @BeforeEach
    void clearOrders() {
        jdbcTemplate.update("DELETE FROM after_sale_attachments");
        jdbcTemplate.update("DELETE FROM after_sale_requests");
        jdbcTemplate.update("DELETE FROM orders");
    }

    @Test
    void concurrentJpaOrderInsertsReceiveDistinctSafeShanghaiDateNumbers() throws Exception {
        CountDownLatch ready = new CountDownLatch(ORDER_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(ORDER_COUNT);
        List<Future<Long>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < ORDER_COUNT; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(20, TimeUnit.SECONDS));

                    Order order = new Order();
                    order.setTotalAmount(BigDecimal.ZERO.setScale(2));
                    order.setActualAmount(BigDecimal.ZERO.setScale(2));
                    order.setStatus(OrderStatus.PENDING);
                    order.setOrderType(OrderType.NORMAL);

                    // saveAndFlush is the same repository boundary used by the
                    // admin pickup path. @Generated(INSERT) must populate the
                    // entity before this method returns.
                    Order saved = orderRepository.saveAndFlush(order);
                    assertNotNull(saved.getOrderNo());
                    return saved.getOrderNo();
                }));
            }

            assertTrue(ready.await(20, TimeUnit.SECONDS));
            start.countDown();

            List<Long> orderNumbers = new ArrayList<>();
            String expectedDate = LocalDate.now(ORDER_NUMBER_ZONE).format(ORDER_DATE);
            for (Future<Long> future : futures) {
                long orderNumber = future.get(30, TimeUnit.SECONDS);
                String rendered = Long.toString(orderNumber);
                assertEquals(16, rendered.length());
                assertEquals(expectedDate, rendered.substring(0, 8));
                assertTrue(rendered.substring(8).matches("\\d{8}"));
                assertTrue(orderNumber <= MAX_JS_SAFE_INTEGER);
                assertTrue(orderNumber <= (long) (double) orderNumber,
                        "the generated number must round-trip through a JavaScript Number");
                orderNumbers.add(orderNumber);
            }

            assertEquals(ORDER_COUNT, orderNumbers.stream().distinct().count());
        } finally {
            executor.shutdownNow();
        }

        assertEquals(ORDER_COUNT, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders", Integer.class));
        assertEquals(ORDER_COUNT, jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT order_no) FROM orders", Integer.class));
        assertEquals(ORDER_COUNT, jdbcTemplate.queryForObject("""
                SELECT count(DISTINCT registry.order_no)
                FROM order_no_registry registry
                JOIN orders order_row ON order_row.order_no = registry.order_no
                """, Integer.class));
    }

    @Test
    void retriesWhenTheRandomCandidateIsAlreadyReserved() {
        long[] collidedCandidate = new long[1];
        Long generated = jdbcTemplate.execute((ConnectionCallback<Long>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT setseed(0.38141)");
                String date;
                long suffix;
                try (ResultSet random = statement.executeQuery("""
                        SELECT to_char(clock_timestamp() AT TIME ZONE 'Asia/Shanghai', 'YYYYMMDD'),
                               floor(random() * 100000000)::BIGINT
                        """)) {
                    assertTrue(random.next());
                    date = random.getString(1);
                    suffix = random.getLong(2);
                }
                collidedCandidate[0] = Long.parseLong(date + String.format(Locale.ROOT, "%08d", suffix));
                try (PreparedStatement reserve = connection.prepareStatement(
                        "INSERT INTO order_no_registry(order_no) VALUES (?) ON CONFLICT DO NOTHING")) {
                    reserve.setLong(1, collidedCandidate[0]);
                    reserve.executeUpdate();
                }

                statement.execute("SELECT setseed(0.38141)");
                UUID id = UUID.randomUUID();
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO orders(id, created_at, updated_at, total_amount, actual_amount, status, order_type)
                        VALUES (?, current_timestamp, current_timestamp, 0.00, 0.00, 'PENDING', 'NORMAL')
                        RETURNING order_no
                        """)) {
                    insert.setObject(1, id);
                    try (ResultSet result = insert.executeQuery()) {
                        assertTrue(result.next());
                        return result.getLong(1);
                    }
                }
            }
        });

        assertNotNull(generated);
        assertNotEquals(collidedCandidate[0], generated);
        assertTrue(generated <= MAX_JS_SAFE_INTEGER);
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM orders WHERE order_no = ?", Integer.class, generated));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM order_no_registry WHERE order_no = ?", Integer.class, generated));
    }
}
