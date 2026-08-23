package com.myshop.service.impl;

import com.myshop.exception.BusinessException;
import com.myshop.entity.Product;
import com.myshop.payment.EpayPaymentProvider;
import com.myshop.payment.PaymentProvider;
import com.myshop.service.AutoFulfillmentService;
import com.myshop.service.DeliverService;
import com.myshop.service.OrderService;
import com.myshop.service.WebhookService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

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

    @Autowired
    private OrderService orderService;

    @Autowired
    private WebhookService webhookService;

    @Autowired
    private AutoFulfillmentService autoFulfillmentService;

    @MockitoBean
    private com.myshop.service.EmailService emailService;

    @MockitoBean
    private EpayPaymentProvider epayPaymentProvider;

    @BeforeEach
    void configureProvider() {
        when(epayPaymentProvider.id()).thenReturn("epay");
        when(epayPaymentProvider.paymentTypes()).thenReturn(List.of("alipay", "wechat"));
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("jwt.secret", () -> "integration-test-secret-with-at-least-64-bytes-and-random-looking-value-2026");
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

    @Test
    void verifiedCallbackAutomaticallyDeliversWithoutBrowserRequest() {
        UUID orderId = insertPendingOrder("auto-channel");
        UUID productId = UUID.randomUUID();
        insertOrderItem(orderId, productId);
        insertCard(productId, "auto-card");
        insertPaymentChannel("auto-channel");
        configureSuccessfulCallback("auto-trade");

        assertEquals("SUCCESS", webhookService.processEpayCallback(callback(orderId, "auto-trade")));
        assertEquals("DELIVERED", scalar("select status from orders where id = ?", String.class, orderId));
        assertEquals(1, number("select count(*) from card_keys where order_id = ? and status = 'SOLD'", orderId));

        assertEquals("SUCCESS", webhookService.processEpayCallback(callback(orderId, "auto-trade")));
        assertEquals(1, number("select count(*) from card_keys where order_id = ?", orderId));
    }

    @Test
    void shortageKeepsPaidFactAndRetryDeliversAfterStockIsRestored() {
        UUID orderId = insertPendingOrder("retry-channel");
        UUID productId = UUID.randomUUID();
        insertOrderItem(orderId, productId);
        insertPaymentChannel("retry-channel");
        configureSuccessfulCallback("retry-trade");

        assertEquals("SUCCESS", webhookService.processEpayCallback(callback(orderId, "retry-trade")));
        assertEquals("PAID", scalar("select status from orders where id = ?", String.class, orderId));
        assertEquals(0, number("select count(*) from card_keys where order_id = ?", orderId));

        insertCard(productId, "retry-card");
        autoFulfillmentService.retryPendingPaidOrders();
        assertEquals("DELIVERED", scalar("select status from orders where id = ?", String.class, orderId));
        assertEquals(1, number("select count(*) from card_keys where order_id = ? and status = 'SOLD'", orderId));
    }

    @Test
    void guestIdempotencyReplayReturnsSameOrderAndRecoverableToken() {
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        insertCard(productId, "idempotent-card");
        insertPaymentChannel("idempotent-channel");
        when(epayPaymentProvider.create(any(PaymentProvider.PaymentRequest.class)))
                .thenReturn(new PaymentProvider.PaymentResult("idempotent-trade", "https://pay.example/idempotent", null));

        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("product_id", productId.toString());
        request.put("quantity", 1);
        request.put("email", "buyer@example.com");
        request.put("payment_method", "idempotent-channel");
        request.put("idempotency_key", "guest-idempotency-key");
        request.put("idempotency_fingerprint", "fingerprint-1");

        Map<String, Object> first = orderService.createDirectOrder(request, null, "203.0.113.10", "guest-session-a");
        Map<String, Object> second = orderService.createDirectOrder(request, null, "203.0.113.10", "guest-session-a");
        Map<?, ?> firstOrder = (Map<?, ?>) first.get("order");
        Map<?, ?> secondOrder = (Map<?, ?>) second.get("order");

        assertEquals(firstOrder.get("id"), secondOrder.get("id"));
        assertEquals(first.get("lookup_token"), second.get("lookup_token"));
        assertEquals(1, number("select count(*) from orders where idempotency_key = ?", "guest-idempotency-key"));

        Map<String, Object> differentSession = new java.util.LinkedHashMap<>(request);
        assertThrows(BusinessException.class,
                () -> orderService.createDirectOrder(differentSession, null, "203.0.113.11", "guest-session-b"));
        assertEquals(1, number("select count(*) from orders where idempotency_key = ?", "guest-idempotency-key"));
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

    private UUID insertPendingOrder(String paymentMethod) {
        UUID orderId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into orders (id, created_at, updated_at, actual_amount, total_amount, status,
                    order_type, payment_method, expires_at)
                values (?, current_timestamp, current_timestamp, 10.00, 10.00, 'PENDING', 'DIRECT', ?,
                    current_timestamp + interval '15 minutes')
                """, orderId, paymentMethod);
        return orderId;
    }

    private void insertProduct(UUID productId) {
        UUID categoryId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into product_categories (id, created_at, updated_at, name, sort_order, is_deleted)
                values (?, current_timestamp, current_timestamp, ?, 0, 0)
                """, categoryId, "Integration category " + productId);
        jdbcTemplate.update("""
                insert into products (id, created_at, updated_at, title, base_price, category_id,
                    delivery_type, is_enabled, is_deleted)
                values (?, current_timestamp, current_timestamp, 'Integration product', 10.00, ?,
                    'AUTO', true, 0)
                """, productId, categoryId);
    }

    private void insertPaymentChannel(String channelCode) {
        jdbcTemplate.update("""
                insert into payment_channels (id, created_at, updated_at, channel_code, channel_name,
                    provider_type, payment_type, config_data, is_enabled, sort_order, is_deleted)
                values (?, current_timestamp, current_timestamp, ?, ?, 'epay', 'alipay', ?, true, 0, 0)
                """, UUID.randomUUID(), channelCode, channelCode,
                "{\"pid\":\"pid\",\"key\":\"secret\",\"api_url\":\"https://pay.example\",\"notify_url\":\"https://shop.example/notify\",\"return_url\":\"https://shop.example/return\"}");
    }

    private void configureSuccessfulCallback(String tradeNo) {
        when(epayPaymentProvider.verifyCallback(any(PaymentProvider.CallbackRequest.class))).thenReturn(true);
        when(epayPaymentProvider.query(any(PaymentProvider.PaymentQueryRequest.class)))
                .thenReturn(new PaymentProvider.PaymentQueryResult("TRADE_SUCCESS", "10.00", tradeNo));
    }

    private Map<String, String> callback(UUID orderId, String tradeNo) {
        Map<String, String> params = new java.util.LinkedHashMap<>();
        params.put("trade_no", tradeNo);
        params.put("out_trade_no", orderId.toString());
        params.put("trade_status", "TRADE_SUCCESS");
        params.put("money", "10.00");
        params.put("sign", "valid-signature");
        return params;
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
