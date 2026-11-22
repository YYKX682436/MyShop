package com.myshop.service.impl;

import com.myshop.constant.ErrorCode;
import com.myshop.context.RequestContext;
import com.myshop.exception.BusinessException;
import com.myshop.model.request.ChangePasswordRequest;
import com.myshop.model.request.CreateAdministratorRequest;
import com.myshop.model.request.LoginRequest;
import com.myshop.model.request.RegisterRequest;
import com.myshop.model.request.UpdateAdministratorRequest;
import com.myshop.payment.EpayPaymentProvider;
import com.myshop.payment.PaymentNextAction;
import com.myshop.payment.PaymentProvider;
import com.myshop.payment.PaymentStatus;
import com.myshop.service.*;
import com.myshop.utils.CardContentParser;
import com.myshop.utils.CaptchaUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Runs on PostgreSQL 16 in CI. A disposable external PostgreSQL may be supplied
 * locally via myshop.test.jdbc-url / jdbc-user / jdbc-password; no H2 substitute.
 */
@EnabledIf("postgresAvailable")
@SpringBootTest
class CoreStabilityPostgresIntegrationTest {
    private static PostgreSQLContainer<?> postgres;

    static boolean postgresAvailable() {
        return !System.getProperty("myshop.test.jdbc-url", "").isBlank()
                || DockerClientFactory.instance().isDockerAvailable();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String externalUrl = System.getProperty("myshop.test.jdbc-url", "");
        if (externalUrl.isBlank()) {
            postgres = new PostgreSQLContainer<>("postgres:16-alpine");
            postgres.start();
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
        } else {
            registry.add("spring.datasource.url", () -> externalUrl);
            registry.add("spring.datasource.username", () -> System.getProperty("myshop.test.jdbc-user", "postgres"));
            registry.add("spring.datasource.password", () -> System.getProperty("myshop.test.jdbc-password", ""));
        }
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.open-in-view", () -> false);
        registry.add("jwt.secret", () -> "core-stability-test-secret-at-least-64-bytes-long-and-not-a-production-secret");
        registry.add("mail.enabled", () -> false);
        registry.add("bootstrap.admin.username", () -> "");
        registry.add("bootstrap.admin.email", () -> "");
        registry.add("bootstrap.admin.password", () -> "");
        registry.add("bootstrap.super-admin.username", () -> "");
        registry.add("bootstrap.super-admin.password", () -> "");
        registry.add("fulfillment.retry-initial-delay-ms", () -> 3600000);
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) postgres.stop();
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired AuthService authService;
    @Autowired AdministratorService administrators;
    @Autowired UserService userService;
    @Autowired UserAvatarService avatarService;
    @Autowired AdminCardKeyService cards;
    @Autowired OrderService orders;
    @Autowired OrderAccessService orderAccess;
    @Autowired PaymentService payments;
    @Autowired AutoFulfillmentService fulfillment;
    @MockitoBean EmailService emailService;
    @MockitoBean ImageUploadService images;
    @MockitoBean EpayPaymentProvider provider;
    @MockitoBean CaptchaUtils captchas;

    @BeforeEach
    void configureProvider() {
        lenient().when(provider.id()).thenReturn("epay");
        lenient().when(provider.paymentTypes()).thenReturn(List.of("alipay", "wxpay"));
    }

    @Test
    void loginPreservesTheBalanceAndAuthVersionCommittedWhileItWaits() throws Exception {
        Account account = account();
        LoginRequest request = new LoginRequest();
        request.setAccount(account.username());
        request.setPassword("old-password");

        afterConcurrentWrite("update users set balance = 90, auth_version = 7 where id = ?",
                List.of(account.id()), "users", () -> authService.login(request, "203.0.113.1"));

        assertAccount(account.id(), 7);
        assertNotNull(scalar("select last_login_at from users where id = ?", Timestamp.class, account.id()));
    }

    @Test
    void passwordChangeUsesTheFreshVersionAndDoesNotRestoreAnOldBalance() throws Exception {
        Account account = account();
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setOldPassword("old-password");
        request.setNewPassword("new-password");

        afterConcurrentWrite("update users set balance = 90, auth_version = 7 where id = ?",
                List.of(account.id()), "users", () -> {
                    userService.changePassword(account.id(), request);
                    return null;
                });

        assertAccount(account.id(), 8);
        assertTrue(passwordEncoder.matches("new-password",
                scalar("select password_hash from users where id = ?", String.class, account.id())));
    }

