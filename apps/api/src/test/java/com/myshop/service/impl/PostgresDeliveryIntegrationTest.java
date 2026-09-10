package com.myshop.service.impl;

import com.myshop.exception.BusinessException;
import com.myshop.constant.ErrorCode;
import com.myshop.constant.OrderType;
import com.myshop.entity.Product;
import com.myshop.model.request.AdminCardKeyBatchPickupRequest;
import com.myshop.model.request.AdminOrderSearchRequest;
import com.myshop.payment.EpayPaymentProvider;
import com.myshop.payment.PaymentNextAction;
import com.myshop.payment.PaymentProvider;
import com.myshop.payment.PaymentStatus;
import com.myshop.service.AutoFulfillmentService;
import com.myshop.service.AdminCardKeyService;
import com.myshop.service.AdminOrderService;
import com.myshop.service.FulfillmentService;
import com.myshop.service.ManualStockService;
import com.myshop.service.OrderService;
import com.myshop.service.PaymentService;
import com.myshop.service.WebhookService;
import com.myshop.utils.CardContentParser;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    private OrderService orderService;

    @Autowired
    private AdminCardKeyService adminCardKeyService;

    @Autowired
    private AdminOrderService adminOrderService;

    @Autowired
    private WebhookService webhookService;

    @Autowired
    private AutoFulfillmentService autoFulfillmentService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private FulfillmentService fulfillmentService;

    @Autowired
    private ManualStockService manualStockService;

    @MockitoBean
    private com.myshop.service.EmailService emailService;

    @MockitoBean
    private EpayPaymentProvider epayPaymentProvider;

    @BeforeEach
    void configureProvider() {
        when(epayPaymentProvider.id()).thenReturn("epay");
        when(epayPaymentProvider.paymentTypes()).thenReturn(List.of("alipay", "wxpay"));
        when(epayPaymentProvider.successAcknowledgement())
                .thenReturn(PaymentProvider.WebhookAcknowledgement.success());
        when(epayPaymentProvider.failureAcknowledgement())
                .thenReturn(PaymentProvider.WebhookAcknowledgement.failure());
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
    void adminPickupUsesDatabaseRoleAndConsumesCardThroughNormalFulfillment() {
        UUID adminId = insertAdminUser("pickup-admin", "pickup-admin@example.com");
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        UUID cardId = insertCard(productId, "admin-pickup-card");

        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("product_id", productId.toString());
        request.put("quantity", 1);
        request.put("admin_pickup", false);

        Map<String, Object> result = orderService.createDirectOrder(request, adminId,
                "203.0.113.30", "admin-pickup-session");
        Map<?, ?> orderResult = (Map<?, ?>) result.get("order");
        Map<?, ?> paymentResult = (Map<?, ?>) result.get("payment");
        UUID orderId = (UUID) orderResult.get("id");

        assertEquals(OrderType.ADMIN_PICKUP, orderResult.get("order_type"));
        assertNotNull(orderResult.get("order_no"));
        assertEquals(null, orderResult.get("payment_method"));
        assertEquals(null, orderResult.get("payment_channel_code"));
        assertEquals(new BigDecimal("10.00"), orderResult.get("total_amount"));
        assertEquals(new BigDecimal("0.00"), orderResult.get("actual_amount"));
        assertEquals(true, paymentResult.get("paid"));
        assertEquals(true, paymentResult.get("admin_pickup"));
        assertEquals(java.util.Set.of("order_id", "expires_at",
                        "order_no", "order_type", "admin_pickup", "paid"), paymentResult.keySet());
        assertEquals("DELIVERED", scalar("select status from orders where id = ?", String.class, orderId));
        assertEquals("ADMIN_PICKUP", scalar("select order_type from orders where id = ?", String.class, orderId));
        assertEquals("SOLD", scalar("select status from card_keys where id = ?", String.class, cardId));
        assertEquals(orderId, scalar("select order_id from card_keys where id = ?", UUID.class, cardId));
        assertTrue(number("select count(*) from card_keys where id = ? and order_item_id is not null and sold_at is not null", cardId) == 1);
        verify(epayPaymentProvider, never()).create(any(PaymentProvider.PaymentRequest.class));
        verify(emailService, never()).sendDeliveryEmail(any(), anyList());
    }

    @Test
    void balancePaymentDebitsExactlyOnceAndFulfillsAfterTheOrderIsPaid() {
        UUID userId = insertUser("balance-buyer-" + UUID.randomUUID(), "balance-buyer-" + UUID.randomUUID() + "@example.com");
        setBalance(userId, new BigDecimal("10.00"));
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        UUID cardId = insertCard(productId, "balance-card");

        Map<String, Object> result = orderService.createDirectOrder(
                balanceOrderRequest(productId, "balance-success-key", "balance-success-fingerprint"),
                userId, "203.0.113.31", "balance-success-session");
        Map<?, ?> order = (Map<?, ?>) result.get("order");
        Map<?, ?> payment = (Map<?, ?>) result.get("payment");
        UUID orderId = (UUID) order.get("id");

        assertEquals("PAID", order.get("status"));
        assertNotNull(order.get("order_no"));
        assertEquals("BALANCE", order.get("payment_method"));
        assertEquals(null, order.get("payment_channel_code"));
        assertEquals("PAID", payment.get("status"));
        assertEquals(order.get("order_no"), payment.get("order_no"));
        assertEquals(true, payment.get("paid"));
        assertEquals("BALANCE", payment.get("payment_method"));
        assertEquals(null, payment.get("payment_channel_code"));
        assertEquals(null, payment.get("next_action"));
        assertEquals(new BigDecimal("0.00"), scalar("select balance from users where id = ?", BigDecimal.class, userId));
        assertEquals(1, number("select count(*) from balance_transactions "
                + "where user_id = ? and type = 'ORDER_PAYMENT' and order_id = ?", userId, orderId));
        assertEquals(new BigDecimal("10.00"), scalar(
                "select amount from balance_transactions where order_id = ?", BigDecimal.class, orderId));
        assertEquals(new BigDecimal("-10.00"), scalar(
                "select delta from balance_transactions where order_id = ?", BigDecimal.class, orderId));
        assertEquals(new BigDecimal("10.00"), scalar(
                "select balance_before from balance_transactions where order_id = ?", BigDecimal.class, orderId));
        assertEquals(new BigDecimal("0.00"), scalar(
                "select balance_after from balance_transactions where order_id = ?", BigDecimal.class, orderId));
        assertEquals(orderId, scalar("select order_id from balance_transactions where order_id = ?", UUID.class, orderId));
        assertEquals(userId, scalar("select operator_id from balance_transactions where order_id = ?", UUID.class, orderId));
        assertEquals("DELIVERED", scalar("select status from orders where id = ?", String.class, orderId));
        assertEquals("SOLD", scalar("select status from card_keys where id = ?", String.class, cardId));
        assertEquals(0, number("select count(*) from payment_attempts where order_id = ?", orderId));
        verify(epayPaymentProvider, never()).create(any(PaymentProvider.PaymentRequest.class));
    }

    @Test
    void balanceShortageRollsBackOrderItemsManualReservationLedgerAndBalance() {
        UUID userId = insertUser("balance-shortage-" + UUID.randomUUID(), "balance-shortage-" + UUID.randomUUID() + "@example.com");
        setBalance(userId, new BigDecimal("9.99"));
        UUID productId = UUID.randomUUID();
        insertManualProduct(productId);
        insertManualStock(productId, null, 1, 0);

        BusinessException exception = assertThrows(BusinessException.class, () -> orderService.createDirectOrder(
                balanceOrderRequest(productId, "balance-shortage-key", "balance-shortage-fingerprint"),
                userId, "203.0.113.32", "balance-shortage-session"));

        assertEquals(ErrorCode.INSUFFICIENT_BALANCE, exception.getCode());
        assertEquals(new BigDecimal("9.99"), scalar("select balance from users where id = ?", BigDecimal.class, userId));
        assertEquals(0, number("select count(*) from orders where user_id = ?", userId));
        assertEquals(0, number("select count(*) from balance_transactions where user_id = ?", userId));
        assertEquals(1, number("select available_quantity from manual_stocks "
                + "where product_id = ? and spec_id is null", productId));
        assertEquals(0, number("select reserved_quantity from manual_stocks "
                + "where product_id = ? and spec_id is null", productId));
    }

    @Test
    void guestBalancePaymentIsRejectedAndLeavesNoPendingOrder() {
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        insertCard(productId, "guest-balance-card");

        BusinessException exception = assertThrows(BusinessException.class, () -> orderService.createDirectOrder(
                balanceOrderRequest(productId, "guest-balance-key", "guest-balance-fingerprint"),
                null, "203.0.113.33", "guest-balance-session"));

        assertEquals(ErrorCode.BALANCE_PAYMENT_REQUIRES_LOGIN, exception.getCode());
        assertEquals(0, number("select count(*) from orders where session_token = ?", "guest-balance-session"));
    }

    @Test
    void concurrentBalanceOrdersLockTheUserAndOnlyOneCanSpendTheAvailableBalance() throws Exception {
        UUID userId = insertUser("balance-concurrent-" + UUID.randomUUID(), "balance-concurrent-" + UUID.randomUUID() + "@example.com");
        setBalance(userId, new BigDecimal("10.00"));
        UUID productId = UUID.randomUUID();
        insertUnlimitedProduct(productId);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> tryBalanceOrder(
                    ready, start, productId, userId, "balance-concurrent-key-a", "balance-concurrent-fingerprint-a"));
            Future<Boolean> second = executor.submit(() -> tryBalanceOrder(
                    ready, start, productId, userId, "balance-concurrent-key-b", "balance-concurrent-fingerprint-b"));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertNotEquals(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertEquals(new BigDecimal("0.00"), scalar("select balance from users where id = ?", BigDecimal.class, userId));
        assertEquals(1, number("select count(*) from orders where user_id = ?", userId));
        assertEquals(1, number("select count(*) from balance_transactions "
                + "where user_id = ? and type = 'ORDER_PAYMENT'", userId));
    }

    @Test
    void balanceIdempotencyReplayReturnsPaidOrderWithoutSecondDebitLedgerOrFulfillment() {
        UUID userId = insertUser("balance-idempotent-" + UUID.randomUUID(), "balance-idempotent-" + UUID.randomUUID() + "@example.com");
        setBalance(userId, new BigDecimal("10.00"));
        UUID productId = UUID.randomUUID();
        insertUnlimitedProduct(productId);
        Map<String, Object> request = balanceOrderRequest(
                productId, "balance-idempotent-key", "balance-idempotent-fingerprint");

        Map<String, Object> first = orderService.createDirectOrder(
                request, userId, "203.0.113.34", "balance-idempotent-session");
        Map<String, Object> second = orderService.createDirectOrder(
                new java.util.LinkedHashMap<>(request), userId, "203.0.113.34", "balance-idempotent-session");
        UUID firstOrderId = (UUID) ((Map<?, ?>) first.get("order")).get("id");
        UUID secondOrderId = (UUID) ((Map<?, ?>) second.get("order")).get("id");

        assertEquals(firstOrderId, secondOrderId);
        assertEquals("PAID", ((Map<?, ?>) second.get("payment")).get("status"));
        assertEquals(1, number("select count(*) from orders where idempotency_key = ?", "balance-idempotent-key"));
        assertEquals(1, number("select count(*) from balance_transactions "
                + "where order_id = ? and type = 'ORDER_PAYMENT'", firstOrderId));
        assertEquals(0, number("select count(*) from payment_attempts where order_id = ?", firstOrderId));
    }

    @Test
    void normalOrderRejectsBalanceRequestThatIncludesAnOnlineChannel() {
        UUID userId = insertUser("balance-protocol-" + UUID.randomUUID(), "balance-protocol-" + UUID.randomUUID() + "@example.com");
        setBalance(userId, new BigDecimal("10.00"));
        UUID productId = UUID.randomUUID();
        insertUnlimitedProduct(productId);
        Map<String, Object> request = balanceOrderRequest(productId, "balance-protocol-key", "balance-protocol-fingerprint");
        request.put("payment_channel_code", "alipay");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> orderService.createDirectOrder(request, userId, "203.0.113.35", "balance-protocol-session"));

        assertEquals(ErrorCode.BAD_REQUEST, exception.getCode());
        assertEquals(0, number("select count(*) from orders where user_id = ?", userId));
    }

    @Test
    void adminBatchPickupReturnsOneResultPerCardKeyAndBindsTheDeliveredOrderItem() {
        String suffix = UUID.randomUUID().toString();
        UUID operatorId = insertAdminUser("batch-pickup-admin-" + suffix, "batch-pickup-" + suffix + "@example.com");
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        UUID firstCardId = insertCardParts(productId, List.of("first-user", "first-password"));
        UUID secondCardId = insertCardParts(productId, List.of("second-user", "second-password"));

        var result = adminCardKeyService.batchPickup(
                new AdminCardKeyBatchPickupRequest(productId, null, 2), operatorId);

        assertEquals(2, result.count());
        assertNotNull(result.orderNo());
        assertEquals(2, result.cards().size());
        assertEquals(List.of(firstCardId, secondCardId),
                result.cards().stream().map(card -> card.id()).toList());
        assertEquals(List.of("first-user", "first-password"), result.cards().get(0).contentParts());
        UUID orderId = result.orderId();
        assertEquals("ADMIN_PICKUP", scalar("select order_type from orders where id = ?", String.class, orderId));
        assertEquals("DELIVERED", scalar("select status from orders where id = ?", String.class, orderId));
        assertEquals(new BigDecimal("0.00"), scalar("select total_amount from orders where id = ?", BigDecimal.class, orderId));
        assertEquals(new BigDecimal("0.00"), scalar("select actual_amount from orders where id = ?", BigDecimal.class, orderId));
        assertEquals(1, number("select count(*) from orders where id = ? and delivered_at is not null", orderId));
        assertEquals(1, number("select count(*) from order_items where order_id = ? and quantity = 2 "
                + "and unit_price = 0 and subtotal = 0 and stock_mode = 'CARD_KEY' "
                + "and fulfillment_status = 'DELIVERED' and delivered_at is not null", orderId));
        assertEquals(2, number("select count(*) from card_keys where order_id = ? and order_item_id is not null "
                + "and status = 'SOLD' and sold_at is not null", orderId));
        assertEquals(2, number("select count(*) from card_keys where id in (?, ?) and order_id = ?", firstCardId, secondCardId, orderId));
        verify(emailService, never()).sendDeliveryEmail(any(), anyList());
    }

    @Test
    void adminBatchPickupShortageRollsBackTheWholeTransaction() {
        String suffix = UUID.randomUUID().toString();
        UUID operatorId = insertAdminUser("shortage-pickup-admin-" + suffix, "shortage-pickup-" + suffix + "@example.com");
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        UUID cardId = insertCard(productId, "only-pickup-card");

        assertThrows(BusinessException.class, () -> adminCardKeyService.batchPickup(
                new AdminCardKeyBatchPickupRequest(productId, null, 2), operatorId));

        assertEquals("AVAILABLE", scalar("select status from card_keys where id = ?", String.class, cardId));
        assertEquals(0, number("select count(*) from orders where user_id = ? and order_type = 'ADMIN_PICKUP'", operatorId));
        assertEquals(0, number("select count(*) from card_keys where order_id is not null and id = ?", cardId));
    }

    @Test
    void adminSinglePickupLocksAndConsumesOnlyTheRequestedCardKey() {
        String suffix = UUID.randomUUID().toString();
        UUID operatorId = insertAdminUser("single-pickup-admin-" + suffix, "single-pickup-" + suffix + "@example.com");
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        UUID firstCardId = insertCard(productId, "first-single-card");
        UUID requestedCardId = insertCardParts(productId, List.of("requested-user", "requested-password"));

        var result = adminCardKeyService.pickupCardKey(requestedCardId, operatorId);

        assertEquals(1, result.count());
        assertNotNull(result.orderNo());
        assertEquals(requestedCardId, result.cards().get(0).id());
        assertEquals("AVAILABLE", scalar("select status from card_keys where id = ?", String.class, firstCardId));
        assertEquals("SOLD", scalar("select status from card_keys where id = ?", String.class, requestedCardId));
        assertEquals(result.orderId(), scalar("select order_id from card_keys where id = ?", UUID.class, requestedCardId));
        assertEquals(ErrorCode.CARD_KEY_UNAVAILABLE,
                assertThrows(BusinessException.class, () -> adminCardKeyService.pickupCardKey(requestedCardId, operatorId)).getCode());

        UUID invalidCardId = insertCard(productId, "invalid-single-card");
        jdbcTemplate.update("update card_keys set status = 'INVALID' where id = ?", invalidCardId);
        assertEquals(ErrorCode.CARD_KEY_UNAVAILABLE,
                assertThrows(BusinessException.class, () -> adminCardKeyService.pickupCardKey(invalidCardId, operatorId)).getCode());
    }

    @Test
    void adminPickupIsExcludedFromNormalAdminOrderList() {
        String suffix = UUID.randomUUID().toString();
        UUID operatorId = insertAdminUser("list-pickup-admin-" + suffix, "list-pickup-" + suffix + "@example.com");
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        insertOrder("normal-list-order-" + UUID.randomUUID());
        insertCard(productId, "list-pickup-card");
        adminCardKeyService.batchPickup(
                new AdminCardKeyBatchPickupRequest(productId, null, 1), operatorId);

        var result = adminOrderService.searchOrders(new AdminOrderSearchRequest(1, 100, null, null, null, null, null));
        List<?> orders = result.getList();
        assertTrue(orders.stream().noneMatch(value -> value instanceof Map<?, ?> map
                && OrderType.ADMIN_PICKUP.name().equals(String.valueOf(map.get("order_type")))));
    }

    @Test
    void adminOrderSearchMatchesEveryOperationalFieldWithoutDuplicateOrders() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        UUID userId = insertUser("search-user-" + suffix, "registered-search-" + suffix + "@example.com");
        jdbcTemplate.update("UPDATE users SET phone = ? WHERE id = ?", "13800138000", userId);
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        UUID orderId = insertSearchOrder(userId, "search-epay-" + suffix,
                LocalDateTime.of(2026, 3, 3, 10, 0), "PAID", new BigDecimal("62.00"));
        UUID firstItemId = insertOrderItem(orderId, productId, "AUTO", "CARD_KEY", 2, 0);
        UUID secondItemId = insertOrderItem(orderId, productId, "AUTO", "CARD_KEY", 1, 0);
        jdbcTemplate.update("""
                UPDATE order_items
                SET product_title = ?, spec_name = ?, customer_inputs = CAST(? AS jsonb),
                    unit_price = ?, cost_price_snapshot = ?, retail_price_snapshot = ?, subtotal = ?
                WHERE id = ?
                """, "Search Product Title", "Search Spec Name",
                "[{\"id\":\"account\",\"label\":\"账号\",\"value\":\"search-customer-value\"}]",
                new BigDecimal("25.00"), new BigDecimal("8.00"), new BigDecimal("30.00"),
                new BigDecimal("50.00"), firstItemId);
        jdbcTemplate.update("""
                UPDATE order_items
                SET product_title = ?, spec_name = ?, customer_inputs = CAST(? AS jsonb),
                    unit_price = ?, cost_price_snapshot = ?, retail_price_snapshot = ?, subtotal = ?
                WHERE id = ?
                """, "Second Search Product", "Second Search Spec",
                "[{\"id\":\"region\",\"label\":\"区服\",\"value\":\"second-customer-value\"}]",
                new BigDecimal("12.00"), new BigDecimal("5.00"), new BigDecimal("12.00"),
                new BigDecimal("12.00"), secondItemId);
        UUID cardId = insertCardParts(productId, List.of("search-card-user", "search-card-password"));
        UUID secondCardId = insertCardParts(productId, List.of("search-card-user-2", "search-card-password"));
        jdbcTemplate.update("""
                UPDATE card_keys
                SET order_id = ?, order_item_id = ?, status = 'SOLD', sold_at = current_timestamp
                WHERE id = ?
                """, orderId, firstItemId, cardId);
        jdbcTemplate.update("""
                UPDATE card_keys
                SET order_id = ?, order_item_id = ?, status = 'SOLD', sold_at = current_timestamp
                WHERE id = ?
                """, orderId, secondItemId, secondCardId);

        Long productNo = scalar("SELECT product_no FROM products WHERE id = ?", Long.class, productId);
        Long userNo = scalar("SELECT user_no FROM users WHERE id = ?", Long.class, userId);
        Long orderNo = scalar("SELECT order_no FROM orders WHERE id = ?", Long.class, orderId);
        List<String> matchingKeywords = List.of(
                orderNo.toString(),
                "Search Product Title", "Search Spec Name",
                "search-customer-value", "search-user-" + suffix,
                "registered-search-" + suffix + "@example.com",
                "13800138000", "search-card-password",
                "search-epay-" + suffix, "search-channel-search-epay-" + suffix, "alipay");
        for (String keyword : matchingKeywords) {
            assertSearchMatches(keyword, orderId);
        }
        assertSearchContains(productNo.toString(), orderId);
        assertSearchContains(userNo.toString(), orderId);
        assertSearchDoesNotMatch(orderId.toString());
        assertSearchDoesNotMatch(userId.toString());
        assertSearchDoesNotMatch(cardId.toString());
        assertEquals(0L, adminOrderService.searchOrders(new AdminOrderSearchRequest(
                1, 20, "definitely-no-order-match-" + suffix, null, null, null, "desc"))
                .getPagination().getTotal());

        var summary = (Map<?, ?>) adminOrderService.searchOrders(new AdminOrderSearchRequest(
                1, 20, orderNo.toString(), null, null, null, "desc")).getList().get(0);
        assertEquals(orderId, summary.get("id"));
        assertEquals(orderNo, summary.get("order_no"));
        assertEquals("REGISTERED", summary.get("customer_type"));
        assertEquals("search-user-" + suffix, summary.get("username"));
        assertEquals(userNo, summary.get("user_no"));
        assertTrue(!summary.containsKey("email"));
        assertTrue(!summary.containsKey("order_email"));
        assertEquals(new BigDecimal("21.00"), summary.get("cost_total"));
        assertEquals(new BigDecimal("41.00"), summary.get("gross_profit_total"));
        assertEquals(new BigDecimal("66.13"), summary.get("gross_margin_total"));
    }

    @Test
    void adminOrderSearchUsesCreatedAtHalfOpenDatesAndStableSortDirection() {
        UUID oldest = insertSearchOrder(null, "trade-oldest",
                LocalDateTime.of(2026, 4, 2, 10, 0), "PAID", new BigDecimal("10.00"));
        UUID middle = insertSearchOrder(null, "trade-middle",
                LocalDateTime.of(2026, 4, 3, 10, 0), "PAID", new BigDecimal("11.00"));
        UUID boundary = insertSearchOrder(null, "trade-boundary",
                LocalDateTime.of(2026, 4, 4, 0, 0), "PAID", new BigDecimal("12.00"));

        var ascending = adminOrderService.searchOrders(new AdminOrderSearchRequest(
                1, 20, null, "PAID", "2026-04-02", "2026-04-04", "asc"));
        assertEquals(2L, ascending.getPagination().getTotal());
        assertEquals(List.of(oldest, middle), ascending.getList().stream()
                .map(value -> ((Map<?, ?>) value).get("id")).toList());
        assertTrue(ascending.getList().stream().noneMatch(value ->
                boundary.equals(((Map<?, ?>) value).get("id"))));

        var descending = adminOrderService.searchOrders(new AdminOrderSearchRequest(
                1, 20, null, "PAID", "2026-04-02", "2026-04-04", "desc"));
        assertEquals(2L, descending.getPagination().getTotal());
        assertEquals(List.of(middle, oldest), descending.getList().stream()
                .map(value -> ((Map<?, ?>) value).get("id")).toList());
    }

    @Test
    void normalFulfillmentAndAdminPickupCannotConsumeTheSameCardConcurrently() throws Exception {
        String suffix = UUID.randomUUID().toString();
        UUID operatorId = insertAdminUser("concurrent-pickup-admin-" + suffix, "concurrent-pickup-" + suffix + "@example.com");
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        UUID normalOrderId = insertOrder("normal-vs-pickup-" + UUID.randomUUID());
        insertOrderItem(normalOrderId, productId);
        UUID cardId = insertCard(productId, "normal-vs-pickup-card");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> admin = executor.submit(() -> pickupAfter(start, ready, productId, operatorId));
            Future<Boolean> normal = executor.submit(() -> fulfillAfter(start, ready, normalOrderId));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            assertNotEquals(admin.get(20, TimeUnit.SECONDS), normal.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertEquals("SOLD", scalar("select status from card_keys where id = ?", String.class, cardId));
        assertEquals(1, number("select count(*) from card_keys where id = ? and order_id is not null and order_item_id is not null", cardId));
    }

    @Test
    void loggedInUserCanCreateNormalPaymentOrderDespiteMultiplePendingOrders() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        UUID userId = insertUser("pending-user-" + suffix, "pending-user-" + suffix + "@example.com");
        UUID productId = UUID.randomUUID();
        String clientIp = "203.0.113.40";
        String channel = "pending-user-" + suffix;
        insertProduct(productId);
        insertCard(productId, "pending-user-card-" + suffix);
        insertPaymentChannel(channel);
        for (int i = 0; i < 5; i++) {
            insertPendingOrder(channel, userId, clientIp);
        }
        configurePaymentCreation("pending-user-trade-" + suffix);

        Map<String, Object> result = orderService.createDirectOrder(
                directOrderRequest(productId, channel), userId, clientIp, "pending-user-session-" + suffix);

        assertNormalPendingPaymentOrder(result);
        verify(epayPaymentProvider, times(1)).create(any(PaymentProvider.PaymentRequest.class));
    }

    @Test
    void guestCanCreateNormalPaymentOrderDespiteMultiplePendingOrdersFromSameIp() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        UUID productId = UUID.randomUUID();
        String clientIp = "203.0.113.41";
        String channel = "pending-guest-" + suffix;
        insertProduct(productId);
        insertCard(productId, "pending-guest-card-" + suffix);
        insertPaymentChannel(channel);
        for (int i = 0; i < 5; i++) {
            insertPendingOrder(channel, null, clientIp);
        }
        configurePaymentCreation("pending-guest-trade-" + suffix);

        Map<String, Object> result = orderService.createDirectOrder(
                directOrderRequest(productId, channel),
                null, clientIp, "pending-guest-session-" + suffix);

        assertNormalPendingPaymentOrder(result);
        verify(epayPaymentProvider, times(1)).create(any(PaymentProvider.PaymentRequest.class));
    }

    @Test
    void guestCanCreateNormalPaymentOrderDespiteMultiplePendingOrdersAcrossIps() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        UUID productId = UUID.randomUUID();
        String channel = "pending-guest-cross-ip-" + suffix;
        insertProduct(productId);
        insertCard(productId, "pending-guest-cross-ip-card-" + suffix);
        insertPaymentChannel(channel);
        for (int i = 0; i < 5; i++) {
            insertPendingOrder(channel, null, "198.51.100." + (40 + i));
        }
        configurePaymentCreation("pending-guest-cross-ip-trade-" + suffix);

        Map<String, Object> result = orderService.createDirectOrder(
                directOrderRequest(productId, channel), null, "198.51.100.50",
                "pending-guest-cross-ip-session-" + suffix);

        assertNormalPendingPaymentOrder(result);
        verify(epayPaymentProvider, times(1)).create(any(PaymentProvider.PaymentRequest.class));
    }

    @Test
    void shortageRollsBackAllEarlierCardPoolMutations() {
        UUID orderId = insertOrder("rollback-session");
        UUID firstProductId = UUID.randomUUID();
        UUID secondProductId = UUID.randomUUID();
        insertProduct(firstProductId);
        insertProduct(secondProductId);
        UUID firstItemId = insertOrderItem(orderId, firstProductId);
        insertOrderItem(orderId, secondProductId);
        UUID cardId = insertCard(firstProductId, "rollback-card");

        assertThrows(BusinessException.class, () -> fulfillmentService.fulfillOrder(orderId));

        assertEquals("PAID", scalar("select status from orders where id = ?", String.class, orderId));
        assertEquals("AVAILABLE", scalar("select status from card_keys where id = ?", String.class, cardId));
        assertEquals(0, number("select count(*) from card_keys where order_id = ?", orderId));
        assertEquals(1, number("select count(*) from order_items where id = ? and order_id = ?", firstItemId, orderId));
    }

    @Test
    void automaticShortageDoesNotRollBackTheManualWaitingStateInAMixedOrder() {
        UUID orderId = insertOrder("mixed-shortage-session");
        UUID autoProductId = UUID.randomUUID();
        UUID manualProductId = UUID.randomUUID();
        insertProduct(autoProductId);
        insertManualProduct(manualProductId);
        insertOrderItem(orderId, autoProductId, "AUTO", "CARD_KEY", 1, 0);
        UUID manualItemId = insertOrderItem(orderId, manualProductId, "MANUAL", "MANUAL", 1, 1);
        insertManualStock(manualProductId, null, 0, 1);

        assertThrows(BusinessException.class, () -> fulfillmentService.fulfillOrder(orderId));

        assertEquals("PAID", scalar("select status from orders where id = ?", String.class, orderId));
        assertEquals("PENDING", scalar(
                "select fulfillment_status from order_items where product_id = ?", String.class, autoProductId));
        assertEquals("WAITING_MANUAL", scalar(
                "select fulfillment_status from order_items where id = ?", String.class, manualItemId));
        assertEquals(1, number(
                "select reserved_quantity from manual_stocks where product_id = ? and spec_id is null", manualProductId));
    }

    @Test
    void concurrentDeliveryOfOneOrderAllocatesExactlyOnce() throws Exception {
        UUID orderId = insertOrder("concurrent-session");
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        insertOrderItem(orderId, productId);
        insertCard(productId, "concurrent-card");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Map<String, Object>> first = executor.submit(() -> fulfillResultAfter(start, ready, orderId));
            Future<Map<String, Object>> second = executor.submit(() -> fulfillResultAfter(start, ready, orderId));
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();

            assertEquals("DELIVERED", first.get().get("status"));
            assertEquals("DELIVERED", second.get().get("status"));
        } finally {
            executor.shutdownNow();
        }

        assertEquals("DELIVERED", scalar("select status from orders where id = ?", String.class, orderId));
        assertEquals(1, number("select count(*) from card_keys where order_id = ? and status = 'SOLD'", orderId));
    }

    @Test
    void automaticItemsSharingOnePoolKeepTheirCardBoundaries() {
        UUID orderId = insertOrder("shared-pool-session");
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        UUID firstItemId = insertOrderItem(orderId, productId);
        UUID secondItemId = insertOrderItem(orderId, productId);
        insertCard(productId, "shared-pool-card-1");
        insertCard(productId, "shared-pool-card-2");

        Map<?, ?> delivery = fulfillmentService.fulfillOrder(orderId);

        assertEquals("DELIVERED", delivery.get("status"));
        assertEquals(2, number("select count(*) from card_keys where order_id = ? and status = 'SOLD'", orderId));
        assertEquals(1, number("select count(*) from card_keys where order_item_id = ?", firstItemId));
        assertEquals(1, number("select count(*) from card_keys where order_item_id = ?", secondItemId));
    }

    @Test
    void concurrentDuplicateCheckedImportsAddOnlyOneHistoricalFingerprint() throws Exception {
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Map<String, Object> importRequest = Map.of(
                    "product_id", productId.toString(),
                    "content_columns", List.of("same-card"),
                    "check_duplicate", true);
            Future<Map<String, Object>> first = executor.submit(() ->
                    adminCardKeyService.importCardKeys(importRequest, UUID.randomUUID()));
            Future<Map<String, Object>> second = executor.submit(() ->
                    adminCardKeyService.importCardKeys(importRequest, UUID.randomUUID()));

            Map<String, Object> firstResult = first.get(20, TimeUnit.SECONDS);
            Map<String, Object> secondResult = second.get(20, TimeUnit.SECONDS);
            assertEquals(1, ((Number) firstResult.get("success_count")).intValue()
                    + ((Number) secondResult.get("success_count")).intValue());
            assertEquals(1, ((Number) firstResult.get("duplicate_count")).intValue()
                    + ((Number) secondResult.get("duplicate_count")).intValue());
            assertEquals(1, number("select count(*) from card_keys where product_id = ?", productId));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentPaymentInitializationCreatesOneProviderPayment() throws Exception {
        UUID orderId = insertPendingOrder("payment-concurrency-channel");
        insertPaymentChannel("payment-concurrency-channel");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        AtomicInteger providerCreateCalls = new AtomicInteger();
        when(epayPaymentProvider.create(any(PaymentProvider.PaymentRequest.class)))
                .thenAnswer(invocation -> {
                    assertEquals(1, providerCreateCalls.incrementAndGet());
                    providerEntered.countDown();
                    assertTrue(releaseProvider.await(10, TimeUnit.SECONDS));
                    return new PaymentProvider.PaymentResult(
                            "payment-concurrency-trade",
                            new PaymentNextAction.QrCode("qr-payment-concurrency"));
                });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Map<String, Object>> first = executor.submit(() -> createPaymentAfter(
                    start, ready, orderId));
            Future<Map<String, Object>> second = executor.submit(() -> createPaymentAfter(
                    start, ready, orderId));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            // The first transaction holds the PostgreSQL row lock while the
            // provider call is paused. The second real Spring transaction must
            // wait for that lock and then reuse the committed gateway result.
            assertTrue(providerEntered.await(10, TimeUnit.SECONDS));
            releaseProvider.countDown();

            Map<String, Object> firstResult = first.get(20, TimeUnit.SECONDS);
            Map<String, Object> secondResult = second.get(20, TimeUnit.SECONDS);
            assertEquals(firstResult.get("next_action"), secondResult.get("next_action"));
            assertEquals(Map.of("type", "qr_code", "value", "qr-payment-concurrency"),
                    firstResult.get("next_action"));
            assertEquals(1, providerCreateCalls.get(), "Provider.create must run once");

            Map<String, Object> stored = jdbcTemplate.queryForMap(
                    "select merchant_trade_no, provider_trade_no, next_action from payment_attempts where order_id = ?",
                    orderId);
            assertTrue(String.valueOf(stored.get("merchant_trade_no")).startsWith("m_"));
            assertEquals("payment-concurrency-trade", stored.get("provider_trade_no"));
            assertEquals("{\"type\":\"qr_code\",\"value\":\"qr-payment-concurrency\"}",
                    stored.get("next_action"));
        } finally {
            releaseProvider.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void verifiedCallbackAutomaticallyDeliversWithoutBrowserRequest() {
        UUID orderId = insertPendingOrder("auto-channel");
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        insertOrderItem(orderId, productId);
        insertCard(productId, "auto-card");
        insertPaymentChannel("auto-channel");
        configurePaymentCreation("auto-trade");
        String merchantTradeNo = createAttempt(orderId, "auto-channel", "auto-trade");
        configureSuccessfulCallback("auto-trade");

        assertEquals("SUCCESS", webhookService.handleWebhook(
                "epay", callback(merchantTradeNo, "auto-trade")).body());
        assertEquals("DELIVERED", scalar("select status from orders where id = ?", String.class, orderId));
        assertEquals(1, number("select count(*) from card_keys where order_id = ? and status = 'SOLD'", orderId));

        assertEquals("SUCCESS", webhookService.handleWebhook(
                "epay", callback(merchantTradeNo, "auto-trade")).body());
        assertEquals(1, number("select count(*) from card_keys where order_id = ?", orderId));
    }

    @Test
    void shortageKeepsPaidFactAndRetryDeliversAfterStockIsRestored() {
        UUID orderId = insertPendingOrder("retry-channel");
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        insertOrderItem(orderId, productId);
        insertPaymentChannel("retry-channel");
        configurePaymentCreation("retry-trade");
        String merchantTradeNo = createAttempt(orderId, "retry-channel", "retry-trade");
        configureSuccessfulCallback("retry-trade");

        assertEquals("SUCCESS", webhookService.handleWebhook(
                "epay", callback(merchantTradeNo, "retry-trade")).body());
        assertEquals("PAID", scalar("select status from orders where id = ?", String.class, orderId));
        assertEquals(0, number("select count(*) from card_keys where order_id = ?", orderId));

        insertCard(productId, "retry-card");
        autoFulfillmentService.retryPendingPaidOrders();
        assertEquals("DELIVERED", scalar("select status from orders where id = ?", String.class, orderId));
        assertEquals(1, number("select count(*) from card_keys where order_id = ? and status = 'SOLD'", orderId));
    }

    @Test
    void guestIdempotencyReplayReturnsSameOrderAndSessionOwnership() {
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        insertCard(productId, "idempotent-card");
        insertPaymentChannel("idempotent-channel");
        when(epayPaymentProvider.create(any(PaymentProvider.PaymentRequest.class)))
                .thenReturn(new PaymentProvider.PaymentResult("idempotent-trade",
                        new PaymentNextAction.Redirect("https://pay.example/idempotent")));

        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("product_id", productId.toString());
        request.put("quantity", 1);
        request.put("payment_method", "ONLINE");
        request.put("payment_channel_code", "idempotent-channel");
        request.put("expected_unit_price", "10.00");
        request.put("idempotency_key", "guest-idempotency-key");
        request.put("idempotency_fingerprint", "fingerprint-1");

        Map<String, Object> first = orderService.createDirectOrder(request, null, "203.0.113.10", "guest-session-a");
        Map<String, Object> second = orderService.createDirectOrder(request, null, "203.0.113.10", "guest-session-a");
        Map<?, ?> firstOrder = (Map<?, ?>) first.get("order");
        Map<?, ?> secondOrder = (Map<?, ?>) second.get("order");

        assertEquals(firstOrder.get("id"), secondOrder.get("id"));
        assertEquals("guest-session-a", scalar("select session_token from orders where id = ?", String.class,
                firstOrder.get("id")));
        assertEquals(1, number("select count(*) from orders where idempotency_key = ?", "guest-idempotency-key"));

        Map<String, Object> differentSession = new java.util.LinkedHashMap<>(request);
        assertThrows(BusinessException.class,
                () -> orderService.createDirectOrder(differentSession, null, "203.0.113.11", "guest-session-b"));
        assertEquals(1, number("select count(*) from orders where idempotency_key = ?", "guest-idempotency-key"));
    }

    @Test
    void concurrentManualReservationsPreventOversellAndRecoveryIsExact() throws Exception {
        UUID productId = UUID.randomUUID();
        insertManualStock(productId, null, 1, 0);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> reserveAfter(start, ready, productId));
            Future<Boolean> second = executor.submit(() -> reserveAfter(start, ready, productId));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            assertNotEquals(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertEquals(0, number("select available_quantity from manual_stocks where product_id = ? and spec_id is null", productId));
        assertEquals(1, number("select reserved_quantity from manual_stocks where product_id = ? and spec_id is null", productId));

        manualStockService.release(productId, null, 1);
        assertEquals(1, number("select available_quantity from manual_stocks where product_id = ? and spec_id is null", productId));
        assertEquals(0, number("select reserved_quantity from manual_stocks where product_id = ? and spec_id is null", productId));
    }

    @Test
    void manualOnlyOrderWaitsForAdminAndIsNotSelectedByAutomaticRetry() {
        UUID orderId = insertOrder("manual-session");
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        UUID itemId = insertOrderItem(orderId, productId, "MANUAL", "MANUAL", 1, 1);
        insertManualStock(productId, null, 0, 1);

        Map<?, ?> delivery = fulfillmentService.fulfillOrder(orderId);
        assertEquals("PAID", delivery.get("status"));
        assertEquals("WAITING_MANUAL", scalar(
                "select fulfillment_status from order_items where id = ?", String.class, itemId));
        assertEquals(0, number("select count(*) from card_keys where order_id = ?", orderId));

        autoFulfillmentService.retryPendingPaidOrders();
        assertEquals("PAID", scalar("select status from orders where id = ?", String.class, orderId));
        assertEquals(0, number("select count(*) from card_keys where order_id = ?", orderId));

        Map<?, ?> completed = fulfillmentService.completeManualItems(orderId, List.of(
                new FulfillmentService.ManualFulfillmentItem(itemId, "账号：buyer\n\n已为你开通。")));
        assertEquals("DELIVERED", completed.get("status"));
        assertEquals("DELIVERED", scalar(
                "select fulfillment_status from order_items where id = ?", String.class, itemId));
        assertEquals("账号：buyer\n\n已为你开通。", scalar(
                "select customer_delivery_content from order_items where id = ?", String.class, itemId));
        assertEquals(0, number("select reserved_quantity from manual_stocks where product_id = ? and spec_id is null", productId));

        // A replay is rejected before any mutation and cannot consume the
        // same reservation or repeat the delivery transition.
        assertThrows(BusinessException.class, () -> fulfillmentService.completeManualItems(orderId, List.of(
                new FulfillmentService.ManualFulfillmentItem(itemId, "replacement"))));
        assertEquals(0, number("select reserved_quantity from manual_stocks where product_id = ? and spec_id is null", productId));
    }

    @Test
    void manualFulfillmentBatchRequiresEveryWaitingItemAndMutatesAtomically() {
        UUID orderId = insertOrder("manual-batch-session");
        UUID firstProductId = UUID.randomUUID();
        UUID secondProductId = UUID.randomUUID();
        insertProduct(firstProductId);
        insertProduct(secondProductId);
        UUID firstItemId = insertOrderItem(orderId, firstProductId, "MANUAL", "MANUAL", 1, 1);
        UUID secondItemId = insertOrderItem(orderId, secondProductId, "MANUAL", "MANUAL", 1, 1);
        insertManualStock(firstProductId, null, 0, 1);
        insertManualStock(secondProductId, null, 0, 1);

        fulfillmentService.fulfillOrder(orderId);
        assertEquals("WAITING_MANUAL", scalar(
                "select fulfillment_status from order_items where id = ?", String.class, firstItemId));
        assertEquals("WAITING_MANUAL", scalar(
                "select fulfillment_status from order_items where id = ?", String.class, secondItemId));

        assertThrows(BusinessException.class, () -> fulfillmentService.completeManualItems(orderId, List.of(
                new FulfillmentService.ManualFulfillmentItem(firstItemId, "only one"))));
        assertEquals("PAID", scalar("select status from orders where id = ?", String.class, orderId));
        assertEquals("WAITING_MANUAL", scalar(
                "select fulfillment_status from order_items where id = ?", String.class, firstItemId));
        assertEquals("WAITING_MANUAL", scalar(
                "select fulfillment_status from order_items where id = ?", String.class, secondItemId));
        assertNull(scalar("select customer_delivery_content from order_items where id = ?", String.class, firstItemId));
        assertNull(scalar("select customer_delivery_content from order_items where id = ?", String.class, secondItemId));
        assertEquals(1, number(
                "select reserved_quantity from manual_stocks where product_id = ? and spec_id is null", firstProductId));
        assertEquals(1, number(
                "select reserved_quantity from manual_stocks where product_id = ? and spec_id is null", secondProductId));

        Map<?, ?> completed = fulfillmentService.completeManualItems(orderId, List.of(
                new FulfillmentService.ManualFulfillmentItem(firstItemId, "first result"),
                new FulfillmentService.ManualFulfillmentItem(secondItemId, "second result")));
        assertEquals("DELIVERED", completed.get("status"));
        assertEquals("DELIVERED", scalar("select status from orders where id = ?", String.class, orderId));
        assertEquals("first result", scalar(
                "select customer_delivery_content from order_items where id = ?", String.class, firstItemId));
        assertEquals("second result", scalar(
                "select customer_delivery_content from order_items where id = ?", String.class, secondItemId));
        assertEquals(0, number(
                "select reserved_quantity from manual_stocks where product_id = ? and spec_id is null", firstProductId));
        assertEquals(0, number(
                "select reserved_quantity from manual_stocks where product_id = ? and spec_id is null", secondProductId));
    }

    @Test
    void mixedOrderDeliversAutomaticItemWhileManualItemWaits() {
        UUID orderId = insertOrder("mixed-session");
        UUID autoProductId = UUID.randomUUID();
        UUID manualProductId = UUID.randomUUID();
        insertProduct(autoProductId);
        insertProduct(manualProductId);
        UUID autoItemId = insertOrderItem(orderId, autoProductId, "AUTO", "CARD_KEY", 1, 0);
        UUID manualItemId = insertOrderItem(orderId, manualProductId, "MANUAL", "MANUAL", 1, 1);
        insertCard(autoProductId, "mixed-auto-card");
        insertManualStock(manualProductId, null, 0, 1);

        Map<?, ?> first = fulfillmentService.fulfillOrder(orderId);
        assertEquals("PAID", first.get("status"));
        assertEquals("DELIVERED", scalar("select fulfillment_status from order_items where id = ?", String.class, autoItemId));
        assertEquals("WAITING_MANUAL", scalar("select fulfillment_status from order_items where id = ?", String.class, manualItemId));
        assertEquals(1, number("select count(*) from card_keys where order_id = ? and status = 'SOLD'", orderId));
        verify(emailService, times(1)).sendDeliveryEmail(eq(orderId), eq(List.of(autoItemId)));

        // A retry while the manual lane is still waiting must not resend the
        // already-completed automatic item or its card body.
        fulfillmentService.fulfillOrder(orderId);
        verify(emailService, times(1)).sendDeliveryEmail(eq(orderId), eq(List.of(autoItemId)));

        Map<?, ?> second = fulfillmentService.completeManualItems(orderId, List.of(
                new FulfillmentService.ManualFulfillmentItem(manualItemId, null)));
        assertEquals("DELIVERED", second.get("status"));
        assertEquals("DELIVERED", scalar("select status from orders where id = ?", String.class, orderId));
        verify(emailService, times(1)).sendDeliveryEmail(eq(orderId), eq(List.of(manualItemId)));
    }

    @Test
    void expiredManualOrderReleasesReservationOnce() {
        UUID orderId = insertExpiredOrder("expired-manual-session");
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        UUID itemId = insertOrderItem(orderId, productId, "MANUAL", "MANUAL", 2, 2);
        insertManualStock(productId, null, 0, 2);

        orderService.expireOrders();
        assertEquals("EXPIRED", scalar("select status from orders where id = ?", String.class, orderId));
        assertEquals("CANCELLED", scalar("select fulfillment_status from order_items where id = ?", String.class, itemId));
        assertEquals(0, number("select manual_stock_reserved from order_items where id = ?", itemId));
        assertEquals(2, number("select available_quantity from manual_stocks where product_id = ? and spec_id is null", productId));
        assertEquals(0, number("select reserved_quantity from manual_stocks where product_id = ? and spec_id is null", productId));

        orderService.expireOrders();
        assertEquals(2, number("select available_quantity from manual_stocks where product_id = ? and spec_id is null", productId));
        assertEquals(0, number("select reserved_quantity from manual_stocks where product_id = ? and spec_id is null", productId));
    }

    private Map<String, Object> fulfillResultAfter(CountDownLatch start, CountDownLatch ready,
                                                    UUID orderId) throws Exception {
        ready.countDown();
        start.await();
        return fulfillmentService.fulfillOrder(orderId);
    }

    private boolean pickupAfter(CountDownLatch start, CountDownLatch ready,
                                UUID productId, UUID operatorId) throws Exception {
        ready.countDown();
        start.await();
        try {
            adminCardKeyService.batchPickup(
                    new AdminCardKeyBatchPickupRequest(productId, null, 1), operatorId);
            return true;
        } catch (BusinessException exception) {
            return false;
        }
    }

    private boolean fulfillAfter(CountDownLatch start, CountDownLatch ready,
                                UUID orderId) throws Exception {
        ready.countDown();
        start.await();
        try {
            fulfillmentService.fulfillOrder(orderId);
            return true;
        } catch (BusinessException exception) {
            return false;
        }
    }

    private boolean reserveAfter(CountDownLatch start, CountDownLatch ready, UUID productId) throws Exception {
        ready.countDown();
        start.await();
        try {
            manualStockService.reserveOrThrow(productId, null, 1);
            return true;
        } catch (BusinessException exception) {
            return false;
        }
    }

    private Map<String, Object> createPaymentAfter(CountDownLatch start, CountDownLatch ready,
                                                   UUID orderId) throws Exception {
        ready.countDown();
        start.await();
        return paymentService.createPayment(orderId, "payment-concurrency-channel",
                new BigDecimal("10.00"), "pc");
    }

    private void assertSearchMatches(String keyword, UUID expectedOrderId) {
        var result = adminOrderService.searchOrders(new AdminOrderSearchRequest(
                1, 20, keyword, null, null, null, "desc"));
        assertEquals(1L, result.getPagination().getTotal(), "keyword=" + keyword);
        assertEquals(1, result.getList().size(), "keyword=" + keyword);
        Map<?, ?> row = (Map<?, ?>) result.getList().get(0);
        assertEquals(expectedOrderId, row.get("id"), "keyword=" + keyword);
    }

    private void assertSearchContains(String keyword, UUID expectedOrderId) {
        var result = adminOrderService.searchOrders(new AdminOrderSearchRequest(
                1, 20, keyword, null, null, null, "desc"));
        assertTrue(result.getPagination().getTotal() >= 1, "keyword=" + keyword);
        assertTrue(result.getList().stream().anyMatch(value -> {
            Map<?, ?> row = (Map<?, ?>) value;
            return expectedOrderId.equals(row.get("id"));
        }), "keyword=" + keyword);
    }

    private void assertSearchDoesNotMatch(String keyword) {
        var result = adminOrderService.searchOrders(new AdminOrderSearchRequest(
                1, 20, keyword, null, null, null, "desc"));
        assertEquals(0L, result.getPagination().getTotal(), "keyword=" + keyword);
        assertTrue(result.getList().isEmpty(), "keyword=" + keyword);
    }

    private UUID insertSearchOrder(UUID userId, String tradeNo,
                                   LocalDateTime createdAt, String status, BigDecimal amount) {
        UUID orderId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO orders(id, created_at, updated_at, user_id, total_amount, actual_amount,
                                   status, order_type, payment_method, payment_channel_code, paid_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'NORMAL', 'ONLINE', ?, ?)
                """, orderId, createdAt, createdAt, userId, amount, amount, status,
                "search-channel-" + tradeNo, createdAt);
        jdbcTemplate.update("""
                INSERT INTO payment_attempts(id, created_at, updated_at, order_id, channel_id,
                    channel_code_snapshot, provider_type, payment_type, provider_config_snapshot,
                    merchant_trade_no, provider_trade_no, amount, status, next_action)
                VALUES (?, ?, ?, ?, ?, ?, 'epay', 'alipay',
                    '{\"pid\":\"pid\",\"key\":\"secret\",\"api_url\":\"https://pay.example\"}',
                    ?, ?, ?, 'PAID', '{\"type\":\"redirect\",\"url\":\"https://pay.example/attempt\"}')
                """, UUID.randomUUID(), createdAt, createdAt, orderId, UUID.randomUUID(),
                "search-channel-" + tradeNo, "merchant-" + tradeNo, tradeNo, amount);
        return orderId;
    }

    private UUID insertOrder(String sessionToken) {
        UUID orderId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into orders (id, created_at, updated_at, actual_amount, total_amount, status,
                    order_type, payment_method, payment_channel_code, session_token)
                values (?, current_timestamp, current_timestamp, 10.00, 10.00, 'PAID',
                    'NORMAL', 'ONLINE', 'integration-channel', ?)
                """, orderId, sessionToken);
        return orderId;
    }

    private UUID insertAdminUser(String username, String email) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into users (id, created_at, updated_at, username, email, password_hash,
                    role, is_deleted, user_no)
                values (?, current_timestamp, current_timestamp, ?, ?, 'integration-hash', 'ADMIN', 0, null)
                """, userId, username, email);
        return userId;
    }

    private UUID insertUser(String username, String email) {
        UUID userId = UUID.randomUUID();
        Long userNo = jdbcTemplate.queryForObject(
                "select coalesce(max(user_no), 9999) + 1 from users", Long.class);
        UUID userLevelId = jdbcTemplate.queryForObject(
                "select id from user_levels where is_default = true", UUID.class);
        jdbcTemplate.update("""
                insert into users (id, created_at, updated_at, username, email, password_hash,
                    role, is_deleted, user_no, user_level_id)
                values (?, current_timestamp, current_timestamp, ?, ?, 'integration-hash', 'USER', 0, ?, ?)
                """, userId, username, email, userNo, userLevelId);
        return userId;
    }

    private UUID insertPendingOrder(String paymentChannelCode) {
        UUID orderId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into orders (id, created_at, updated_at, actual_amount, total_amount, status,
                    order_type, payment_method, payment_channel_code, expires_at)
                values (?, current_timestamp, current_timestamp, 10.00, 10.00, 'PENDING',
                    'NORMAL', 'ONLINE', ?,
                    current_timestamp + interval '15 minutes')
                """, orderId, paymentChannelCode);
        return orderId;
    }

    private UUID insertPendingOrder(String paymentChannelCode, UUID userId, String clientIp) {
        UUID orderId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into orders (id, created_at, updated_at, user_id, actual_amount, total_amount,
                    status, order_type, payment_method, payment_channel_code, expires_at, client_ip)
                values (?, current_timestamp, current_timestamp, ?, 10.00, 10.00, 'PENDING',
                    'NORMAL', 'ONLINE', ?,
                    current_timestamp + interval '15 minutes', ?)
                """, orderId, userId, paymentChannelCode, clientIp);
        return orderId;
    }

    private UUID insertExpiredOrder(String sessionToken) {
        UUID orderId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into orders (id, created_at, updated_at, actual_amount, total_amount, status,
                    order_type, payment_method, payment_channel_code, session_token, expires_at)
                values (?, current_timestamp, current_timestamp, 10.00, 10.00, 'PENDING',
                    'NORMAL', 'ONLINE', 'integration-channel', ?,
                    current_timestamp - interval '1 minute')
                """, orderId, sessionToken);
        return orderId;
    }

    private void insertProduct(UUID productId) {
        UUID categoryId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into product_categories (id, created_at, updated_at, name, sort_order, is_deleted)
                values (?, current_timestamp, current_timestamp, ?, 0, 0)
                """, categoryId, "Integration category " + productId);
        jdbcTemplate.update("""
                insert into products (id, created_at, updated_at, title, product_no, retail_price, cost_price, category_id,
                    delivery_type, is_enabled, is_deleted)
                values (?, current_timestamp, current_timestamp, 'Integration product',
                    nextval('product_no_sequence'), 10.00, 10.00, ?,
                    'AUTO', true, 0)
                """, productId, categoryId);
    }

    private void insertUnlimitedProduct(UUID productId) {
        UUID categoryId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into product_categories (id, created_at, updated_at, name, sort_order, is_deleted)
                values (?, current_timestamp, current_timestamp, ?, 0, 0)
                """, categoryId, "Unlimited integration category " + productId);
        jdbcTemplate.update("""
                insert into products (id, created_at, updated_at, title, product_no, retail_price, cost_price, category_id,
                    delivery_type, stock_mode, is_enabled, is_deleted)
                values (?, current_timestamp, current_timestamp, 'Unlimited integration product',
                    nextval('product_no_sequence'), 10.00, 10.00, ?,
                    'AUTO', 'UNLIMITED', true, 0)
                """, productId, categoryId);
    }

    private void insertManualProduct(UUID productId) {
        UUID categoryId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into product_categories (id, created_at, updated_at, name, sort_order, is_deleted)
                values (?, current_timestamp, current_timestamp, ?, 0, 0)
                """, categoryId, "Manual integration category " + productId);
        jdbcTemplate.update("""
                insert into products (id, created_at, updated_at, title, product_no, retail_price, cost_price, category_id,
                    delivery_type, stock_mode, is_enabled, is_deleted)
                values (?, current_timestamp, current_timestamp, 'Manual integration product',
                    nextval('product_no_sequence'), 10.00, 10.00, ?,
                    'MANUAL', 'MANUAL', true, 0)
                """, productId, categoryId);
    }

    private void setBalance(UUID userId, BigDecimal balance) {
        jdbcTemplate.update("UPDATE users SET balance = ? WHERE id = ?", balance, userId);
    }

    private Map<String, Object> balanceOrderRequest(UUID productId, String idempotencyKey,
                                                     String idempotencyFingerprint) {
        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("product_id", productId.toString());
        request.put("quantity", 1);
        request.put("payment_method", "BALANCE");
        request.put("expected_unit_price", "10.00");
        request.put("idempotency_key", idempotencyKey);
        request.put("idempotency_fingerprint", idempotencyFingerprint);
        return request;
    }

    private boolean tryBalanceOrder(CountDownLatch ready, CountDownLatch start,
                                    UUID productId, UUID userId, String idempotencyKey,
                                    String idempotencyFingerprint) throws InterruptedException {
        ready.countDown();
        if (!start.await(20, TimeUnit.SECONDS)) {
            throw new IllegalStateException("balance concurrency test did not start");
        }
        try {
            orderService.createDirectOrder(
                    balanceOrderRequest(productId, idempotencyKey, idempotencyFingerprint),
                    userId, "203.0.113.36", idempotencyKey + "-session");
            return true;
        } catch (BusinessException exception) {
            if (exception.getCode() == ErrorCode.INSUFFICIENT_BALANCE) return false;
            throw exception;
        }
    }

    private void insertPaymentChannel(String channelCode) {
        jdbcTemplate.update("""
                insert into payment_channels (id, created_at, updated_at, channel_code, channel_name,
                    provider_type, payment_type, config_data, is_enabled, sort_order, is_deleted)
                values (?, current_timestamp, current_timestamp, ?, ?, 'epay', 'wxpay', ?, true, 0, 0)
                """, UUID.randomUUID(), channelCode, channelCode,
                "{\"pid\":\"pid\",\"key\":\"secret\",\"api_url\":\"https://pay.example\"}");
    }

    private void configureSuccessfulCallback(String tradeNo) {
        when(epayPaymentProvider.supportsServerSideQuery()).thenReturn(true);
        when(epayPaymentProvider.extractMerchantTradeNo(any(PaymentProvider.WebhookRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0, PaymentProvider.WebhookRequest.class)
                        .parameter("out_trade_no"));
        when(epayPaymentProvider.verifyAndParseCallback(
                any(PaymentProvider.WebhookRequest.class), anyMap()))
                .thenAnswer(invocation -> {
                    PaymentProvider.WebhookRequest request = invocation.getArgument(0);
                    String merchantTradeNo = request.parameter("out_trade_no");
                    return new PaymentProvider.CallbackResult("epay:" + tradeNo + ":" + merchantTradeNo,
                            merchantTradeNo, tradeNo, PaymentStatus.PAID, new BigDecimal("10.00"));
                });
        when(epayPaymentProvider.query(any(PaymentProvider.PaymentQueryRequest.class)))
                .thenReturn(new PaymentProvider.PaymentQueryResult(
                        PaymentStatus.PAID, new BigDecimal("10.00"), tradeNo));
    }

    private void configurePaymentCreation(String tradeNo) {
        when(epayPaymentProvider.create(any(PaymentProvider.PaymentRequest.class)))
                .thenReturn(new PaymentProvider.PaymentResult(
                        tradeNo, new PaymentNextAction.Redirect("https://pay.example/" + tradeNo)));
    }

    private Map<String, Object> directOrderRequest(UUID productId, String paymentMethod) {
        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("product_id", productId.toString());
        request.put("quantity", 1);
        request.put("payment_method", "ONLINE");
        request.put("payment_channel_code", paymentMethod);
        request.put("expected_unit_price", "10.00");
        return request;
    }

    private void assertNormalPendingPaymentOrder(Map<String, Object> result) {
        Map<?, ?> order = (Map<?, ?>) result.get("order");
        Map<?, ?> payment = (Map<?, ?>) result.get("payment");
        UUID orderId = (UUID) order.get("id");

        assertEquals(OrderType.NORMAL, order.get("order_type"));
        assertNotNull(order.get("order_no"));
        assertEquals(order.get("order_no"), payment.get("order_no"));
        assertEquals("PENDING", order.get("status"));
        assertEquals("PENDING", scalar("select status from orders where id = ?", String.class, orderId));
        Map<?, ?> nextAction = (Map<?, ?>) payment.get("next_action");
        assertEquals("redirect", nextAction.get("type"));
        assertTrue(String.valueOf(nextAction.get("url")).startsWith("https://pay.example/"));
    }

    private PaymentProvider.WebhookRequest callback(String merchantTradeNo, String tradeNo) {
        return new PaymentProvider.WebhookRequest("POST", Map.of(), Map.of(), Map.of(
                "trade_no", List.of(tradeNo),
                "out_trade_no", List.of(merchantTradeNo),
                "trade_status", List.of("TRADE_SUCCESS"),
                "money", List.of("10.00"),
                "sign", List.of("valid-signature")), "");
    }

    private String createAttempt(UUID orderId, String channel, String tradeNo) {
        Map<String, Object> result = paymentService.createPayment(
                orderId, channel, new BigDecimal("10.00"), "pc");
        UUID paymentId = (UUID) result.get("payment_id");
        return scalar("select merchant_trade_no from payment_attempts where id = ?",
                String.class, paymentId);
    }

    private UUID insertOrderItem(UUID orderId, UUID productId) {
        return insertOrderItem(orderId, productId, "AUTO", "CARD_KEY", 1, 0);
    }

    private UUID insertOrderItem(UUID orderId, UUID productId, String deliveryType,
                                 String stockMode, int quantity, int manualStockReserved) {
        UUID itemId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into order_items (id, created_at, updated_at, order_id, product_id, product_no,
                    product_title, quantity, unit_price, cost_price_snapshot, retail_price_snapshot, pricing_source, subtotal, delivery_type,
                    stock_mode, fulfillment_status, manual_stock_reserved)
                values (?, current_timestamp, current_timestamp, ?, ?, (select product_no from products where id = ?), 'Integration product', ?, 10.00, 10.00, 10.00,
                    'RETAIL', ?, ?, ?, 'PENDING', ?)
                """, itemId, orderId, productId, productId, quantity,
                BigDecimal.TEN.multiply(BigDecimal.valueOf(quantity)), deliveryType,
                stockMode, manualStockReserved);
        return itemId;
    }

    private void insertManualStock(UUID productId, UUID specId, int available, int reserved) {
        jdbcTemplate.update("""
                insert into manual_stocks (id, created_at, updated_at, product_id, spec_id,
                    available_quantity, reserved_quantity)
                values (?, current_timestamp, current_timestamp, ?, ?, ?, ?)
                """, UUID.randomUUID(), productId, specId, available, reserved);
    }

    private UUID insertCard(UUID productId, String content) {
        UUID cardId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into card_keys (id, created_at, updated_at, product_id, content_parts,
                                       content_fingerprint, status)
                values (?, current_timestamp, current_timestamp, ?, jsonb_build_array(?), ?, 'AVAILABLE')
                """, cardId, productId, content, CardContentParser.fingerprint(List.of(content)));
        return cardId;
    }

    private UUID insertCardParts(UUID productId, List<String> parts) {
        if (parts.size() != 2) {
            throw new IllegalArgumentException("This test helper expects exactly two card parts");
        }
        UUID cardId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into card_keys (id, created_at, updated_at, product_id, content_parts,
                                       content_fingerprint, status)
                values (?, current_timestamp, current_timestamp, ?, jsonb_build_array(?, ?), ?, 'AVAILABLE')
                """, cardId, productId, parts.get(0), parts.get(1), CardContentParser.fingerprint(parts));
        return cardId;
    }

    private int number(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    private <T> T scalar(String sql, Class<T> type, Object... args) {
        return jdbcTemplate.queryForObject(sql, type, args);
    }
}
