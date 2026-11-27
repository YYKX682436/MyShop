package com.myshop.service.impl;

import com.myshop.constant.AccountPaymentStatus;
import com.myshop.constant.AccountPaymentType;
import com.myshop.context.RequestContext;
import com.myshop.constant.BalanceTransactionType;
import com.myshop.constant.OrderPaymentMethod;
import com.myshop.constant.PricingType;
import com.myshop.context.SiteRequestContext;
import com.myshop.entity.PaymentAttempt;
import com.myshop.entity.Subsite;
import com.myshop.exception.BusinessException;
import com.myshop.payment.PaymentProvider;
import com.myshop.payment.PaymentStatus;
import com.myshop.repository.AccountPaymentTransactionRepository;
import com.myshop.repository.PaymentAttemptRepository;
import com.myshop.repository.SubsiteRepository;
import com.myshop.service.UserAccountService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIf(value = "postgresAvailable", disabledReason = "PostgreSQL test database or Docker required")
@SpringBootTest
class AccountPaymentFlowIntegrationTest {
    static final IntegrationPostgres POSTGRES = new IntegrationPostgres();
    static boolean postgresAvailable() { return POSTGRES.available(); }
    @AfterAll static void close() { POSTGRES.close(); }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("app.public-site-url", () -> "https://shop.example.test");
        registry.add("jwt.secret", () -> "account-payment-integration-secret-with-at-least-64-bytes-and-random-looking-value-2026");
        registry.add("bootstrap.admin.username", () -> "");
        registry.add("bootstrap.admin.email", () -> "");
        registry.add("bootstrap.admin.password", () -> "");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired UserAccountService accounts;
    @Autowired PaymentConfirmationTransactionService confirmations;
    @Autowired AccountPaymentTransactionRepository accountTransactions;
    @Autowired com.myshop.service.SupportTimelineService supportTimeline;
    @Autowired PaymentAttemptRepository paymentAttempts;
    @Autowired SubsiteRepository subsites;