    @Test
    void avatarSavePreservesAConcurrentPasswordChangeAndBalance() throws Exception {
        Account account = account();
        String newHash = passwordEncoder.encode("changed-elsewhere");
        String avatarUrl = "/uploads/avatars/" + UUID.randomUUID() + ".webp";
        when(images.uploadAvatar(any())).thenReturn(new ImageUploadService.UploadedImage(avatarUrl, "avatar.webp"));

        afterConcurrentWrite("update users set balance = 90, auth_version = 7, password_hash = ? where id = ?",
                List.of(newHash, account.id()), "users", () -> avatarService.uploadAvatar(account.id(),
                        new MockMultipartFile("file", "avatar.png", "image/png", new byte[]{1})));

        assertAccount(account.id(), 7);
        assertEquals(newHash, scalar("select password_hash from users where id = ?", String.class, account.id()));
        assertEquals(avatarUrl, scalar("select avatar_url from users where id = ?", String.class, account.id()));
    }

    @Test
    void singleCardInvalidationCannotOverwriteAConcurrentSale() throws Exception {
        UUID product = product("CARD_KEY");
        UUID order = order("NORMAL", "PAID", null, LocalDateTime.now().plusMinutes(15));
        UUID item = item(order, product, "CARD_KEY", 0);
        UUID card = card(product);

        afterConcurrentWrite("update card_keys set status = 'SOLD', order_id = ?, order_item_id = ?, sold_at = current_timestamp where id = ?",
                List.of(order, item, card), "card_keys", () -> {
                    BusinessException error = assertThrows(BusinessException.class, () -> cards.invalidateCardKey(card));
                    assertEquals(ErrorCode.BAD_REQUEST, error.getCode());
                    return null;
                });

        assertEquals("SOLD", scalar("select status from card_keys where id = ?", String.class, card));
        assertEquals(order, scalar("select order_id from card_keys where id = ?", UUID.class, card));
        assertEquals(item, scalar("select order_item_id from card_keys where id = ?", UUID.class, card));
    }

    @Test
    void retryPassReachesBeyondFiftyFailuresAndLeavesRetiredChannelOrdersUntouched() {
        UUID shortProduct = product("CARD_KEY");
        for (int i = 0; i < 50; i++) {
            UUID order = order("NORMAL", "PAID", null, null);
            jdbc.update("update orders set created_at = ? where id = ?",
                    Timestamp.valueOf(LocalDateTime.now().minusDays(2).plusSeconds(i)), order);
            item(order, shortProduct, "CARD_KEY", 0);
        }
        UUID normalProduct = product("CARD_KEY");
        UUID normal = order("NORMAL", "PAID", null, null);
        item(normal, normalProduct, "CARD_KEY", 0);
        UUID normalCard = card(normalProduct);
        UUID adminProduct = product("CARD_KEY");
        UUID admin = order("ADMIN_PICKUP", "PAID", null, null);
        item(admin, adminProduct, "CARD_KEY", 0);
        UUID adminCard = card(adminProduct);
        UUID retiredProduct = product("CARD_KEY");
        UUID retired = order("SALES_CHANNEL", "PAID", null, null);
        item(retired, retiredProduct, "CARD_KEY", 0);
        UUID retiredCard = card(retiredProduct);

        fulfillment.retryPendingPaidOrders();

        assertEquals("DELIVERED", scalar("select status from orders where id = ?", String.class, normal));
        assertEquals("DELIVERED", scalar("select status from orders where id = ?", String.class, admin));
        assertEquals("SOLD", scalar("select status from card_keys where id = ?", String.class, normalCard));
        assertEquals("SOLD", scalar("select status from card_keys where id = ?", String.class, adminCard));
        assertEquals("PAID", scalar("select status from orders where id = ?", String.class, retired));
        assertEquals("AVAILABLE", scalar("select status from card_keys where id = ?", String.class, retiredCard));
    }

