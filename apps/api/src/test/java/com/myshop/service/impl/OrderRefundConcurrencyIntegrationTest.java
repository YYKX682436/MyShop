package com.myshop.service.impl;

import com.myshop.constant.OrderStatus;
import com.myshop.constant.ErrorCode;
import com.myshop.context.RequestContext;
import com.myshop.exception.BusinessException;
import com.myshop.service.AdminOrderService;
import com.myshop.service.AdminCardKeyService;
import com.myshop.service.AutoFulfillmentService;
import com.myshop.service.FulfillmentService;
import com.myshop.service.OrderRefundService;
import com.myshop.service.SupportTimelineService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.myshop.payment.PaymentProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

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
@EnabledIf(value = "postgresAvailable", disabledReason = "PostgreSQL test database or Docker required")
@ExtendWith(SpringExtension.class)
@SpringBootTest
class OrderRefundConcurrencyIntegrationTest {

    static final IntegrationPostgres POSTGRES = new IntegrationPostgres();

    static boolean postgresAvailable() { return POSTGRES.available(); }

    @AfterAll
    static void stopPostgres() { POSTGRES.close(); }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OrderRefundService orderRefundService;

    @Autowired
    private com.myshop.service.SupportService supportService;

    @Autowired
    private SupportTimelineService supportTimeline;

    @Autowired
    private AdminOrderService adminOrderService;

    @Autowired
    private AdminCardKeyService adminCardKeyService;

    @Autowired
    private PaymentConfirmationTransactionService paymentConfirmationTransactionService;

    @Autowired
    private FulfillmentService fulfillmentService;

