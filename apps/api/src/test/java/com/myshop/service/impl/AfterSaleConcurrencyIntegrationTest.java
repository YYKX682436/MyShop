package com.myshop.service.impl;

import com.myshop.constant.ErrorCode;
import com.myshop.constant.OrderStatus;
import com.myshop.context.RequestContext;
import com.myshop.service.AfterSaleService;
import com.myshop.service.EmailService;
import com.myshop.service.FulfillmentService;
import com.myshop.service.OrderRefundService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PostgreSQL proof for the order-first after-sale lock protocol. */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@SpringBootTest
class AfterSaleConcurrencyIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final Path PRIVATE_UPLOAD_DIRECTORY = temporaryDirectory();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AfterSaleService afterSaleService;

    @Autowired
    private OrderRefundService orderRefundService;

    @Autowired
    private FulfillmentService fulfillmentService;

    @MockitoBean
    private EmailService emailService;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("private-upload.path", () -> PRIVATE_UPLOAD_DIRECTORY.toString());
        registry.add("jwt.secret", () -> "after-sale-concurrency-integration-secret-with-at-least-64-bytes-2026");
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
        jdbcTemplate.update("DELETE FROM after_sale_attachments");
        jdbcTemplate.update("DELETE FROM after_sale_requests");
        jdbcTemplate.update("DELETE FROM order_items");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM users");
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    @Test
    void twoConcurrentApplicationsCreateOnePendingRequestAndOneAfterSaleOrder() throws Exception {
        UUID userId = insertUser("apply-race-user");
        UUID orderId = insertPaidOrder(userId, "3.00");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Outcome>> futures = List.of(
                    submitApply(executor, ready, start, userId, orderId, "第一次申请售后"),
                    submitApply(executor, ready, start, userId, orderId, "第二次申请售后"));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<Outcome> outcomes = waitFor(futures);

            assertEquals(1, outcomes.stream().filter(Outcome::success).count(), outcomes::toString);
            assertEquals(1, outcomes.stream().filter(outcome -> !outcome.success()).count(), outcomes::toString);
        } finally {
            executor.shutdownNow();
        }

        assertEquals("AFTER_SALE", scalar("SELECT status FROM orders WHERE id = ?", String.class, orderId));
        assertEquals(1, count("SELECT count(*) FROM after_sale_requests WHERE order_id = ?", orderId));
        assertEquals(1, count("SELECT count(*) FROM after_sale_requests WHERE order_id = ? AND status = 'PENDING'", orderId));
    }

    @Test
    void afterSaleApplicationSerializesWithAutomaticFulfillmentAndNeverDeliversAfterSaleOrder() throws Exception {
        UUID userId = insertUser("auto-fulfillment-race-user");
        AutoFixture fixture = insertAutomaticPaidOrder(userId);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> apply = submitApply(executor, ready, start, userId, fixture.orderId(), "自动发货竞态说明");
            Future<Outcome> fulfill = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                try {
                    return new Outcome(true, String.valueOf(
                            fulfillmentService.fulfillOrder(fixture.orderId()).get("status")), null);
                } catch (RuntimeException exception) {
                    return new Outcome(false, null, exception.getClass().getSimpleName());
                }
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            Outcome applyOutcome = apply.get(30, TimeUnit.SECONDS);
            Outcome fulfillOutcome = fulfill.get(30, TimeUnit.SECONDS);
            assertTrue(applyOutcome.success(), () -> "apply=" + applyOutcome + ", fulfill=" + fulfillOutcome);
            assertTrue(fulfillOutcome.success(), () -> "apply=" + applyOutcome + ", fulfill=" + fulfillOutcome);
        } finally {
            executor.shutdownNow();
        }

        assertEquals("AFTER_SALE", scalar("SELECT status FROM orders WHERE id = ?", String.class, fixture.orderId()));
        String itemStatus = scalar("SELECT fulfillment_status FROM order_items WHERE id = ?", String.class, fixture.itemId());
        String keyStatus = scalar("SELECT status FROM card_keys WHERE id = ?", String.class, fixture.cardId());
        assertTrue("PENDING".equals(itemStatus) && "AVAILABLE".equals(keyStatus)
                || "DELIVERED".equals(itemStatus) && "SOLD".equals(keyStatus));
        assertEquals(1, count("SELECT count(*) FROM after_sale_requests WHERE order_id = ? AND status = 'PENDING'",
                fixture.orderId()));
    }

    @Test
    void afterSaleApplicationSerializesWithManualFulfillmentAndStopsASecondDelivery() throws Exception {
        UUID userId = insertUser("manual-fulfillment-race-user");
        ManualFixture fixture = insertManualPaidOrder(userId);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> apply = submitApply(executor, ready, start, userId, fixture.orderId(), "人工发货竞态说明");
            Future<Outcome> fulfill = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                try {
                    return new Outcome(true, String.valueOf(fulfillmentService.completeManualItems(
                            fixture.orderId(), List.of(new FulfillmentService.ManualFulfillmentItem(
                                    fixture.itemId(), "人工交付内容"))).get("status")), null);
                } catch (RuntimeException exception) {
                    return new Outcome(false, null, exception.getClass().getSimpleName());
                }
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            Outcome applyOutcome = apply.get(30, TimeUnit.SECONDS);
            Outcome fulfillOutcome = fulfill.get(30, TimeUnit.SECONDS);
            assertTrue(applyOutcome.success() || fulfillOutcome.success(),
                    () -> "apply=" + applyOutcome + ", fulfill=" + fulfillOutcome);
        } finally {
            executor.shutdownNow();
        }

        assertEquals("AFTER_SALE", scalar("SELECT status FROM orders WHERE id = ?", String.class, fixture.orderId()));
        String itemStatus = scalar("SELECT fulfillment_status FROM order_items WHERE id = ?", String.class, fixture.itemId());
        assertTrue("WAITING_MANUAL".equals(itemStatus) || "DELIVERED".equals(itemStatus));
        assertEquals(1, count("SELECT count(*) FROM after_sale_requests WHERE order_id = ? AND status = 'PENDING'",
                fixture.orderId()));
    }

    @Test
    void batchSummaryQueryReadsEveryPendingAfterSaleOrderInThePage() {
        UUID firstUserId = insertUser("batch-summary-first-user");
        UUID secondUserId = insertUser("batch-summary-second-user");
        UUID firstOrderId = insertAfterSaleOrder(firstUserId, "2.00", OrderStatus.PAID);
        UUID secondOrderId = insertAfterSaleOrder(secondUserId, "3.00", OrderStatus.DELIVERED);
        UUID firstRequestId = insertPendingRequest(firstOrderId, firstUserId, OrderStatus.PAID);
        UUID secondRequestId = insertPendingRequest(secondOrderId, secondUserId, OrderStatus.DELIVERED);

        Map<UUID, Map<String, Object>> summaries = afterSaleService.buildOrderSummaries(
                List.of(firstOrderId, secondOrderId, firstOrderId));

        assertEquals(2, summaries.size());
        assertEquals(firstRequestId, summaries.get(firstOrderId).get("id"));
        assertEquals(secondRequestId, summaries.get(secondOrderId).get("id"));
        assertEquals("PENDING", summaries.get(firstOrderId).get("status"));
        assertEquals("ADMIN", summaries.get(secondOrderId).get("waiting_for"));
    }

    @Test
    void concurrentApproveAndRejectLeaveExactlyOneConsistentTerminalPair() throws Exception {
        UUID userId = insertUser("approve-reject-race-user");
        UUID adminId = insertAdmin("approve-reject-race-admin");
        UUID orderId = insertAfterSaleOrder(userId, "4.00", OrderStatus.PAID);
        UUID requestId = insertPendingRequest(orderId, userId, OrderStatus.PAID);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> approve = submitApprove(executor, ready, start, adminId, orderId);
            Future<Outcome> actualReject = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                RequestContext.set(new RequestContext.UserInfo(adminId, "admin", "ADMIN"));
                try {
                    afterSaleService.reject(orderId, "凭证不足，请补充后重新申请");
                    return new Outcome(true, "REJECTED", null);
                } catch (RuntimeException exception) {
                    return new Outcome(false, null, exception.getClass().getSimpleName());
                } finally {
                    RequestContext.clear();
                }
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            Outcome approved = approve.get(30, TimeUnit.SECONDS);
            Outcome rejected = actualReject.get(30, TimeUnit.SECONDS);
            assertEquals(1, List.of(approved, rejected).stream().filter(Outcome::success).count());
        } finally {
            executor.shutdownNow();
        }

        String orderStatus = scalar("SELECT status FROM orders WHERE id = ?", String.class, orderId);
        String requestStatus = scalar("SELECT status FROM after_sale_requests WHERE id = ?", String.class, requestId);
        assertTrue("REFUNDED".equals(orderStatus) && "REFUNDED".equals(requestStatus)
                || "PAID".equals(orderStatus) && "REJECTED".equals(requestStatus));
        assertEquals("REFUNDED".equals(orderStatus) ? 1 : 0,
                count("SELECT count(*) FROM balance_transactions WHERE order_id = ? AND type = 'REFUND'", orderId));
    }

    @Test
    void twoConcurrentApprovalsCreditBalanceAndWriteRefundLedgerExactlyOnce() throws Exception {
        UUID userId = insertUser("double-approve-user");
        UUID adminId = insertAdmin("double-approve-admin");
        UUID orderId = insertAfterSaleOrder(userId, "6.00", OrderStatus.PAID);
        insertPendingRequest(orderId, userId, OrderStatus.PAID);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Outcome>> futures = List.of(
                    submitApprove(executor, ready, start, adminId, orderId),
                    submitApprove(executor, ready, start, adminId, orderId));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<Outcome> outcomes = waitFor(futures);
            assertEquals(1, outcomes.stream().filter(Outcome::success).count());
        } finally {
            executor.shutdownNow();
        }

        assertEquals("REFUNDED", scalar("SELECT status FROM orders WHERE id = ?", String.class, orderId));
        assertEquals("REFUNDED", scalar("SELECT status FROM after_sale_requests WHERE order_id = ?", String.class, orderId));
        assertEquals(new BigDecimal("6.00"), scalar("SELECT balance FROM users WHERE id = ?", BigDecimal.class, userId));
        assertEquals(1, count("SELECT count(*) FROM balance_transactions WHERE order_id = ? AND type = 'REFUND'", orderId));
        assertEquals(1, count("SELECT count(*) FROM operation_logs WHERE action = 'order.after_sale.approve' AND target_id = ?",
                orderId.toString()));
    }

    @Test
    void concurrentAdminReplyAndRejectHonorWhicheverOrderLockIsWon() throws Exception {
        UUID userId = insertUser("admin-reply-reject-race-user", "0.00");
        UUID adminId = insertAdmin("admin-reply-reject-race-admin");
        UUID orderId = insertAfterSaleOrder(userId, "4.00", OrderStatus.PAID);
        UUID requestId = insertPendingRequest(orderId, userId, OrderStatus.PAID);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Outcome replyOutcome;
        Outcome rejectOutcome;
        try {
            Future<Outcome> reply = submitAdminReply(executor, ready, start, adminId, orderId);
            Future<Outcome> reject = submitReject(executor, ready, start, adminId, orderId);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            replyOutcome = reply.get(30, TimeUnit.SECONDS);
            rejectOutcome = reject.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertTrue(rejectOutcome.success(), () -> "reply=" + replyOutcome + ", reject=" + rejectOutcome);
        assertReplyOutcomeIsEitherCommittedBeforeTerminalOrRejectedAfterIt(replyOutcome);

        assertEquals("REJECTED", scalar(
                "SELECT status FROM after_sale_requests WHERE id = ?", String.class, requestId));
        assertEquals("PAID", scalar("SELECT status FROM orders WHERE id = ?", String.class, orderId));
        assertNull(scalar("SELECT waiting_for FROM after_sale_requests WHERE id = ?",
                String.class, requestId));
        assertTerminalMessageInvariant(requestId, replyOutcome.success(), "REJECTED");
    }

    @Test
    void concurrentCustomerReplyAndApproveHonorLockOutcomeAndRefundExactlyOnce() throws Exception {
        UUID userId = insertUser("customer-reply-approve-race-user", "1.25");
        UUID adminId = insertAdmin("customer-reply-approve-race-admin");
        UUID orderId = insertAfterSaleOrder(userId, "4.00", OrderStatus.PAID);
        UUID requestId = insertPendingRequest(orderId, userId, OrderStatus.PAID);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Outcome replyOutcome;
        Outcome approveOutcome;
        try {
            Future<Outcome> reply = submitCustomerReply(executor, ready, start, userId, orderId);
            Future<Outcome> approve = submitApprove(executor, ready, start, adminId, orderId);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            replyOutcome = reply.get(30, TimeUnit.SECONDS);
            approveOutcome = approve.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertTrue(approveOutcome.success(), () -> "reply=" + replyOutcome + ", approve=" + approveOutcome);
        assertReplyOutcomeIsEitherCommittedBeforeTerminalOrRejectedAfterIt(replyOutcome);

        assertEquals("REFUNDED", scalar(
                "SELECT status FROM orders WHERE id = ?", String.class, orderId));
        assertEquals("REFUNDED", scalar(
                "SELECT status FROM after_sale_requests WHERE id = ?", String.class, requestId));
        assertNull(scalar("SELECT waiting_for FROM after_sale_requests WHERE id = ?",
                String.class, requestId));
        assertMoney("5.25", scalar("SELECT balance FROM users WHERE id = ?", BigDecimal.class, userId));
        assertEquals(1, count("SELECT count(*) FROM balance_transactions "
                + "WHERE order_id = ? AND type = 'REFUND'", orderId));
        assertEquals(1, count("SELECT count(*) FROM balance_transactions "
                + "WHERE user_id = ? AND type = 'REFUND'", userId));
        assertMoney("4.00", scalar(
                "SELECT amount FROM balance_transactions WHERE order_id = ? AND type = 'REFUND'",
                BigDecimal.class, orderId));
        assertTerminalMessageInvariant(requestId, replyOutcome.success(), "REFUNDED");
    }

    private Future<Outcome> submitApply(ExecutorService executor, CountDownLatch ready,
                                         CountDownLatch start, UUID userId, UUID orderId, String reason) {
        return executor.submit(() -> {
            ready.countDown();
            assertTrue(start.await(10, TimeUnit.SECONDS));
            RequestContext.set(new RequestContext.UserInfo(userId, "buyer", "USER"));
            try {
                afterSaleService.apply(orderId, userId, reason, List.of());
                return new Outcome(true, "APPLIED", null);
            } catch (RuntimeException exception) {
                Integer code = exception instanceof com.myshop.exception.BusinessException business
                        ? business.getCode() : null;
                return new Outcome(false, null, code == null
                        ? exception.getClass().getSimpleName() + ":" + exception.getMessage()
                        : code + ":" + exception.getMessage());
            } finally {
                RequestContext.clear();
            }
        });
    }

    private Future<Outcome> submitApprove(ExecutorService executor, CountDownLatch ready,
                                          CountDownLatch start, UUID adminId, UUID orderId) {
        return executor.submit(() -> {
            ready.countDown();
            assertTrue(start.await(10, TimeUnit.SECONDS));
            RequestContext.set(new RequestContext.UserInfo(adminId, "admin", "ADMIN"));
            try {
                orderRefundService.approveAfterSale(orderId, true, false);
                return new Outcome(true, "APPROVED", null);
            } catch (RuntimeException exception) {
                Integer code = exception instanceof com.myshop.exception.BusinessException business
                        ? business.getCode() : null;
                return new Outcome(false, null, code == null
                        ? exception.getClass().getSimpleName() + ":" + exception.getMessage()
                        : code + ":" + exception.getMessage());
            } finally {
                RequestContext.clear();
            }
        });
    }

    private Future<Outcome> submitAdminReply(ExecutorService executor, CountDownLatch ready,
                                              CountDownLatch start, UUID adminId, UUID orderId) {
        return executor.submit(() -> {
            ready.countDown();
            assertTrue(start.await(10, TimeUnit.SECONDS));
            RequestContext.set(new RequestContext.UserInfo(adminId, "admin", "ADMIN"));
            try {
                afterSaleService.replyFromAdmin(orderId, "商家回复了售后申请", List.of());
                return new Outcome(true, "REPLIED", null);
            } catch (RuntimeException exception) {
                return failure(exception);
            } finally {
                RequestContext.clear();
            }
        });
    }

    private Future<Outcome> submitCustomerReply(ExecutorService executor, CountDownLatch ready,
                                                 CountDownLatch start, UUID userId, UUID orderId) {
        return executor.submit(() -> {
            ready.countDown();
            assertTrue(start.await(10, TimeUnit.SECONDS));
            RequestContext.set(new RequestContext.UserInfo(userId, "buyer", "USER"));
            try {
                afterSaleService.replyFromCustomer(orderId, userId, "买家补充了售后信息", List.of());
                return new Outcome(true, "REPLIED", null);
            } catch (RuntimeException exception) {
                return failure(exception);
            } finally {
                RequestContext.clear();
            }
        });
    }

    private Future<Outcome> submitReject(ExecutorService executor, CountDownLatch ready,
                                          CountDownLatch start, UUID adminId, UUID orderId) {
        return executor.submit(() -> {
            ready.countDown();
            assertTrue(start.await(10, TimeUnit.SECONDS));
            RequestContext.set(new RequestContext.UserInfo(adminId, "admin", "ADMIN"));
            try {
                afterSaleService.reject(orderId, "凭证不足，请补充后重新申请");
                return new Outcome(true, "REJECTED", null);
            } catch (RuntimeException exception) {
                return failure(exception);
            } finally {
                RequestContext.clear();
            }
        });
    }

    private Outcome failure(RuntimeException exception) {
        Integer code = exception instanceof com.myshop.exception.BusinessException business
                ? business.getCode() : null;
        return new Outcome(false, null, code == null
                ? exception.getClass().getSimpleName() + ":" + exception.getMessage()
                : code + ":" + exception.getMessage());
    }

    private void assertReplyOutcomeIsEitherCommittedBeforeTerminalOrRejectedAfterIt(Outcome reply) {
        assertTrue(reply.success()
                        || (reply.error() != null
                        && reply.error().startsWith(String.valueOf(ErrorCode.BAD_REQUEST))),
                () -> "unexpected reply outcome: " + reply);
    }

    private void assertTerminalMessageInvariant(UUID requestId, boolean replySucceeded,
                                                String terminalStatus) {
        int messageCount = count("SELECT count(*) FROM after_sale_messages WHERE request_id = ?", requestId);
        assertEquals(replySucceeded ? 1 : 0, messageCount);

        LocalDateTime handledAt = scalar(
                "SELECT handled_at FROM after_sale_requests WHERE id = ?", LocalDateTime.class, requestId);
        assertTrue(handledAt != null);
        int messagesAtOrAfterTerminal = count("""
                SELECT count(*)
                FROM after_sale_messages m
                JOIN after_sale_requests r ON r.id = m.request_id
                WHERE r.id = ? AND r.status = ? AND r.handled_at IS NOT NULL
                  AND m.created_at >= r.handled_at
                """, requestId, terminalStatus);
        assertEquals(0, messagesAtOrAfterTerminal);

        if (replySucceeded) {
            LocalDateTime messageCreatedAt = scalar(
                    "SELECT created_at FROM after_sale_messages WHERE request_id = ?",
                    LocalDateTime.class, requestId);
            assertTrue(messageCreatedAt.isBefore(handledAt),
                    () -> "message=" + messageCreatedAt + ", handled=" + handledAt);
        }
    }

    private List<Outcome> waitFor(List<Future<Outcome>> futures) throws Exception {
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> future : futures) {
            outcomes.add(future.get(30, TimeUnit.SECONDS));
        }
        return outcomes;
    }

    private UUID insertUser(String suffix) {
        return insertUser(suffix, "0.00");
    }

    private UUID insertUser(String suffix, String balance) {
        UUID id = UUID.randomUUID();
        UUID levelId = scalar("SELECT id FROM user_levels WHERE is_default = true", UUID.class);
        jdbcTemplate.update("""
                INSERT INTO users(id, username, email, password_hash, role, user_no, user_level_id, balance, is_deleted)
                VALUES (?, ?, ?, 'test-hash', 'USER', ?, ?, ?, 0)
                """, id, suffix + "-" + id, id + "@example.com", Math.abs(id.hashCode()) + 10000L,
                levelId, new BigDecimal(balance));
        return id;
    }

    private UUID insertAdmin(String suffix) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users(id, username, email, password_hash, role, is_deleted)
                VALUES (?, ?, ?, 'test-hash', 'ADMIN', 0)
                """, id, suffix + "-" + id, id + "@example.com");
        return id;
    }

    private UUID insertPaidOrder(UUID userId, String amount) {
        return insertAfterSaleOrder(userId, amount, OrderStatus.PAID);
    }

    private UUID insertAfterSaleOrder(UUID userId, String amount, OrderStatus sourceStatus) {
        UUID orderId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO orders(id, user_id, total_amount, actual_amount, status, order_type,
                                   payment_method, payment_channel_code, paid_at)
                VALUES (?, ?, ?, ?, ?, 'NORMAL', 'ONLINE', 'after-sale-race-channel', CURRENT_TIMESTAMP)
                """, orderId, userId, new BigDecimal(amount), new BigDecimal(amount), sourceStatus.name());
        return orderId;
    }

    private UUID insertPendingRequest(UUID orderId, UUID userId, OrderStatus sourceStatus) {
        UUID requestId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO after_sale_requests(id, order_id, user_id, status, source_order_status,
                                                reason, waiting_for, last_activity_at, submitted_at)
                VALUES (?, ?, ?, 'PENDING', ?, '用户提交的有效售后说明', 'ADMIN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, requestId, orderId, userId, sourceStatus.name());
        jdbcTemplate.update("UPDATE orders SET status = 'AFTER_SALE' WHERE id = ?", orderId);
        return requestId;
    }

    private AutoFixture insertAutomaticPaidOrder(UUID userId) {
        UUID orderId = insertPaidOrder(userId, "1.00");
        UUID productId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO order_items(id, order_id, product_id, product_no, product_title, quantity,
                                        unit_price, cost_price_snapshot, retail_price_snapshot, pricing_source,
                                        subtotal, delivery_type, customer_inputs, stock_mode, fulfillment_status,
                                        manual_stock_reserved)
                VALUES (?, ?, ?, 1, 'Auto race product', 1, 1.00, 0.10, 1.00, 'RETAIL',
                        1.00, 'AUTO', '[]'::jsonb, 'CARD_KEY', 'PENDING', 0)
                """, itemId, orderId, productId);
        jdbcTemplate.update("""
                INSERT INTO card_keys(id, product_id, content_parts, content_fingerprint, status)
                VALUES (?, ?, '["race-key"]'::jsonb, ?, 'AVAILABLE')
                """, cardId, productId, "after-sale-race-" + cardId);
        return new AutoFixture(orderId, itemId, cardId);
    }

    private ManualFixture insertManualPaidOrder(UUID userId) {
        UUID orderId = insertPaidOrder(userId, "1.00");
        UUID itemId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO order_items(id, order_id, product_id, product_no, product_title, quantity,
                                        unit_price, cost_price_snapshot, retail_price_snapshot, pricing_source,
                                        subtotal, delivery_type, customer_inputs, stock_mode, fulfillment_status,
                                        manual_stock_reserved)
                VALUES (?, ?, ?, 1, 'Manual race product', 1, 1.00, 0.10, 1.00, 'RETAIL',
                        1.00, 'MANUAL', '[]'::jsonb, 'MANUAL', 'WAITING_MANUAL', 0)
                """, itemId, orderId, UUID.randomUUID());
        return new ManualFixture(orderId, itemId);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private <T> T scalar(String sql, Class<T> type, Object... args) {
        return jdbcTemplate.queryForObject(sql, type, args);
    }

    private void assertMoney(String expected, BigDecimal actual) {
        assertTrue(actual != null);
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("myshop-after-sale-concurrency-");
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private record Outcome(boolean success, String value, String error) {
    }

    private record AutoFixture(UUID orderId, UUID itemId, UUID cardId) {
    }

    private record ManualFixture(UUID orderId, UUID itemId) {
    }
}