    @Test
    void orderHistoryExpiryClosesAttemptsAndReleasesManualStockExactlyOnce() {
        Account account = account();
        ExpiringOrder fixture = expiringOrder(account.id());

        userService.getOrders(account.id(), null, 1, 20);
        orders.expireOrders();

        assertExpired(fixture);
    }

    @Test
    void expiredRepaymentCommitsItsClosureEvenThoughTheRequestIsRejected() {
        ExpiringOrder fixture = expiringOrder(null);

        BusinessException error = assertThrows(BusinessException.class,
                () -> orderAccess.repayOrder(fixture.orderId(), "pc", null, "core-session"));

        assertEquals(ErrorCode.ORDER_EXPIRED, error.getCode());
        assertExpired(fixture);
    }

    @Test
    void idempotentReplayCannotExpireAnOrderPaidWhileTheReplayWaits() throws Exception {
        UUID order = order("NORMAL", "PENDING", null, LocalDateTime.now().minusMinutes(1));
        String key = "core-replay-" + UUID.randomUUID();
        jdbc.update("update orders set idempotency_key = ?, idempotency_fingerprint = 'same-request' where id = ?", key, order);
        UUID attempt = attempt(order);
        jdbc.update("update payment_attempts set status = 'PAID' where id = ?", attempt);

        afterConcurrentWrite("update orders set status = 'PAID', paid_at = current_timestamp where id = ?",
                List.of(order), "orders", () -> {
                    Map<String, Object> replay = orders.createDirectOrder(
                            Map.of("idempotency_key", key, "idempotency_fingerprint", "same-request"),
                            null, "203.0.113.1", "core-session");
                    assertEquals("PAID", ((Map<?, ?>) replay.get("order")).get("status"));
                    assertNull(((Map<?, ?>) replay.get("payment")).get("next_action"));
                    return null;
                });

        assertEquals("PAID", scalar("select status from orders where id = ?", String.class, order));
        assertEquals("PAID", scalar("select status from payment_attempts where id = ?", String.class, attempt));
        assertEquals(1, number("select count(*) from payment_attempts where order_id = ?", order));
    }

    @Test
    void theNewestAttemptEnforcesRepayCooldownEvenWithAnOldOrderTimestamp() {
        UUID order = order("NORMAL", "PENDING", null, LocalDateTime.now().plusMinutes(15));
        UUID original = attempt(order);
        jdbc.update("update payment_attempts set created_at = current_timestamp - interval '1 minute' where id = ?", original);
        jdbc.update("update orders set updated_at = current_timestamp - interval '5 minutes' where id = ?", order);
        when(provider.create(any())).thenReturn(new PaymentProvider.PaymentResult("core-repay-trade",
                new PaymentNextAction.Redirect("https://pay.example/checkout")));

        payments.repay(order, "pc");
        BusinessException error = assertThrows(BusinessException.class, () -> payments.repay(order, "pc"));

        assertEquals(ErrorCode.BAD_REQUEST, error.getCode());
        assertEquals(2, number("select count(*) from payment_attempts where order_id = ?", order));
        assertEquals(1, number("select count(*) from payment_attempts where order_id = ? and status = 'PENDING'", order));
        verify(provider).create(any());
    }

