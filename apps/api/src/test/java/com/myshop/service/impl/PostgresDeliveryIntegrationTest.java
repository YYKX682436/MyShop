package com.myshop.service.impl;

import com.myshop.exception.BusinessException;
import com.myshop.service.DeliverService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@SpringBootTest
class PostgresDeliveryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DeliverService deliverService;

    @MockitoBean
    private com.myshop.service.EmailService emailService;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("jwt.secret", () -> "integration-test-secret-with-enough-length");
        registry.add("mail.enabled", () -> false);
        registry.add("bootstrap.admin.username", () -> "");
        registry.add("bootstrap.admin.email", () -> "");
        registry.add("bootstrap.admin.password", () -> "");
    }

    @Test
    void shortageRollsBackAllEarlierCardPoolMutations() {
        UUID orderId = insertOrder("rollback-token");
        UUID firstProductId = UUID.randomUUID();
        UUID secondProductId = UUID.randomUUID();
        UUID firstItemId = insertOrderItem(orderId, firstProductId);
        insertOrderItem(orderId, secondProductId);
        UUID cardId = insertCard(firstProductId, "rollback-card");

        Map<String, Object> request = request(orderId, "rollback-token");
        assertThrows(BusinessException.class, () -> deliverService.deliverOrders(request, null));

        assertEquals("PAID", scalar("select status from orders where id = ?", String.class, orderId));
        assertEquals("AVAILABLE", scalar("select status from card_keys where id = ?", String.class, cardId));
        assertEquals(0, number("select count(*) from card_keys where order_id = ?", orderId));
        assertEquals(1, number("select count(*) from order_items where id = ? and order_id = ?", firstItemId, orderId));
    }

    @Test
    void concurrentDeliveryOfOneOrderAllocatesExactlyOnce() throws Exception {
        UUID orderId = insertOrder("concurrent-token");
        UUID productId = UUID.randomUUID();
        insertOrderItem(orderId, productId);
        insertCard(productId, "concurrent-card");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<List<?>> first = executor.submit(() -> deliverAfter(start, ready, orderId, "concurrent-token"));
            Future<List<?>> second = executor.submit(() -> deliverAfter(start, ready, orderId, "concurrent-token"));
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();

            assertEquals("DELIVERED", ((Map<?, ?>) first.get().get(0)).get("status"));
            assertEquals("DELIVERED", ((Map<?, ?>) second.get().get(0)).get("status"));
        } finally {
            executor.shutdownNow();
        }

        assertEquals("DELIVERED", scalar("select status from orders where id = ?", String.class, orderId));
        assertEquals(1, number("select count(*) from card_keys where order_id = ? and status = 'SOLD'", orderId));
    }

    private List<?> deliverAfter(CountDownLatch start, CountDownLatch ready,
                                 UUID orderId, String lookupToken) throws Exception {
        ready.countDown();
        start.await();
        return deliverService.deliverOrders(request(orderId, lookupToken), null);
    }

    private Map<String, Object> request(UUID orderId, String lookupToken) {
        return Map.of("orders", List.of(Map.of(
                "order_id", orderId.toString(),
                "lookup_token", lookupToken)));
    }

    private UUID insertOrder(String lookupToken) {
        UUID orderId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into orders (id, created_at, updated_at, actual_amount, total_amount, status,
                    order_type, lookup_token_hash)
                values (?, current_timestamp, current_timestamp, 10.00, 10.00, 'PAID', 'DIRECT', ?)
                """, orderId, com.myshop.utils.LookupTokens.hash(lookupToken));
        return orderId;
    }

    private UUID insertOrderItem(UUID orderId, UUID productId) {
        UUID itemId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into order_items (id, created_at, updated_at, order_id, product_id,
                    product_title, quantity, unit_price, subtotal)
                values (?, current_timestamp, current_timestamp, ?, ?, 'Integration product', 1, 10.00, 10.00)
                """, itemId, orderId, productId);
        return itemId;
    }

    private UUID insertCard(UUID productId, String content) {
        UUID cardId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into card_keys (id, created_at, updated_at, product_id, content, status)
                values (?, current_timestamp, current_timestamp, ?, ?, 'AVAILABLE')
                """, cardId, productId, content);
        return cardId;
    }

    private int number(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    private <T> T scalar(String sql, Class<T> type, Object... args) {
        return jdbcTemplate.queryForObject(sql, type, args);
    }
}
