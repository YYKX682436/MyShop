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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves that concurrent JPA inserts read the PostgreSQL-generated business number back immediately. */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@SpringBootTest
class OrderNumberConcurrencyIntegrationTest {

    private static final int ORDER_COUNT = 32;
    private static final long FIRST_ORDER_NO = 1_000_000_001L;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

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
        jdbcTemplate.update("DELETE FROM orders");
        // The restart value is a test constant. PostgreSQL's ALTER SEQUENCE
        // grammar does not accept a JDBC bind marker in this position.
        jdbcTemplate.execute("ALTER SEQUENCE order_no_sequence RESTART WITH " + FIRST_ORDER_NO);
    }

    @Test
    void concurrentJpaOrderInsertsReceiveDistinctNonNullIncreasingNumbers() throws Exception {
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
            for (Future<Long> future : futures) {
                orderNumbers.add(future.get(30, TimeUnit.SECONDS));
            }
            orderNumbers.sort(Long::compareTo);

            assertEquals(ORDER_COUNT, orderNumbers.stream().distinct().count());
            for (int index = 0; index < ORDER_COUNT; index++) {
                assertEquals(FIRST_ORDER_NO + index, orderNumbers.get(index));
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(ORDER_COUNT, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders", Integer.class));
        assertEquals(ORDER_COUNT, jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT order_no) FROM orders", Integer.class));
        assertEquals(FIRST_ORDER_NO + ORDER_COUNT - 1, jdbcTemplate.queryForObject(
                "SELECT max(order_no) FROM orders", Long.class));
        assertEquals(FIRST_ORDER_NO + ORDER_COUNT - 1, jdbcTemplate.queryForObject(
                "SELECT last_value FROM order_no_sequence", Long.class));
        assertEquals(FIRST_ORDER_NO + ORDER_COUNT, jdbcTemplate.queryForObject(
                "SELECT nextval('order_no_sequence')", Long.class));
    }
}
