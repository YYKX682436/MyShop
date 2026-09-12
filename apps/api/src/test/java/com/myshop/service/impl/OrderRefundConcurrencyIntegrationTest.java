package com.myshop.service.impl;

import com.myshop.constant.OrderStatus;
import com.myshop.constant.ErrorCode;
import com.myshop.context.RequestContext;
import com.myshop.exception.BusinessException;
import com.myshop.service.AdminOrderService;
import com.myshop.service.AutoFulfillmentService;
import com.myshop.service.FulfillmentService;
import com.myshop.service.OrderRefundService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.myshop.payment.PaymentProvider;
import org.junit.jupiter.api.AfterEach;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves the order lock and partial unique refund index together make refunds one-shot. */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@SpringBootTest
class OrderRefundConcurrencyIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OrderRefundService orderRefundService;

    @Autowired
    private AdminOrderService adminOrderService;

    @Autowired
    private PaymentConfirmationTransactionService paymentConfirmationTransactionService;

    @Autowired
    private FulfillmentService fulfillmentService;

    @MockitoBean
    private AutoFulfillmentService autoFulfillmentService;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("jwt.secret", () -> "refund-concurrency-integration-secret-with-at-least-64-bytes-2026");
        registry.add("mail.enabled", () -> false);
        registry.add("bootstrap.admin.username", () -> "");
        registry.add("bootstrap.admin.email", () -> "");
        registry.add("bootstrap.admin.password", () -> "");
    }

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM card_key_reclaims");
        jdbcTemplate.update("DELETE FROM balance_transactions");
        jdbcTemplate.update("DELETE FROM operation_logs");
        jdbcTemplate.update("DELETE FROM webhook_events");
        jdbcTemplate.update("DELETE FROM payment_attempts");
        jdbcTemplate.update("DELETE FROM card_keys");
        jdbcTemplate.update("DELETE FROM order_items");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM users");
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    @Test
    void concurrentRefundRequestsCreditOnceAndCreateOneRefundLedgerEntry() throws Exception {
        UUID operatorId = insertAdmin("refund-concurrency-admin");
        UUID userId = insertUser("refund-concurrency-buyer", "5.66");
        UUID orderId = insertPaidOrder(userId, "12.34");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<Outcome>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < 2; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    RequestContext.set(new RequestContext.UserInfo(
                            operatorId, "refund-concurrency-admin", "ADMIN"));
                    try {
                        orderRefundService.refund(orderId, "并发退款请求", true, false);
                        return new Outcome(true, null);
                    } catch (BusinessException exception) {
                        return new Outcome(false, exception.getCode());
                    } finally {
                        RequestContext.clear();
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            assertEquals(1, outcomes.stream().filter(Outcome::success).count());
            assertEquals(1, outcomes.stream()
                    .filter(outcome -> Integer.valueOf(ErrorCode.ORDER_ALREADY_REFUNDED).equals(outcome.errorCode()))
                    .count());
        } finally {
            executor.shutdownNow();
        }

        assertEquals(OrderStatus.REFUNDED.name(), scalar(
                "SELECT status FROM orders WHERE id = ?", String.class, orderId));
        assertMoney("18.00", scalar("SELECT balance FROM users WHERE id = ?", BigDecimal.class, userId));
        assertEquals(1, scalar("SELECT count(*) FROM balance_transactions WHERE order_id = ? AND type = 'REFUND'", Integer.class, orderId));
        assertEquals(1, scalar("SELECT count(*) FROM operation_logs WHERE action = 'order.refund' AND target_id = ?", Integer.class, orderId.toString()));
        assertEquals(1, scalar("SELECT count(*) FROM balance_transactions WHERE user_id = ?", Integer.class, userId));
    }

    @Test
    void aLedgerConstraintFailureRollsBackOrderBalanceAndAuditTogether() {
        UUID operatorId = insertAdmin("refund-rollback-admin");
        UUID userId = insertUser("refund-rollback-buyer", "5.66");
        UUID orderId = insertPaidOrder(userId, "12.34");
        jdbcTemplate.update("""
                INSERT INTO balance_transactions(user_id, type, amount, delta, balance_before,
                                                 balance_after, reason, operator_id, order_id)
                VALUES (?, 'REFUND', 12.34, 12.34, 5.66, 18.00, 'pre-existing test guard', ?, ?)
                """, userId, operatorId, orderId);

        RequestContext.set(new RequestContext.UserInfo(operatorId, "refund-rollback-admin", "ADMIN"));
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> orderRefundService.refund(orderId, "触发唯一约束回滚", true, false));

        assertEquals(OrderStatus.PAID.name(), scalar(
                "SELECT status FROM orders WHERE id = ?", String.class, orderId));
        assertMoney("5.66", scalar("SELECT balance FROM users WHERE id = ?", BigDecimal.class, userId));
        assertEquals(1, scalar("SELECT count(*) FROM balance_transactions WHERE order_id = ? AND type = 'REFUND'", Integer.class, orderId));
        assertEquals(0, scalar("SELECT count(*) FROM operation_logs WHERE action = 'order.refund' AND target_id = ?", Integer.class, orderId.toString()));
    }

    @Test
    void refundWithoutReclaimLeavesDeliveredAutomaticKeyAndItemUntouched() {
        UUID operatorId = insertAdmin("refund-no-reclaim-admin");
        UUID userId = insertUser("refund-no-reclaim-buyer", "0.00");
        DeliveredCardOrder fixture = insertDeliveredCardOrder(userId, 1);

        RequestContext.set(new RequestContext.UserInfo(operatorId, "refund-no-reclaim-admin", "ADMIN"));
        var response = orderRefundService.refund(fixture.orderId(), "不回收已发卡密", true, false);

        assertEquals(0, response.reclaimedCardKeyCount());
        assertEquals(OrderStatus.REFUNDED.name(), scalar(
                "SELECT status FROM orders WHERE id = ?", String.class, fixture.orderId()));
        assertEquals("DELIVERED", scalar(
                "SELECT fulfillment_status FROM order_items WHERE id = ?", String.class, fixture.itemId()));
        assertEquals("SOLD", scalar(
                "SELECT status FROM card_keys WHERE id = ?", String.class, fixture.cardKeyIds().get(0)));
        assertEquals(fixture.orderId(), scalar(
                "SELECT order_id FROM card_keys WHERE id = ?", UUID.class, fixture.cardKeyIds().get(0)));
        assertEquals(0, scalar("SELECT count(*) FROM card_key_reclaims WHERE order_id = ?",
                Integer.class, fixture.orderId()));
    }

    @Test
    void refundWithReclaimReleasesAllDeliveredAutomaticKeysAndKeepsImmutableProof() {
        UUID operatorId = insertAdmin("refund-reclaim-admin");
        UUID userId = insertUser("refund-reclaim-buyer", "0.00");
        DeliveredCardOrder fixture = insertDeliveredCardOrder(userId, 2);

        RequestContext.set(new RequestContext.UserInfo(operatorId, "refund-reclaim-admin", "ADMIN"));
        var response = orderRefundService.refund(fixture.orderId(), "确认卡密未使用", true, true);

        assertEquals(2, response.reclaimedCardKeyCount());
        assertEquals(OrderStatus.REFUNDED.name(), scalar(
                "SELECT status FROM orders WHERE id = ?", String.class, fixture.orderId()));
        assertEquals("CANCELLED", scalar(
                "SELECT fulfillment_status FROM order_items WHERE id = ?", String.class, fixture.itemId()));
        assertEquals(2, scalar("SELECT count(*) FROM card_keys WHERE id IN (?, ?) AND status = 'AVAILABLE' "
                        + "AND order_id IS NULL AND order_item_id IS NULL",
                Integer.class, fixture.cardKeyIds().get(0), fixture.cardKeyIds().get(1)));
        assertEquals(0, scalar("SELECT count(*) FROM card_keys WHERE order_id = ? OR order_item_id = ?",
                Integer.class, fixture.orderId(), fixture.itemId()));
        assertEquals(2, scalar("SELECT count(*) FROM card_key_reclaims WHERE order_id = ?",
                Integer.class, fixture.orderId()));
        assertEquals(0, scalar("SELECT count(*) FROM card_key_reclaims WHERE order_id = ? AND card_key_id IS NULL",
                Integer.class, fixture.orderId()));
        String audit = scalar("SELECT detail FROM operation_logs WHERE action = 'order.refund' AND target_id = ?",
                String.class, fixture.orderId().toString());
        assertTrue(audit.contains("回收卡密=是"));
        assertTrue(audit.contains("回收数量=2"));
        assertTrue(audit.contains("原因=确认卡密未使用"));
        assertTrue(!audit.contains("test-key"));
    }

    @Test
    void invalidReclaimRollsBackBeforeChangingRefundOrInventoryFacts() {
        UUID operatorId = insertAdmin("refund-invalid-reclaim-admin");
        UUID userId = insertUser("refund-invalid-reclaim-buyer", "0.00");
        DeliveredCardOrder fixture = insertDeliveredCardOrder(userId, 1);
        jdbcTemplate.update("UPDATE card_keys SET status = 'AVAILABLE' WHERE id = ?",
                fixture.cardKeyIds().get(0));

        RequestContext.set(new RequestContext.UserInfo(
                operatorId, "refund-invalid-reclaim-admin", "ADMIN"));
        org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> orderRefundService.refund(fixture.orderId(), "异常卡密状态", true, true));

        assertEquals(OrderStatus.DELIVERED.name(), scalar(
                "SELECT status FROM orders WHERE id = ?", String.class, fixture.orderId()));
        assertMoney("0.00", scalar("SELECT balance FROM users WHERE id = ?", BigDecimal.class, userId));
        assertEquals("AVAILABLE", scalar(
                "SELECT status FROM card_keys WHERE id = ?", String.class, fixture.cardKeyIds().get(0)));
        assertEquals(0, scalar("SELECT count(*) FROM card_key_reclaims WHERE order_id = ?",
                Integer.class, fixture.orderId()));
        assertEquals(0, scalar("SELECT count(*) FROM operation_logs WHERE target_id = ?",
                Integer.class, fixture.orderId().toString()));
    }

    @Test
    void refundAndFulfillmentRaceOnTheOrderFirstLockWithoutPartialFacts() throws Exception {
        UUID operatorId = insertAdmin("refund-fulfillment-race-admin");
        UUID userId = insertUser("refund-fulfillment-race-buyer", "0.00");
        PendingCardOrder fixture = insertPendingCardOrder(userId);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<Outcome> refund = executor.submit(() -> {
            ready.countDown();
            assertTrue(start.await(10, TimeUnit.SECONDS));
            RequestContext.set(new RequestContext.UserInfo(
                    operatorId, "refund-fulfillment-race-admin", "ADMIN"));
            try {
                orderRefundService.refund(fixture.orderId(), "退款与发货竞态", true, false);
                return new Outcome(true, null);
            } catch (BusinessException exception) {
                return new Outcome(false, exception.getCode());
            } finally {
                RequestContext.clear();
            }
        });
        Future<Map<String, Object>> fulfillment = executor.submit(() -> {
            ready.countDown();
            assertTrue(start.await(10, TimeUnit.SECONDS));
            return fulfillmentService.fulfillOrder(fixture.orderId());
        });
        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            Outcome refundOutcome = refund.get(30, TimeUnit.SECONDS);
            Map<String, Object> fulfillmentOutcome = fulfillment.get(30, TimeUnit.SECONDS);
            assertTrue(refundOutcome.success());
            assertTrue("REFUNDED".equals(fulfillmentOutcome.get("status"))
                    || "DELIVERED".equals(fulfillmentOutcome.get("status")));
        } finally {
            executor.shutdownNow();
        }

        assertEquals(OrderStatus.REFUNDED.name(), scalar(
                "SELECT status FROM orders WHERE id = ?", String.class, fixture.orderId()));
        String keyStatus = scalar("SELECT status FROM card_keys WHERE id = ?", String.class,
                fixture.cardKeyIds().get(0));
        String itemStatus = scalar("SELECT fulfillment_status FROM order_items WHERE id = ?", String.class,
                fixture.itemId());
        assertTrue(("AVAILABLE".equals(keyStatus) && "CANCELLED".equals(itemStatus))
                || ("SOLD".equals(keyStatus) && "DELIVERED".equals(itemStatus)));
        assertEquals(1, scalar("SELECT count(*) FROM balance_transactions WHERE order_id = ? AND type = 'REFUND'",
                Integer.class, fixture.orderId()));
    }

    @Test
    void manualConfirmationAndProviderCallbackCompleteOnePaymentAndTriggerFulfillmentOnce() throws Exception {
        UUID operatorId = insertAdmin("payment-race-admin");
        UUID userId = insertUser("payment-race-buyer", "0.00");
        UUID orderId = insertPendingOnlineOrder(userId, "10.00");
        UUID attemptId = insertPendingPaymentAttempt(orderId, "payment-race-merchant", "10.00");
        PaymentProvider.CallbackResult callback = new PaymentProvider.CallbackResult(
                "payment-race-event", "payment-race-merchant", "payment-race-provider-trade",
                com.myshop.payment.PaymentStatus.PAID, new BigDecimal("10.00"));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> manual = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                RequestContext.set(new RequestContext.UserInfo(
                        operatorId, "payment-race-admin", "ADMIN"));
                try {
                    adminOrderService.markPaid(orderId, "并发核验支付凭证", true);
                    return true;
                } catch (BusinessException exception) {
                    return false;
                } finally {
                    RequestContext.clear();
                }
            });
            Future<String> provider = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return paymentConfirmationTransactionService.confirmPaid(attemptId, callback);
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            boolean manualSucceeded = manual.get(30, TimeUnit.SECONDS);
            String providerResult = provider.get(30, TimeUnit.SECONDS);
            assertTrue(manualSucceeded || "SUCCESS".equals(providerResult));
        } finally {
            executor.shutdownNow();
        }

        assertEquals(OrderStatus.PAID.name(), scalar(
                "SELECT status FROM orders WHERE id = ?", String.class, orderId));
        assertEquals("PAID", scalar(
                "SELECT status FROM payment_attempts WHERE id = ?", String.class, attemptId));
        org.mockito.Mockito.verify(autoFulfillmentService, org.mockito.Mockito.timeout(5000).times(1))
                .trigger(orderId);
        assertTrue(scalar("SELECT count(*) FROM operation_logs WHERE target_id = ?", Integer.class,
                orderId.toString()) <= 1);
        assertTrue(scalar("SELECT count(*) FROM webhook_events WHERE order_id = ?", Integer.class,
                orderId) <= 1);
    }

    private UUID insertAdmin(String username) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users(id, username, email, password_hash, role, is_deleted)
                VALUES (?, ?, ?, 'test-hash', 'ADMIN', 0)
                """, id, username, username + "@example.com");
        return id;
    }

    private UUID insertUser(String username, String balance) {
        UUID id = UUID.randomUUID();
        UUID defaultLevelId = jdbcTemplate.queryForObject(
                "SELECT id FROM user_levels WHERE is_default = true", UUID.class);
        jdbcTemplate.update("""
                INSERT INTO users(id, username, email, password_hash, role, user_no,
                                  user_level_id, balance, is_deleted)
                VALUES (?, ?, ?, 'test-hash', 'USER', ?, ?, ?, 0)
                """, id, username, username + "@example.com", Math.abs(username.hashCode()) + 10000L,
                defaultLevelId, new BigDecimal(balance));
        return id;
    }

    private UUID insertPaidOrder(UUID userId, String amount) {
        UUID orderId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO orders(id, user_id, total_amount, actual_amount, status, order_type,
                                   payment_method, payment_channel_code, paid_at)
                VALUES (?, ?, ?, ?, 'PAID', 'NORMAL', 'ONLINE', 'refund-test-channel', CURRENT_TIMESTAMP)
                """, orderId, userId, new BigDecimal(amount), new BigDecimal(amount));
        return orderId;
    }

    private UUID insertPendingOnlineOrder(UUID userId, String amount) {
        UUID orderId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO orders(id, user_id, total_amount, actual_amount, status, order_type,
                                   payment_method, payment_channel_code, expires_at)
                VALUES (?, ?, ?, ?, 'PENDING', 'NORMAL', 'ONLINE', 'payment-race-channel', CURRENT_TIMESTAMP + INTERVAL '10 minutes')
                """, orderId, userId, new BigDecimal(amount), new BigDecimal(amount));
        return orderId;
    }

    private UUID insertPendingPaymentAttempt(UUID orderId, String merchantTradeNo, String amount) {
        UUID attemptId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO payment_attempts(id, order_id, channel_id, channel_code_snapshot,
                                             provider_type, payment_type, provider_config_snapshot,
                                             merchant_trade_no, amount, status, next_action, expires_at)
                VALUES (?, ?, ?, 'payment-race-channel', 'epay', 'wxpay', '{}', ?, ?, 'PENDING', '{}',
                        CURRENT_TIMESTAMP + INTERVAL '10 minutes')
                """, attemptId, orderId, UUID.randomUUID(), merchantTradeNo, new BigDecimal(amount));
        return attemptId;
    }

    private DeliveredCardOrder insertDeliveredCardOrder(UUID userId, int keyCount) {
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO orders(id, user_id, total_amount, actual_amount, status, order_type,
                                   payment_method, payment_channel_code, paid_at)
                VALUES (?, ?, ?, ?, 'DELIVERED', 'NORMAL', 'ONLINE', 'refund-fixture-channel', CURRENT_TIMESTAMP)
                """, orderId, userId, BigDecimal.valueOf(keyCount), BigDecimal.valueOf(keyCount));
        jdbcTemplate.update("""
                INSERT INTO order_items(id, order_id, product_id, product_no, product_title, quantity,
                                        unit_price, cost_price_snapshot, retail_price_snapshot, pricing_source,
                                        subtotal, delivery_type, customer_inputs, stock_mode, fulfillment_status,
                                        delivered_at, delivery_notified_at, manual_stock_reserved)
                VALUES (?, ?, ?, 1, 'Refund fixture product', ?, 1.00, 0.10, 1.00, 'RETAIL',
                        ?, 'AUTO', '[]'::jsonb, 'CARD_KEY', 'DELIVERED',
                        CURRENT_TIMESTAMP - INTERVAL '5 minutes', CURRENT_TIMESTAMP - INTERVAL '4 minutes', 0)
                """, itemId, orderId, productId, keyCount, BigDecimal.valueOf(keyCount));
        List<UUID> keyIds = new ArrayList<>();
        for (int index = 0; index < keyCount; index++) {
            UUID keyId = UUID.randomUUID();
            keyIds.add(keyId);
            jdbcTemplate.update("""
                    INSERT INTO card_keys(id, product_id, content_parts, content_fingerprint, status,
                                          order_id, order_item_id, sold_at)
                    VALUES (?, ?, '["test-key"]'::jsonb, ?, 'SOLD', ?, ?,
                            CURRENT_TIMESTAMP - INTERVAL '10 minutes')
                    """, keyId, productId, "refund-fixture-" + keyId,
                    orderId, itemId);
        }
        return new DeliveredCardOrder(orderId, itemId, keyIds);
    }

    private PendingCardOrder insertPendingCardOrder(UUID userId) {
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO orders(id, user_id, total_amount, actual_amount, status, order_type,
                                   payment_method, payment_channel_code, paid_at)
                VALUES (?, ?, 1.00, 1.00, 'PAID', 'NORMAL', 'ONLINE', 'refund-race-channel', CURRENT_TIMESTAMP)
                """, orderId, userId);
        jdbcTemplate.update("""
                INSERT INTO order_items(id, order_id, product_id, product_no, product_title, quantity,
                                        unit_price, cost_price_snapshot, retail_price_snapshot, pricing_source,
                                        subtotal, delivery_type, customer_inputs, stock_mode, fulfillment_status,
                                        manual_stock_reserved)
                VALUES (?, ?, ?, 1, 'Refund race product', 1, 1.00, 0.10, 1.00, 'RETAIL',
                        1.00, 'AUTO', '[]'::jsonb, 'CARD_KEY', 'PENDING', 0)
                """, itemId, orderId, productId);
        jdbcTemplate.update("""
                INSERT INTO card_keys(id, product_id, content_parts, content_fingerprint, status)
                VALUES (?, ?, '["race-key"]'::jsonb, ?, 'AVAILABLE')
                """, keyId, productId, "refund-race-" + keyId);
        return new PendingCardOrder(orderId, itemId, List.of(keyId));
    }

    private <T> T scalar(String sql, Class<T> type, Object... args) {
        return jdbcTemplate.queryForObject(sql, type, args);
    }

    private void assertMoney(String expected, BigDecimal actual) {
        assertTrue(actual != null);
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    private record Outcome(boolean success, Integer errorCode) {
    }

    private record DeliveredCardOrder(UUID orderId, UUID itemId, List<UUID> cardKeyIds) {
    }

    private record PendingCardOrder(UUID orderId, UUID itemId, List<UUID> cardKeyIds) {
    }
}