    @Test
    void providerQueryHoldsNoOrderLockAndCannotReviveAnAttemptClosedDuringTheCall() throws Exception {
        UUID order = order("NORMAL", "PENDING", null, LocalDateTime.now().plusMinutes(15));
        UUID attempt = attempt(order);
        CountDownLatch queryStarted = new CountDownLatch(1);
        CountDownLatch releaseQuery = new CountDownLatch(1);
        when(provider.supportsServerSideQuery()).thenReturn(true);
        when(provider.query(any())).thenAnswer(invocation -> {
            queryStarted.countDown();
            assertTrue(releaseQuery.await(10, TimeUnit.SECONDS), "provider query was never released");
            return new PaymentProvider.PaymentQueryResult(PaymentStatus.PAID, new BigDecimal("10.00"), "core-trade");
        });
        try (var workers = Executors.newFixedThreadPool(2)) {
            Future<?> refresh = workers.submit(() -> orderAccess.refreshOrder(order, null, "core-session"));
            try {
                assertTrue(queryStarted.await(10, TimeUnit.SECONDS));
                Future<?> concurrentClose = workers.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                    jdbc.queryForObject("select id from orders where id = ? for update", UUID.class, order);
                    jdbc.update("update payment_attempts set status = 'CLOSED' where id = ?", attempt);
                    return null;
                }));
                concurrentClose.get(3, TimeUnit.SECONDS);
            } finally {
                releaseQuery.countDown();
            }
            refresh.get(10, TimeUnit.SECONDS);
        }
        assertEquals("PENDING", scalar("select status from orders where id = ?", String.class, order));
        assertEquals("CLOSED", scalar("select status from payment_attempts where id = ?", String.class, attempt));
    }

    @Test
    void numericAdministratorUsernameAndRegistrationPhoneCannotClaimTheSameIdentity() throws Exception {
        String identity = "13800138101";
        UUID operator = administrator();
        CreateAdministratorRequest create = new CreateAdministratorRequest();
        create.setUsername(identity);
        create.setPassword("admin-test-password");
        RegisterRequest registration = registration("identity_buyer", identity);
        CountDownLatch adminWritten = new CountDownLatch(1);
        CountDownLatch commitAdmin = new CountDownLatch(1);

        try (var workers = Executors.newFixedThreadPool(2)) {
            Future<?> admin = workers.submit(() -> asAdministrator(operator, () ->
                    new TransactionTemplate(transactionManager).execute(status -> {
                        administrators.createAdministrator(create);
                        adminWritten.countDown();
                        awaitLatch(commitAdmin);
                        return null;
                    })));
            Future<BusinessException> rejected;
            try {
                assertTrue(adminWritten.await(10, TimeUnit.SECONDS));
                rejected = workers.submit(() -> assertThrows(BusinessException.class,
                        () -> authService.register(registration, "203.0.113.2")));
                awaitDatabaseLock(rejected, "user_number_sequences");
            } finally {
                commitAdmin.countDown();
            }
            admin.get(10, TimeUnit.SECONDS);
            BusinessException conflict = rejected.get(10, TimeUnit.SECONDS);
            assertEquals(ErrorCode.IDENTIFIER_CONFLICT, conflict.getCode());
            assertEquals("phone", conflict.getParams().get("field"));
        }

        assertEquals(1, number("select count(*) from users where username = ? or phone = ?", identity, identity));
        assertEquals("ADMIN", scalar("select role from users where username = ?", String.class, identity));
        assertEquals(0, number("select count(*) from users where username = ?", registration.getUsername()));
    }

    @Test
    void administratorPhoneUpdateAndRegistrationUsernameUseTheSameIdentityLock() throws Exception {
        String identity = "13800138102";
        UUID adminId = administrator();
        UpdateAdministratorRequest update = new UpdateAdministratorRequest();
        update.setPhone(identity);
        RegisterRequest registration = registration(identity, "13900139102");
        CountDownLatch phoneWritten = new CountDownLatch(1);
        CountDownLatch commitPhone = new CountDownLatch(1);

        try (var workers = Executors.newFixedThreadPool(2)) {
            Future<?> admin = workers.submit(() -> asAdministrator(adminId, () ->
                    new TransactionTemplate(transactionManager).execute(status -> {
                        administrators.updateAdministrator(adminId, update);
                        phoneWritten.countDown();
                        awaitLatch(commitPhone);
                        return null;
                    })));
            Future<BusinessException> rejected;
            try {
                assertTrue(phoneWritten.await(10, TimeUnit.SECONDS));
                rejected = workers.submit(() -> assertThrows(BusinessException.class,
                        () -> authService.register(registration, "203.0.113.3")));
                awaitDatabaseLock(rejected, "user_number_sequences");
            } finally {
                commitPhone.countDown();
            }
            admin.get(10, TimeUnit.SECONDS);
            assertEquals(ErrorCode.IDENTIFIER_CONFLICT, rejected.get(10, TimeUnit.SECONDS).getCode());
        }

        assertEquals(identity, scalar("select phone from users where id = ?", String.class, adminId));
        assertEquals(1, number("select count(*) from users where username = ? or phone = ?", identity, identity));
    }

    private <T> T afterConcurrentWrite(String sql, List<?> args, String table, Callable<T> action) throws Exception {
        try (Connection held = dataSource.getConnection(); var worker = Executors.newSingleThreadExecutor()) {
            held.setAutoCommit(false);
            try (PreparedStatement update = held.prepareStatement(sql)) {
                for (int i = 0; i < args.size(); i++) update.setObject(i + 1, args.get(i));
                assertEquals(1, update.executeUpdate());
            }
            Future<T> result = worker.submit(action);
            try {
                awaitDatabaseLock(result, table);
            } finally {
                held.commit();
            }
            return result.get(10, TimeUnit.SECONDS);
        }
    }

    private void awaitDatabaseLock(Future<?> operation, String table) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        boolean blocked = false;
        while (System.nanoTime() < deadline && !operation.isDone()) {
            blocked = number("select count(*) from pg_stat_activity where datname = current_database() "
                    + "and wait_event_type = 'Lock' and query ilike ?", "%" + table + "%") > 0;
            if (blocked) break;
            Thread.sleep(20);
        }
        assertTrue(blocked, "the concurrent operation did not reach its database row lock");
    }

    private void awaitLatch(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "identity transaction was never released");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }

    private <T> T asAdministrator(UUID operator, Callable<T> action) throws Exception {
        RequestContext.set(new RequestContext.UserInfo(operator, "core-operator", "ADMIN"));
        try {
            return action.call();
        } finally {
            RequestContext.clear();
        }
    }

    private UUID administrator() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into users (id, created_at, updated_at, username, password_hash, role, is_deleted)
                values (?, current_timestamp, current_timestamp, ?, 'test-fixture-hash', 'ADMIN', 0)
                """, id, "identity_" + id.toString().substring(0, 8));
        return id;
    }

    private RegisterRequest registration(String username, String phone) {
        RegisterRequest request = new RegisterRequest();
        request.setUsername(username);
        request.setPhone(phone);
        request.setEmail(username + "@identity.example.test");
        request.setPassword("registration-test-password");
        request.setCaptchaId("core-identity-captcha");
        request.setCaptcha("1234");
        when(captchas.verify(request.getCaptchaId(), request.getCaptcha())).thenReturn(true);
        return request;
    }

    private Account account() {
        UUID id = UUID.randomUUID();
        String username = "core_" + id.toString().substring(0, 8);
        jdbc.update("""
                insert into users (id, created_at, updated_at, username, email, password_hash,
                    role, is_deleted, user_no, user_level_id)
                values (?, current_timestamp, current_timestamp, ?, ?, ?, 'USER', 0,
                    (select coalesce(max(user_no), 9999) + 1 from users),
                    (select id from user_levels where is_default = true))
                """, id, username, username + "@example.test", passwordEncoder.encode("old-password"));
        return new Account(id, username);
    }

    private void assertAccount(UUID id, int authVersion) {
        assertEquals(new BigDecimal("90.00"), scalar("select balance from users where id = ?", BigDecimal.class, id));
        assertEquals(authVersion, number("select auth_version from users where id = ?", id));
    }

    private UUID product(String stockMode) {
        UUID category = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        jdbc.update("insert into product_categories (id, created_at, updated_at, name, sort_order, is_deleted) "
                + "values (?, current_timestamp, current_timestamp, ?, 0, 0)", category, "Core " + product);
        jdbc.update("""
                insert into products (id, created_at, updated_at, title, product_no, retail_price, cost_price,
                    category_id, delivery_type, stock_mode, is_enabled, is_deleted)
                values (?, current_timestamp, current_timestamp, 'Core product', nextval('product_no_sequence'),
                    10, 10, ?, ?, ?, true, 0)
                """, product, category, "MANUAL".equals(stockMode) ? "MANUAL" : "AUTO", stockMode);
        return product;
    }

    private UUID order(String type, String status, UUID userId, LocalDateTime expiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into orders (id, created_at, updated_at, user_id, actual_amount, total_amount, status,
                    order_type, payment_method, payment_channel_code, session_token, expires_at)
                values (?, current_timestamp, current_timestamp, ?, ?, 10, ?, ?, ?, ?, 'core-session', ?)
                """, id, userId, "ADMIN_PICKUP".equals(type) ? 0 : 10, status, type,
                "NORMAL".equals(type) ? "ONLINE" : null,
                "NORMAL".equals(type) ? "core-channel" : ("SALES_CHANNEL".equals(type) ? "XIANYU" : null),
                expiresAt == null ? null : Timestamp.valueOf(expiresAt));
        return id;
    }

    private UUID item(UUID order, UUID product, String stockMode, int reserved) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into order_items (id, created_at, updated_at, order_id, product_id, product_no,
                    product_title, quantity, unit_price, cost_price_snapshot, retail_price_snapshot, pricing_source,
                    subtotal, delivery_type, stock_mode, fulfillment_status, manual_stock_reserved)
                values (?, current_timestamp, current_timestamp, ?, ?, (select product_no from products where id = ?),
                    'Core product', 1, 10, 10, 10, 'RETAIL', 10, ?, ?, 'PENDING', ?)
                """, id, order, product, product, "MANUAL".equals(stockMode) ? "MANUAL" : "AUTO", stockMode, reserved);
        return id;
    }

    private UUID card(UUID product) {
        UUID id = UUID.randomUUID();
        String content = "core-card-" + id;
        jdbc.update("""
                insert into card_keys (id, created_at, updated_at, product_id, content_parts, content_fingerprint, status)
                values (?, current_timestamp, current_timestamp, ?, jsonb_build_array(?), ?, 'AVAILABLE')
                """, id, product, content, CardContentParser.fingerprint(List.of(content)));
        return id;
    }

    private UUID attempt(UUID order) {
        UUID channel = jdbc.query("select id from payment_channels where channel_code = 'core-channel'",
                result -> result.next() ? result.getObject(1, UUID.class) : null);
        if (channel == null) {
            channel = UUID.randomUUID();
            jdbc.update("""
                    insert into payment_channels (id, created_at, updated_at, channel_code, channel_name,
                        provider_type, payment_type, config_data, is_enabled, sort_order, is_deleted)
                    values (?, current_timestamp, current_timestamp, 'core-channel', 'Core channel',
                        'epay', 'alipay', '{}', true, 0, 0)
                    """, channel);
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into payment_attempts (id, created_at, updated_at, order_id, channel_id,
                    channel_code_snapshot, provider_type, payment_type, provider_config_snapshot,
                    merchant_trade_no, amount, status, next_action, expires_at)
                values (?, current_timestamp, current_timestamp, ?, ?, 'core-channel', 'epay', 'alipay', '{}',
                    ?, 10, 'PENDING', '{"type":"redirect","url":"https://pay.example/checkout"}',
                    current_timestamp + interval '15 minutes')
                """, id, order, channel, "core_" + id);
        return id;
    }

    private ExpiringOrder expiringOrder(UUID userId) {
        UUID product = product("MANUAL");
        UUID order = order("NORMAL", "PENDING", userId, LocalDateTime.now().minusMinutes(1));
        item(order, product, "MANUAL", 1);
        jdbc.update("""
                insert into manual_stocks (id, created_at, updated_at, product_id, available_quantity, reserved_quantity)
                values (?, current_timestamp, current_timestamp, ?, 0, 1)
                """, UUID.randomUUID(), product);
        return new ExpiringOrder(order, product, attempt(order));
    }

    private void assertExpired(ExpiringOrder fixture) {
        assertEquals("EXPIRED", scalar("select status from orders where id = ?", String.class, fixture.orderId()));
        assertEquals("CLOSED", scalar("select status from payment_attempts where id = ?", String.class, fixture.attemptId()));
        assertEquals(1, number("select available_quantity from manual_stocks where product_id = ?", fixture.productId()));
        assertEquals(0, number("select reserved_quantity from manual_stocks where product_id = ?", fixture.productId()));
        assertEquals(0, number("select manual_stock_reserved from order_items where order_id = ?", fixture.orderId()));
    }

    private int number(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }
    private <T> T scalar(String sql, Class<T> type, Object... args) { return jdbc.queryForObject(sql, type, args); }
    private record Account(UUID id, String username) { }
    private record ExpiringOrder(UUID orderId, UUID productId, UUID attemptId) { }
}