    private final String tag = "acct" + UUID.randomUUID().toString().replace("-", "").substring(0, 9);
    private UUID defaultLevel;
    private final List<UUID> userIds = new ArrayList<>();
    private final List<UUID> levelIds = new ArrayList<>();
    private final List<UUID> siteIds = new ArrayList<>();
    private final List<UUID> accountTransactionIds = new ArrayList<>();
    private final List<UUID> productIds = new ArrayList<>();
    private final List<UUID> specIds = new ArrayList<>();
    private final List<UUID> categoryIds = new ArrayList<>();
    private final List<String> channelCodes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        SiteRequestContext.clear();
        defaultLevel = jdbc.queryForObject("SELECT id FROM user_levels WHERE is_default = true", UUID.class);
    }

    @AfterEach
    void cleanUp() {
        SiteRequestContext.clear();
        if (!accountTransactionIds.isEmpty()) {
            for (UUID id : accountTransactionIds) {
                jdbc.update("DELETE FROM support_system_events WHERE source_type='ACCOUNT_PAYMENT' AND source_id = ?", id);
                jdbc.update("DELETE FROM webhook_events WHERE payment_attempt_id IN (SELECT id FROM payment_attempts WHERE account_payment_transaction_id = ?)", id);
                jdbc.update("DELETE FROM balance_transactions WHERE account_payment_transaction_id = ?", id);
                jdbc.update("DELETE FROM payment_attempts WHERE account_payment_transaction_id = ?", id);
                jdbc.update("DELETE FROM account_payment_transactions WHERE id = ?", id);
            }
        }
        if (!siteIds.isEmpty()) {
            for (UUID id : siteIds) {
                jdbc.update("DELETE FROM support_system_events WHERE site_id = ?", id);
                jdbc.update("DELETE FROM support_activity WHERE conversation_id IN (SELECT id FROM support_conversations WHERE site_id = ?)", id);
                jdbc.update("DELETE FROM support_attachments WHERE message_id IN (SELECT m.id FROM support_messages m JOIN support_conversations c ON c.id=m.conversation_id WHERE c.site_id = ?)", id);
                jdbc.update("DELETE FROM support_messages WHERE conversation_id IN (SELECT id FROM support_conversations WHERE site_id = ?)", id);
                jdbc.update("DELETE FROM support_read_states WHERE conversation_id IN (SELECT id FROM support_conversations WHERE site_id = ?)", id);
                jdbc.update("DELETE FROM support_conversations WHERE site_id = ?", id);
                jdbc.update("DELETE FROM subsite_level_upgrade_rules WHERE subsite_id = ?", id);
                jdbc.update("DELETE FROM subsite_product_prices WHERE subsite_id = ?", id);
                jdbc.update("DELETE FROM subsite_hidden_products WHERE subsite_id = ?", id);
                jdbc.update("UPDATE users SET subsite_id = NULL, user_level_id = ? WHERE subsite_id = ?", defaultLevel, id);
                jdbc.update("DELETE FROM subsites WHERE id = ?", id);
            }
        }
        for (UUID id : productIds) {
            jdbc.update("DELETE FROM product_level_price_overrides WHERE product_id = ?", id);
            jdbc.update("DELETE FROM product_specs WHERE product_id = ?", id);
            jdbc.update("DELETE FROM products WHERE id = ?", id);
        }
        for (UUID specId : specIds) jdbc.update("DELETE FROM product_specs WHERE id = ?", specId);
        for (UUID userId : userIds) {
            jdbc.update("DELETE FROM support_system_events WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM support_activity WHERE conversation_id IN (SELECT id FROM support_conversations WHERE user_id = ? OR order_id IN (SELECT id FROM orders WHERE user_id = ?))", userId, userId);
            jdbc.update("DELETE FROM support_attachments WHERE message_id IN (SELECT id FROM support_messages WHERE conversation_id IN (SELECT id FROM support_conversations WHERE user_id = ?))", userId);
            jdbc.update("DELETE FROM support_messages WHERE conversation_id IN (SELECT id FROM support_conversations WHERE user_id = ?)", userId);
            jdbc.update("DELETE FROM support_read_states WHERE conversation_id IN (SELECT id FROM support_conversations WHERE user_id = ?)", userId);
            jdbc.update("DELETE FROM support_conversations WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM balance_transactions WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
        for (UUID categoryId : categoryIds) jdbc.update("DELETE FROM product_categories WHERE id = ?", categoryId);
        for (String code : channelCodes) jdbc.update("DELETE FROM payment_channels WHERE channel_code = ?", code);
        jdbc.update("UPDATE subsite_settings SET supply_level_id = NULL WHERE id = 1");
        for (UUID levelId : levelIds) jdbc.update("DELETE FROM user_levels WHERE id = ?", levelId);
        userIds.clear(); levelIds.clear(); siteIds.clear(); accountTransactionIds.clear();
        productIds.clear(); specIds.clear(); categoryIds.clear(); channelCodes.clear();
    }

    @Test
    void rechargeCallbackCreditsBalanceOnceEvenWhenRepeated() {
        UUID customer = user("recharge", "0.00", defaultLevel, null);
        BigDecimal amount = new BigDecimal("50.00");
        UUID transactionId = accountTransaction(customer, null, null, AccountPaymentType.RECHARGE,
                OrderPaymentMethod.ONLINE, amount, null, null, null, null, null, null, "recharge-channel");
        PaymentAttempt attempt = attempt(transactionId, amount);
        PaymentProvider.CallbackResult callback = paid(attempt, "recharge-event");

        assertEquals("SUCCESS", confirmations.confirmPaid(attempt.getId(), callback));
        assertEquals("SUCCESS", confirmations.confirmPaid(attempt.getId(), callback));

        assertMoney("50.00", scalar("SELECT balance FROM users WHERE id = ?", BigDecimal.class, customer));
        assertEquals(1, scalar("SELECT count(*) FROM balance_transactions WHERE account_payment_transaction_id = ? AND type = 'RECHARGE'", Integer.class, transactionId));
        assertEquals("COMPLETED", scalar("SELECT status FROM account_payment_transactions WHERE id = ?", String.class, transactionId));
        assertEquals(1, scalar("SELECT count(*) FROM support_system_events WHERE source_type='ACCOUNT_PAYMENT' AND source_id=? AND event_type='RECHARGE_CREDITED'", Integer.class, transactionId));
        UUID root=scalar("SELECT id FROM support_conversations WHERE user_id=? AND site_id IS NULL AND order_id IS NULL", UUID.class, customer);
        RequestContext.set(new RequestContext.UserInfo(customer,"recharge-customer","USER"));
        try {
            var event=items(supportTimeline.activities(root,false,null,null,null,20)).stream()
                    .filter(row -> "SYSTEM_RESULT".equals(row.get("source_type"))).findFirst().orElseThrow();
            assertEquals("RECHARGE_CREDITED",event.get("event_type"));
            assertEquals(scalar("SELECT id FROM balance_transactions WHERE account_payment_transaction_id=? AND type='RECHARGE'", UUID.class, transactionId),event.get("ledger_id"));
        } finally { RequestContext.clear(); }
    }

    @Test
    void accountOverviewSeparatesHigherLevelsFromTheHighestLevel() {
        UUID customer = user("overview-upgradeable", "0.00", defaultLevel, null);
        level("overview-available", 100, true, new BigDecimal("29.00"), true, PricingType.RETAIL, null);

        var upgradeable = accounts.overview(customer);
        assertTrue(upgradeable.hasHigherLevel());
        assertFalse(upgradeable.isHighestLevel());
        assertTrue(upgradeable.upgradeAvailable());

        jdbc.update("UPDATE user_levels SET enabled = false WHERE upgrade_rank = 100");
        level("overview-locked", 200, false, null, false, PricingType.RETAIL, null);
        var higherButLocked = accounts.overview(customer);
        assertTrue(higherButLocked.hasHigherLevel());
        assertFalse(higherButLocked.isHighestLevel());
        assertFalse(higherButLocked.upgradeAvailable());

        UUID topLevel = level("overview-top", 300, false, null, false, PricingType.RETAIL, null);
        UUID topCustomer = user("overview-highest", "0.00", topLevel, null);
        var highest = accounts.overview(topCustomer);
        assertFalse(highest.hasHigherLevel());
        assertTrue(highest.isHighestLevel());
        assertFalse(highest.upgradeAvailable());
    }

    @Test
    void accountOverviewNeverMarksRestrictedOrUnrankedAccountsAsHighest() {
        UUID admin = user("overview-admin", "0.00", defaultLevel, null);
        jdbc.update("UPDATE users SET role = 'ADMIN', user_no = NULL, user_level_id = NULL WHERE id = ?", admin);
        assertFalse(accounts.overview(admin).isHighestLevel());

        UUID owner = user("overview-owner", "0.00", defaultLevel, null);
        UUID site = subsite(owner, "overview-owner");
        SiteRequestContext.set(subsiteView(site));
        try {
            var ownerOverview = accounts.overview(owner);
            assertFalse(ownerOverview.hasHigherLevel());
            assertFalse(ownerOverview.isHighestLevel());
        } finally {
            SiteRequestContext.clear();
        }

        UUID disabledLevel = level("overview-disabled", 100, false, null, false, PricingType.RETAIL, null);
        UUID unrankedCustomer = user("overview-unranked", "0.00", disabledLevel, null);
        jdbc.update("UPDATE user_levels SET enabled = false WHERE id = ?", disabledLevel);
        var unranked = accounts.overview(unrankedCustomer);
        assertNull(unranked.currentLevelName());
        assertFalse(unranked.isHighestLevel());
    }

    @Test
    void balanceUpgradeUsesThePriceDifferenceAndUpdatesLedgerAndLevelTogether() {
        UUID target = level("paid-level", 100, true, new BigDecimal("29.00"), true, PricingType.RETAIL, null);
        UUID customer = user("balance-upgrade", "100.00", defaultLevel, null);

        var result = accounts.beginUpgrade(customer, target, OrderPaymentMethod.BALANCE, null, "pc");
        accountTransactionIds.add(result.transactionId());

        assertEquals(AccountPaymentStatus.COMPLETED, result.status());
        assertEquals(target, scalar("SELECT user_level_id FROM users WHERE id = ?", UUID.class, customer));
        assertMoney("71.00", scalar("SELECT balance FROM users WHERE id = ?", BigDecimal.class, customer));
        assertMoney("29.00", scalar("SELECT amount FROM account_payment_transactions WHERE id = ?", BigDecimal.class, result.transactionId()));
        assertMoney("-29.00", scalar("SELECT delta FROM balance_transactions WHERE account_payment_transaction_id = ? AND type = 'LEVEL_UPGRADE'", BigDecimal.class, result.transactionId()));
        assertEquals(1, scalar("SELECT count(*) FROM support_system_events WHERE source_type='ACCOUNT_PAYMENT' AND source_id=? AND event_type='LEVEL_UPGRADE_COMPLETED'", Integer.class, result.transactionId()));
        assertTrue(accounts.overview(customer).isHighestLevel());
    }

    @Test
    void explicitlyFreeUpgradeCompletesWithoutTreatingUnconfiguredPriceAsFree() {
        UUID freeTarget = level("free-level", 100, true, new BigDecimal("0.00"), true, PricingType.RETAIL, null);
        UUID unconfiguredTarget = level("unconfigured-level", 200, true, null, false, PricingType.RETAIL, null);
        UUID customer = user("free-upgrade", "7.00", defaultLevel, null);

        var overview = accounts.overview(customer);
        assertEquals(List.of(freeTarget), overview.upgrades().stream().map(UserAccountService.UpgradeOption::levelId).toList());
        assertFalse(overview.upgrades().stream().anyMatch(value -> value.levelId().equals(unconfiguredTarget)));

        var result = accounts.beginUpgrade(customer, freeTarget, OrderPaymentMethod.ONLINE, null, "pc");
        accountTransactionIds.add(result.transactionId());

        assertEquals(AccountPaymentStatus.COMPLETED, result.status());
        assertNull(result.paymentId());
        assertEquals(freeTarget, scalar("SELECT user_level_id FROM users WHERE id = ?", UUID.class, customer));
        assertMoney("7.00", scalar("SELECT balance FROM users WHERE id = ?", BigDecimal.class, customer));
        assertMoney("0.00", scalar("SELECT amount FROM balance_transactions WHERE account_payment_transaction_id = ? AND type = 'LEVEL_UPGRADE'", BigDecimal.class, result.transactionId()));
        assertEquals(0, scalar("SELECT count(*) FROM payment_attempts WHERE account_payment_transaction_id = ?", Integer.class, result.transactionId()));
    }

    @Test
    void onlineUpgradeCreatesAnExistingPaymentIntentWithoutCreatingAnOrder() {
        UUID target = level("online-level", 100, true, new BigDecimal("29.00"), true, PricingType.RETAIL, null);
        UUID customer = user("online-intent", "100.00", defaultLevel, null);
        String channel = paymentChannel("online-intent-channel");

        var result = accounts.beginUpgrade(customer, target, OrderPaymentMethod.ONLINE, channel, "pc");
        accountTransactionIds.add(result.transactionId());

        assertNotNull(result.paymentId());
        assertEquals(defaultLevel, scalar("SELECT user_level_id FROM users WHERE id = ?", UUID.class, customer));
        assertMoney("100.00", scalar("SELECT balance FROM users WHERE id = ?", BigDecimal.class, customer));
        assertEquals(0, scalar("SELECT count(*) FROM orders WHERE user_id = ?", Integer.class, customer));
        PaymentAttempt attempt = paymentAttempts.findById(result.paymentId()).orElseThrow();
        assertNull(attempt.getOrderId());
        assertEquals(result.transactionId(), attempt.getAccountPaymentTransactionId());
        assertMoney("29.00", attempt.getAmount());
        assertEquals("INTENT", attempt.getCreationStatus().name());
        assertEquals("SUCCESS", confirmations.confirmPaid(attempt.getId(), paid(attempt, "last-level-online-event")));
        assertEquals(target, scalar("SELECT user_level_id FROM users WHERE id = ?", UUID.class, customer));
        assertTrue(accounts.overview(customer).isHighestLevel());
    }

    @Test
    void priceChangeDuringOnlineUpgradePaymentRecoversThePaidAmountToBalance() {
        UUID target = level("changed-price-level", 100, true, new BigDecimal("29.00"), true, PricingType.RETAIL, null);
        UUID customer = user("changed-price", "0.00", defaultLevel, null);
        UUID transactionId = accountTransaction(customer, null, null, AccountPaymentType.LEVEL_UPGRADE,
                OrderPaymentMethod.ONLINE, new BigDecimal("29.00"), defaultLevel, target,
                0, 100, new BigDecimal("0.00"), new BigDecimal("29.00"), "changed-price-channel");
        PaymentAttempt attempt = attempt(transactionId, new BigDecimal("29.00"));
        jdbc.update("UPDATE user_levels SET upgrade_price = 49.00 WHERE id = ?", target);

        assertEquals("SUCCESS", confirmations.confirmPaid(attempt.getId(), paid(attempt, "changed-price-event")));

        assertEquals(defaultLevel, scalar("SELECT user_level_id FROM users WHERE id = ?", UUID.class, customer));
        assertMoney("29.00", scalar("SELECT balance FROM users WHERE id = ?", BigDecimal.class, customer));
        assertEquals("RECOVERED", scalar("SELECT status FROM account_payment_transactions WHERE id = ?", String.class, transactionId));
        assertEquals(1, scalar("SELECT count(*) FROM balance_transactions WHERE account_payment_transaction_id = ? AND type = 'RECHARGE'", Integer.class, transactionId));
        assertEquals(1, scalar("SELECT count(*) FROM support_system_events WHERE source_type='ACCOUNT_PAYMENT' AND source_id=? AND event_type='LEVEL_UPGRADE_RECOVERED'", Integer.class, transactionId));
    }

    @Test
    void subsiteOnlineUpgradePaysOwnerOnceAndPriceLookupUsesSiteSpecPricing() {
        UUID supplyLevel = level("supply-level", 50, false, null, false, PricingType.COST_MARKUP, new BigDecimal("25.0000"));
        UUID target = level("public-site-level", 100, true, new BigDecimal("29.00"), true, PricingType.RETAIL, null);
        UUID internal = level("internal-site-level", 200, false, null, false, PricingType.RETAIL, null);
        UUID owner = user("site-owner", "0.00", defaultLevel, null);
        UUID customer = user("site-customer", "0.00", defaultLevel, null);
        UUID site = subsite(owner, "a");
        jdbc.update("UPDATE users SET subsite_id = ? WHERE id = ?", site, customer);
        jdbc.update("UPDATE subsite_settings SET supply_level_id = ? WHERE id = 1", supplyLevel);
        jdbc.update("""
                INSERT INTO subsite_level_upgrade_rules(subsite_id, user_level_id, self_upgrade_enabled, upgrade_price, public_price_enabled)
                VALUES (?, ?, true, 35.00, true), (?, ?, true, 55.00, false)
                """, site, target, site, internal);

        UUID transactionId = accountTransaction(customer, site, owner, AccountPaymentType.LEVEL_UPGRADE,
                OrderPaymentMethod.ONLINE, new BigDecimal("35.00"), defaultLevel, target,
                0, 100, new BigDecimal("0.00"), new BigDecimal("35.00"), "station-upgrade-channel");
        PaymentAttempt attempt = attempt(transactionId, new BigDecimal("35.00"));
        PaymentProvider.CallbackResult callback = paid(attempt, "station-upgrade-event");
        assertEquals("SUCCESS", confirmations.confirmPaid(attempt.getId(), callback));
        assertEquals("SUCCESS", confirmations.confirmPaid(attempt.getId(), callback));

        assertEquals(target, scalar("SELECT user_level_id FROM users WHERE id = ?", UUID.class, customer));
        assertMoney("35.00", scalar("SELECT balance FROM users WHERE id = ?", BigDecimal.class, owner));
        assertEquals(1, scalar("SELECT count(*) FROM balance_transactions WHERE account_payment_transaction_id = ? AND type = 'SUBSITE_UPGRADE_PROFIT'", Integer.class, transactionId));

        ProductFixture product = productWithSpec();
        jdbc.update("INSERT INTO product_level_price_overrides(product_id, spec_id, user_level_id, price) VALUES (?, ?, ?, 90.00)", product.productId(), product.specId(), target);
        jdbc.update("INSERT INTO subsite_product_prices(subsite_id, product_id, spec_id, user_level_id, price) VALUES (?, ?, ?, ?, 85.00)", site, product.productId(), product.specId(), target);

        var station = subsiteView(site);
        SiteRequestContext.set(station);
        UUID stationPriceUser = user("site-price-check", "0.00", defaultLevel, site);
        var stationPrice = accounts.priceCheck(stationPriceUser, product.productId(), product.specId());
        assertMoney("120.00", stationPrice.currentPrice());
        assertMoney("85.00", stationPrice.levels().stream().filter(value -> value.levelId().equals(target)).findFirst().orElseThrow().price());
        assertEquals(1, stationPrice.levels().size());
        SiteRequestContext.clear();
        var mainPrice = accounts.priceCheck(user("main-price-check", "0.00", defaultLevel, null), product.productId(), product.specId());
        assertMoney("90.00", mainPrice.levels().stream().filter(value -> value.levelId().equals(target)).findFirst().orElseThrow().price());

        UUID otherOwner = user("other-owner", "0.00", defaultLevel, null);
        UUID otherSite = subsite(otherOwner, "b");
        SiteRequestContext.set(subsiteView(otherSite));
        assertThrows(BusinessException.class, () -> accounts.overview(customer));
        SiteRequestContext.clear();
    }

    private UUID user(String suffix, String balance, UUID level, UUID subsiteId) {
        UUID id = UUID.randomUUID();
        long userNo = jdbc.queryForObject("SELECT next_user_no FROM user_number_sequences WHERE id = 1", Long.class);
        jdbc.update("UPDATE user_number_sequences SET next_user_no = next_user_no + 1 WHERE id = 1");
        jdbc.update("""
                INSERT INTO users(id, username, email, password_hash, role, user_no, user_level_id, subsite_id, balance, is_deleted)
                VALUES (?, ?, ?, 'test', 'USER', ?, ?, ?, ?, 0)
                """, id, tag + suffix, tag + suffix + "@example.test", userNo, level, subsiteId, new BigDecimal(balance));
        userIds.add(id);
        return id;
    }

    private UUID level(String suffix, int rank, boolean selfUpgrade, BigDecimal price,
                       boolean publicPrice, PricingType pricingType, BigDecimal markup) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO user_levels(id, name, pricing_type, markup_rate, enabled, is_default, sort_order,
                    upgrade_rank, self_upgrade_enabled, upgrade_price, public_price_enabled)
                VALUES (?, ?, ?, ?, true, false, ?, ?, ?, ?, ?)
                """, id, tag + suffix, pricingType.name(), markup, rank, rank, selfUpgrade, price, publicPrice);
        levelIds.add(id);
        return id;
    }

    private UUID subsite(UUID owner, String suffix) {
        UUID id = UUID.randomUUID();
        siteIds.add(id);
        jdbc.update("INSERT INTO subsites(id, owner_id, prefix, name, enabled) VALUES (?, ?, ?, ?, true)",
                id, owner, tag + suffix, tag + suffix);
        return id;
    }

    private UUID accountTransaction(UUID user, UUID site, UUID owner, AccountPaymentType type,
                                    OrderPaymentMethod method, BigDecimal amount, UUID source, UUID target,
                                    Integer sourceRank, Integer targetRank, BigDecimal sourcePrice,
                                    BigDecimal targetPrice, String channel) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO account_payment_transactions(
                    id, user_id, subsite_id, subsite_owner_id_snapshot, type, payment_method, amount, status,
                    current_level_id, target_level_id, current_level_rank_snapshot, target_level_rank_snapshot,
                    current_level_price_snapshot, target_level_price_snapshot, channel_code_snapshot, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, user, site, owner, type.name(), method.name(), amount, source, target,
                sourceRank, targetRank, sourcePrice, targetPrice, channel, LocalDateTime.now().plusMinutes(30));
        accountTransactionIds.add(id);
        return id;
    }

    private PaymentAttempt attempt(UUID transactionId, BigDecimal amount) {
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setAccountPaymentTransactionId(transactionId);
        attempt.setChannelId(UUID.randomUUID());
        attempt.setChannelCodeSnapshot(jdbc.queryForObject(
                "SELECT channel_code_snapshot FROM account_payment_transactions WHERE id = ?", String.class, transactionId));
        attempt.setProviderType("epay");
        attempt.setPaymentType("alipay");
        attempt.setProviderConfigSnapshot("{\"api_url\":\"https://pay.example.test\",\"pid\":\"test\",\"key\":\"test\"}");
        attempt.setMerchantTradeNo(tag + UUID.randomUUID().toString().replace("-", ""));
        attempt.setAmount(amount);
        attempt.setStatus(PaymentStatus.PENDING);
        attempt.setCreationStatus(com.myshop.payment.PaymentCreationStatus.READY);
        attempt.setProductNameSnapshot("账户交易测试");
        attempt.setNotifyUrlSnapshot("https://shop.example.test/api/payments/webhook/epay");
        attempt.setReturnUrlSnapshot("https://shop.example.test/my/balance");
        attempt.setExpiresAt(LocalDateTime.now().plusMinutes(30));
        return paymentAttempts.saveAndFlush(attempt);
    }

    private PaymentProvider.CallbackResult paid(PaymentAttempt attempt, String event) {
        return new PaymentProvider.CallbackResult(event, attempt.getMerchantTradeNo(),
                tag + "-trade-" + event, PaymentStatus.PAID, attempt.getAmount());
    }

    private String paymentChannel(String code) {
        String channel = tag + code;
        channelCodes.add(channel);
        jdbc.update("""
                INSERT INTO payment_channels(channel_code, channel_name, provider_type, payment_type, config_data, is_enabled, sort_order, is_deleted)
                VALUES (?, ?, 'epay', 'alipay', ?, true, 0, 0)
                """, channel, "Fixture Alipay", "{\"api_url\":\"https://pay.example.test\",\"pid\":\"test\",\"key\":\"test\"}");
        return channel;
    }

    private ProductFixture productWithSpec() {
        UUID category = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        UUID spec = UUID.randomUUID();
        categoryIds.add(category); productIds.add(product); specIds.add(spec);
        jdbc.update("INSERT INTO product_categories(id, name) VALUES (?, ?)", category, tag + "category");
        jdbc.update("""
                INSERT INTO products(id, title, category_id, retail_price, cost_price, spec_enabled, is_enabled, is_deleted, stock_mode, delivery_type)
                VALUES (?, ?, ?, 120.00, 40.00, true, true, 0, 'UNLIMITED', 'MANUAL')
                """, product, tag + "商品", category);
        jdbc.update("""
                INSERT INTO product_specs(id, product_id, name, retail_price, cost_price, is_visible, is_deleted, sort_order)
                VALUES (?, ?, '年卡', 120.00, 40.00, true, 0, 0)
                """, spec, product);
        return new ProductFixture(product, spec);
    }

    private Subsite subsiteView(UUID id) { return subsites.findById(id).orElseThrow(); }
    private <T> T scalar(String sql, Class<T> type, Object... args) { return jdbc.queryForObject(sql, type, args); }
    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> items(Map<String,Object> page) { return (List<Map<String,Object>>)page.get("items"); }
    private void assertMoney(String expected, BigDecimal actual) { assertEquals(0, new BigDecimal(expected).compareTo(actual)); }
    private record ProductFixture(UUID productId, UUID specId) { }
}