    @Autowired
    private SubsiteSettlementService subsiteSettlementService;

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
        jdbcTemplate.update("DELETE FROM support_system_events");
        jdbcTemplate.update("DELETE FROM support_read_states");
        jdbcTemplate.update("DELETE FROM support_attachments");
        jdbcTemplate.update("DELETE FROM support_activity");
        jdbcTemplate.update("DELETE FROM support_messages");
        jdbcTemplate.update("DELETE FROM support_conversations");
        jdbcTemplate.update("DELETE FROM card_key_reclaims");
        jdbcTemplate.update("DELETE FROM balance_transactions");
        jdbcTemplate.update("DELETE FROM operation_logs");
        jdbcTemplate.update("DELETE FROM webhook_events");
        jdbcTemplate.update("DELETE FROM payment_attempts");
        jdbcTemplate.update("DELETE FROM card_keys");
        jdbcTemplate.update("DELETE FROM order_items");
        jdbcTemplate.update("DELETE FROM after_sale_attachments");
        jdbcTemplate.update("DELETE FROM after_sale_requests");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("UPDATE users SET subsite_id = NULL WHERE subsite_id IS NOT NULL");
        jdbcTemplate.update("DELETE FROM subsite_opening_records");
        jdbcTemplate.update("DELETE FROM subsites");
        jdbcTemplate.update("DELETE FROM users");
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    @Test
    void manualDeliverySettlesSubsiteProfitOnlyOnce() {
        UUID buyer = insertUser("subsite-manual-buyer", "0.00");
        UUID owner = insertUser("subsite-manual-owner", "0.00");
        UUID site = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO subsites(id, owner_id, prefix, name) VALUES (?, ?, ?, ?)", site, owner, "manual-test", "Manual");
        PendingCardOrder fixture = insertPendingCardOrder(buyer);
        jdbcTemplate.update("UPDATE orders SET subsite_id=?, subsite_owner_id_snapshot=?, subsite_profit=5.00, total_amount=15.00, actual_amount=15.00 WHERE id=?", site, owner, fixture.orderId());
        jdbcTemplate.update("UPDATE order_items SET unit_price=15.00, subtotal=15.00, subsite_supply_price_snapshot=10.00, delivery_type='MANUAL', stock_mode='UNLIMITED', fulfillment_status='WAITING_MANUAL' WHERE id=?", fixture.itemId());
        fulfillmentService.completeManualItems(fixture.orderId(), List.of(new FulfillmentService.ManualFulfillmentItem(fixture.itemId(), "complete")));
        org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class, () -> fulfillmentService.completeManualItems(fixture.orderId(), List.of(new FulfillmentService.ManualFulfillmentItem(fixture.itemId(), "again"))));
        assertMoney("5.00", scalar("SELECT balance FROM users WHERE id=?", BigDecimal.class, owner));
        assertEquals(1, scalar("SELECT count(*) FROM balance_transactions WHERE order_id=? AND type='SUBSITE_PROFIT'", Integer.class, fixture.orderId()));
    }

    @Test
    void manualDeliveryWithFullBalanceRemainsDeliveredAndPending() {
        UUID buyer = insertUser("manual-full-buyer", "0.00");
        UUID owner = insertUser("manual-full-owner", "9999999999.99");
        UUID site = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO subsites(id, owner_id, prefix, name) VALUES (?, ?, 'manual-full', 'Manual full')", site, owner);
        PendingCardOrder fixture = insertPendingCardOrder(buyer);
        jdbcTemplate.update("UPDATE orders SET subsite_id=?, subsite_owner_id_snapshot=?, subsite_profit=5.00, total_amount=15.00, actual_amount=15.00 WHERE id=?", site, owner, fixture.orderId());
        jdbcTemplate.update("UPDATE order_items SET unit_price=15.00, subtotal=15.00, subsite_supply_price_snapshot=10.00, delivery_type='MANUAL', stock_mode='UNLIMITED', fulfillment_status='WAITING_MANUAL' WHERE id=?", fixture.itemId());
        fulfillmentService.completeManualItems(fixture.orderId(), List.of(new FulfillmentService.ManualFulfillmentItem(fixture.itemId(), "complete")));
        assertEquals("DELIVERED", scalar("SELECT status FROM orders WHERE id=?", String.class, fixture.orderId()));
        assertEquals("PENDING", scalar("SELECT subsite_profit_state FROM orders WHERE id=?", String.class, fixture.orderId()));
        jdbcTemplate.update("UPDATE users SET balance=9999999994.99 WHERE id=?", owner);
        subsiteSettlementService.retryPendingProfit();
        assertEquals("SETTLED", scalar("SELECT subsite_profit_state FROM orders WHERE id=?", String.class, fixture.orderId()));
        assertEquals(1, scalar("SELECT count(*) FROM balance_transactions WHERE order_id=? AND type='SUBSITE_PROFIT'", Integer.class, fixture.orderId()));
    }

    @Test
    void subsiteDeliverySettlesOnceAndInsufficientRefundRollsBack() {
        UUID operatorId = insertAdmin("subsite-refund-admin");
        UUID buyerId = insertUser("subsite-refund-buyer", "0.00");
        UUID ownerId = insertUser("subsite-refund-owner", "0.00");
        UUID siteId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO subsites(id, owner_id, prefix, name) VALUES (?, ?, ?, ?)",
                siteId, ownerId, "refund-test", "Refund test");
        PendingCardOrder fixture = insertPendingCardOrder(buyerId);
        jdbcTemplate.update("UPDATE orders SET subsite_id=?, subsite_owner_id_snapshot=?, subsite_profit=5.00, total_amount=15.00, actual_amount=15.00 WHERE id=?",
                siteId, ownerId, fixture.orderId());
        jdbcTemplate.update("UPDATE order_items SET unit_price=15.00, subtotal=15.00, subsite_supply_price_snapshot=10.00 WHERE id=?", fixture.itemId());
        fulfillmentService.fulfillOrder(fixture.orderId());
        fulfillmentService.fulfillOrder(fixture.orderId());
        assertMoney("5.00", scalar("SELECT balance FROM users WHERE id = ?", BigDecimal.class, ownerId));
        assertEquals(1, scalar("SELECT count(*) FROM balance_transactions WHERE order_id=? AND type='SUBSITE_PROFIT'", Integer.class, fixture.orderId()));
        jdbcTemplate.update("UPDATE users SET balance=1.00 WHERE id=?", ownerId);
        RequestContext.set(new RequestContext.UserInfo(operatorId, "subsite-refund-admin", "ADMIN"));
        BusinessException failure = org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> orderRefundService.refund(fixture.orderId(), "refund", true, false));
        assertTrue(failure.getMessage().contains("4.00"));
        assertMoney("1.00", scalar("SELECT balance FROM users WHERE id = ?", BigDecimal.class, ownerId));
        assertMoney("0.00", scalar("SELECT balance FROM users WHERE id = ?", BigDecimal.class, buyerId));
        assertEquals("DELIVERED", scalar("SELECT status FROM orders WHERE id=?", String.class, fixture.orderId()));
        assertEquals(0, scalar("SELECT count(*) FROM balance_transactions WHERE order_id=? AND type IN ('REFUND','SUBSITE_PROFIT_REVERSAL')", Integer.class, fixture.orderId()));
        assertEquals(0, scalar("SELECT count(*) FROM support_activity WHERE source_type='ORDER_REFUND' AND source_id=?", Integer.class, fixture.orderId()));
        jdbcTemplate.update("UPDATE users SET balance=5.00 WHERE id=?", ownerId);
        orderRefundService.refund(fixture.orderId(), "refund", true, false);
        assertMoney("0.00", scalar("SELECT balance FROM users WHERE id = ?", BigDecimal.class, ownerId));
        assertMoney("15.00", scalar("SELECT balance FROM users WHERE id = ?", BigDecimal.class, buyerId));
        assertEquals(1, scalar("SELECT count(*) FROM balance_transactions WHERE order_id=? AND type='SUBSITE_PROFIT_REVERSAL'", Integer.class, fixture.orderId()));
        assertEquals(1, scalar("SELECT count(*) FROM support_activity WHERE source_type='ORDER_REFUND' AND source_id=? AND notify_customer", Integer.class, fixture.orderId()));
    }

    @Test
    void fullOwnerBalanceDoesNotBlockDeliveryAndRetryCreditsExactlyOnce() {
        UUID buyer = insertUser("pending-capacity-buyer", "0.00");
        UUID owner = insertUser("pending-capacity-owner", "9999999999.99");
        UUID site = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO subsites(id, owner_id, prefix, name) VALUES (?, ?, 'capacity', 'Capacity')", site, owner);
        PendingCardOrder fixture = insertPendingCardOrder(buyer);
        jdbcTemplate.update("UPDATE orders SET subsite_id=?, subsite_owner_id_snapshot=?, subsite_profit=5.00, total_amount=15.00, actual_amount=15.00 WHERE id=?", site, owner, fixture.orderId());
        jdbcTemplate.update("UPDATE order_items SET unit_price=15.00, subtotal=15.00, subsite_supply_price_snapshot=10.00 WHERE id=?", fixture.itemId());
        fulfillmentService.fulfillOrder(fixture.orderId());
        assertEquals("DELIVERED", scalar("SELECT status FROM orders WHERE id=?", String.class, fixture.orderId()));
        assertEquals("PENDING", scalar("SELECT subsite_profit_state FROM orders WHERE id=?", String.class, fixture.orderId()));
        assertMoney("9999999999.99", scalar("SELECT balance FROM users WHERE id=?", BigDecimal.class, owner));
        assertEquals(0, scalar("SELECT count(*) FROM balance_transactions WHERE order_id=? AND type='SUBSITE_PROFIT'", Integer.class, fixture.orderId()));
        jdbcTemplate.update("UPDATE users SET balance=9999999994.99 WHERE id=?", owner);
        // A scheduled scan also recovers a record left pending by a process restart.
        subsiteSettlementService.retryPendingProfit();
        subsiteSettlementService.retryPendingProfit();
        assertEquals("SETTLED", scalar("SELECT subsite_profit_state FROM orders WHERE id=?", String.class, fixture.orderId()));
        assertMoney("9999999999.99", scalar("SELECT balance FROM users WHERE id=?", BigDecimal.class, owner));
        assertEquals(1, scalar("SELECT count(*) FROM balance_transactions WHERE order_id=? AND type='SUBSITE_PROFIT'", Integer.class, fixture.orderId()));
    }

    @Test
    void failedCreditTransactionLeavesDeliveryAndPendingRecordRecoverable() {
        UUID buyer = insertUser("credit-failure-buyer", "0.00");
        UUID owner = insertUser("credit-failure-owner", "0.00");
        UUID site = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO subsites(id, owner_id, prefix, name) VALUES (?, ?, 'credit-failure', 'Credit failure')", site, owner);
        PendingCardOrder fixture = insertPendingCardOrder(buyer);
        jdbcTemplate.update("UPDATE orders SET subsite_id=?, subsite_owner_id_snapshot=?, subsite_profit=5.00, total_amount=15.00, actual_amount=15.00 WHERE id=?", site, owner, fixture.orderId());
        jdbcTemplate.update("UPDATE order_items SET unit_price=15.00, subtotal=15.00, subsite_supply_price_snapshot=10.00 WHERE id=?", fixture.itemId());
        jdbcTemplate.execute("CREATE FUNCTION test_fail_profit_credit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'simulated credit failure'; END $$");
        jdbcTemplate.execute("CREATE TRIGGER test_fail_profit_credit BEFORE INSERT ON balance_transactions FOR EACH ROW WHEN (NEW.type='SUBSITE_PROFIT') EXECUTE FUNCTION test_fail_profit_credit()");
        try {
            fulfillmentService.fulfillOrder(fixture.orderId());
            assertEquals("DELIVERED", scalar("SELECT status FROM orders WHERE id=?", String.class, fixture.orderId()));
            assertEquals("PENDING", scalar("SELECT subsite_profit_state FROM orders WHERE id=?", String.class, fixture.orderId()));
            assertMoney("0.00", scalar("SELECT balance FROM users WHERE id=?", BigDecimal.class, owner));
        } finally {
            jdbcTemplate.execute("DROP TRIGGER test_fail_profit_credit ON balance_transactions");
            jdbcTemplate.execute("DROP FUNCTION test_fail_profit_credit()");
        }
        subsiteSettlementService.retryPendingProfit();
        assertEquals("SETTLED", scalar("SELECT subsite_profit_state FROM orders WHERE id=?", String.class, fixture.orderId()));
        assertMoney("5.00", scalar("SELECT balance FROM users WHERE id=?", BigDecimal.class, owner));
        assertEquals(1, scalar("SELECT count(*) FROM balance_transactions WHERE order_id=? AND type='SUBSITE_PROFIT'", Integer.class, fixture.orderId()));
    }

    @Test
    void refundCancelsPendingProfitAndRetryCannotCreditIt() {
        UUID operator = insertAdmin("pending-refund-admin");
        UUID buyer = insertUser("pending-refund-buyer", "0.00");
        UUID owner = insertUser("pending-refund-owner", "9999999999.99");
        UUID site = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO subsites(id, owner_id, prefix, name) VALUES (?, ?, 'pending-refund', 'Pending refund')", site, owner);
        PendingCardOrder fixture = insertPendingCardOrder(buyer);
        jdbcTemplate.update("UPDATE orders SET subsite_id=?, subsite_owner_id_snapshot=?, subsite_profit=5.00, total_amount=15.00, actual_amount=15.00 WHERE id=?", site, owner, fixture.orderId());
        jdbcTemplate.update("UPDATE order_items SET unit_price=15.00, subtotal=15.00, subsite_supply_price_snapshot=10.00 WHERE id=?", fixture.itemId());
        fulfillmentService.fulfillOrder(fixture.orderId());
        RequestContext.set(new RequestContext.UserInfo(operator, "pending-refund-admin", "ADMIN"));
        orderRefundService.refund(fixture.orderId(), "申请退款", true, false);
        assertEquals("REFUNDED", scalar("SELECT status FROM orders WHERE id=?", String.class, fixture.orderId()));
        assertEquals("CANCELED", scalar("SELECT subsite_profit_state FROM orders WHERE id=?", String.class, fixture.orderId()));
        assertMoney("15.00", scalar("SELECT balance FROM users WHERE id=?", BigDecimal.class, buyer));
        jdbcTemplate.update("UPDATE users SET balance=0 WHERE id=?", owner);
        subsiteSettlementService.retryPendingProfit();
        assertMoney("0.00", scalar("SELECT balance FROM users WHERE id=?", BigDecimal.class, owner));
        assertEquals(0, scalar("SELECT count(*) FROM balance_transactions WHERE order_id=? AND type IN ('SUBSITE_PROFIT','SUBSITE_PROFIT_REVERSAL')", Integer.class, fixture.orderId()));
    }

    @Test
    void concurrentRetryAndRefundCannotCreditAfterRefund() throws Exception {
        UUID operator = insertAdmin("pending-race-admin");
        UUID buyer = insertUser("pending-race-buyer", "0.00");
        UUID owner = insertUser("pending-race-owner", "9999999999.99");
        UUID site = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO subsites(id, owner_id, prefix, name) VALUES (?, ?, 'pending-race', 'Pending race')", site, owner);
        PendingCardOrder fixture = insertPendingCardOrder(buyer);
        jdbcTemplate.update("UPDATE orders SET subsite_id=?, subsite_owner_id_snapshot=?, subsite_profit=5.00, total_amount=15.00, actual_amount=15.00 WHERE id=?", site, owner, fixture.orderId());
        jdbcTemplate.update("UPDATE order_items SET unit_price=15.00, subtotal=15.00, subsite_supply_price_snapshot=10.00 WHERE id=?", fixture.itemId());
        fulfillmentService.fulfillOrder(fixture.orderId());
        jdbcTemplate.update("UPDATE users SET balance=0 WHERE id=?", owner);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> retry = workers.submit(() -> { start.await(); subsiteSettlementService.retryPendingProfit(); return null; });
            Future<?> refund = workers.submit(() -> {
                start.await(); RequestContext.set(new RequestContext.UserInfo(operator, "pending-race-admin", "ADMIN"));
                try { orderRefundService.refund(fixture.orderId(), "并发退款", true, false); }
                finally { RequestContext.clear(); }
                return null;
            });
            start.countDown(); retry.get(15, TimeUnit.SECONDS); refund.get(15, TimeUnit.SECONDS);
            subsiteSettlementService.retryPendingProfit();
            assertEquals("REFUNDED", scalar("SELECT status FROM orders WHERE id=?", String.class, fixture.orderId()));
            String state = scalar("SELECT subsite_profit_state FROM orders WHERE id=?", String.class, fixture.orderId());
            assertTrue(List.of("CANCELED", "REVERSED").contains(state), state);
            assertMoney("0.00", scalar("SELECT balance FROM users WHERE id=?", BigDecimal.class, owner));
            assertMoney("15.00", scalar("SELECT balance FROM users WHERE id=?", BigDecimal.class, buyer));
            int credited = scalar("SELECT count(*) FROM balance_transactions WHERE order_id=? AND type='SUBSITE_PROFIT'", Integer.class, fixture.orderId());
            int reversed = scalar("SELECT count(*) FROM balance_transactions WHERE order_id=? AND type='SUBSITE_PROFIT_REVERSAL'", Integer.class, fixture.orderId());
            assertEquals(credited, reversed);
        } finally { workers.shutdownNow(); }
    }

    @Test
    void deliveryRetryAndRefundRaceLeavesNoUnreversedProfit() throws Exception {
        UUID operator = insertAdmin("triple-race-admin");
        UUID buyer = insertUser("triple-race-buyer", "0.00");
        UUID owner = insertUser("triple-race-owner", "0.00");
        UUID site = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO subsites(id, owner_id, prefix, name) VALUES (?, ?, 'triple-race', 'Triple race')", site, owner);
        PendingCardOrder fixture = insertPendingCardOrder(buyer);
        jdbcTemplate.update("UPDATE orders SET subsite_id=?, subsite_owner_id_snapshot=?, subsite_profit=5.00, total_amount=15.00, actual_amount=15.00 WHERE id=?", site, owner, fixture.orderId());
        jdbcTemplate.update("UPDATE order_items SET unit_price=15.00, subtotal=15.00, subsite_supply_price_snapshot=10.00 WHERE id=?", fixture.itemId());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(3);
        try {
            Future<?> delivery = workers.submit(() -> { start.await(); fulfillmentService.fulfillOrder(fixture.orderId()); return null; });
            Future<?> retry = workers.submit(() -> { start.await(); subsiteSettlementService.retryPendingProfit(); return null; });
            Future<?> refund = workers.submit(() -> {
                start.await(); RequestContext.set(new RequestContext.UserInfo(operator, "triple-race-admin", "ADMIN"));
                try { orderRefundService.refund(fixture.orderId(), "并发退款", true, false); }
                finally { RequestContext.clear(); }
                return null;
            });
            start.countDown(); delivery.get(30, TimeUnit.SECONDS); retry.get(30, TimeUnit.SECONDS); refund.get(30, TimeUnit.SECONDS);
            subsiteSettlementService.retryPendingProfit();
            assertEquals("REFUNDED", scalar("SELECT status FROM orders WHERE id=?", String.class, fixture.orderId()));
            assertMoney("0.00", scalar("SELECT balance FROM users WHERE id=?", BigDecimal.class, owner));
            assertMoney("15.00", scalar("SELECT balance FROM users WHERE id=?", BigDecimal.class, buyer));
            int credited = scalar("SELECT count(*) FROM balance_transactions WHERE order_id=? AND type='SUBSITE_PROFIT'", Integer.class, fixture.orderId());
            int reversed = scalar("SELECT count(*) FROM balance_transactions WHERE order_id=? AND type='SUBSITE_PROFIT_REVERSAL'", Integer.class, fixture.orderId());
            assertEquals(credited, reversed);
        } finally { workers.shutdownNow(); }
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
        assertEquals(1, scalar("SELECT count(*) FROM support_activity WHERE source_type='ORDER_REFUND' AND source_id=? AND notify_customer", Integer.class, orderId));
        RequestContext.set(new RequestContext.UserInfo(userId, "buyer", "USER"));
        UUID root=scalar("SELECT id FROM support_conversations WHERE user_id=? AND order_id IS NULL AND site_id IS NULL",UUID.class,userId);
        @SuppressWarnings("unchecked") var events=(List<Map<String,Object>>)supportTimeline.activities(root,false,null,null,null,40).get("items");
        var refund=events.stream().filter(event -> "ORDER_REFUND".equals(event.get("source_type"))).findFirst().orElseThrow();
        assertEquals(new BigDecimal("12.34"),refund.get("refund_amount"));
        assertEquals("BALANCE",refund.get("refund_destination"));
        assertEquals("REFUNDED",refund.get("order_status"));
        var timelineItems=items(supportTimeline.activities(root,false,null,null,null,40));
        var timelineRefund=timelineItems.stream().filter(event -> "ORDER_REFUND".equals(event.get("source_type"))).findFirst().orElseThrow();
        assertEquals(scalar("SELECT id FROM balance_transactions WHERE order_id=? AND type='REFUND'",UUID.class,orderId),timelineRefund.get("ledger_id"));
        assertTrue(timelineRefund.get("order_no") instanceof String);
        assertTrue(((String)supportTimeline.detail(root,false,null).get("preview")).contains("已退款 ¥12.34"));
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
        assertEquals(0, scalar("SELECT count(*) FROM support_activity WHERE source_type='ORDER_REFUND' AND source_id=?", Integer.class, orderId));
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
    void refundReclaimPreservesTheProductFormat() {
        UUID operatorId = insertAdmin("refund-format-admin");
        UUID userId = insertUser("refund-format-buyer", "0.00");
        DeliveredCardOrder fixture = insertDeliveredCardOrder(userId, 1);
        UUID productId = scalar("SELECT product_id FROM order_items WHERE id = ?", UUID.class, fixture.itemId());
        jdbcTemplate.update("""
                INSERT INTO products(id, title, retail_price, cost_price, category_id, card_content_column_count)
                VALUES (?, 'refund-format', 1.00, 0.10, ?, 2)
                """, productId, UUID.randomUUID());
        jdbcTemplate.update("UPDATE card_keys SET content_parts = '[\"account\",\"code\"]'::jsonb WHERE id = ?",
                fixture.cardKeyIds().get(0));
        RequestContext.set(new RequestContext.UserInfo(operatorId, "refund-format-admin", "ADMIN"));
        orderRefundService.refund(fixture.orderId(), "回收", true, true);

        assertEquals(2, scalar("SELECT card_content_column_count FROM products WHERE id = ?", Integer.class, productId));
        assertEquals("AVAILABLE", scalar("SELECT status FROM card_keys WHERE id = ?", String.class, fixture.cardKeyIds().get(0)));
        BusinessException exception = org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> adminCardKeyService.importCardKeys(Map.of("product_id", productId.toString(),
                        "content_columns", List.of("wrong-width")), operatorId));
        assertEquals(ErrorCode.CARD_KEY_FORMAT_ERROR, exception.getCode());
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
                    adminOrderService.markPaid(orderId, true);
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
        String paymentStatus = scalar(
                "SELECT status FROM payment_attempts WHERE id = ?", String.class, attemptId);
        assertTrue("PAID".equals(paymentStatus) || "CLOSED".equals(paymentStatus));
        assertEquals("payment-race-provider-trade", scalar(
                "SELECT provider_trade_no FROM payment_attempts WHERE id = ?", String.class, attemptId));
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

    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> items(Map<String,Object> page) {
        return (List<Map<String,Object>>)page.get("items");
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
