package com.myshop.service.impl;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.FlywayException;
import org.springframework.dao.DataAccessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
class MigrationChainIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate = new JdbcTemplate(dataSource());
        flyway().clean();
    }

    @Test
    void freshDatabaseBuildsCompleteCurrentBaselineWithoutHistoricalColumns() {
        Flyway flyway = flyway();
        flyway.migrate();

        assertEquals("7", flyway.info().current().getVersion().getVersion());
        assertEquals(7, flyway.info().all().length);
        for (String table : List.of(
                "users", "product_categories", "products", "product_specs", "wholesale_rules",
                "card_import_batches", "orders", "order_items", "card_keys",
                "payment_channels", "site_configs", "user_number_sequences", "balance_transactions", "operation_logs",
                "visit_stats", "webhook_events", "manual_stocks")) {
            assertTrue(tableExists(table), "missing table: " + table);
        }
        assertFalse(tableExists("cart_items"));
        assertTrue(columnExists("orders", "order_type"));
        assertEquals("NO", nullableValue("orders", "order_type"));
        assertTrue(defaultValue("orders", "order_type").contains("NORMAL"));
        assertTrue(checkConstraintExists("orders_order_type_check"));
        assertTrue(indexExists("idx_orders_type_created"));
        assertFalse(tableExists("currencies"));
        assertFalse(tableExists("points_logs"));

        assertTrue(columnExists("users", "balance"));
        assertEquals("NO", nullableValue("users", "balance"));
        assertEquals("numeric", dataType("users", "balance"));
        assertEquals("12", numericPrecision("users", "balance"));
        assertEquals("2", numericScale("users", "balance"));
        assertNotNull(defaultValue("users", "balance"));
        assertTrue(defaultValue("users", "balance").contains("0.00"));
        assertTrue(checkConstraintExists("users_balance_non_negative"));
        assertTrue(columnExists("users", "user_no"));
        assertEquals("bigint", dataType("users", "user_no"));
        assertTrue(indexExists("uq_users_user_no_user"));
        assertTrue(indexExists("uq_users_email_normalized"));
        assertTrue(indexExists("uq_users_username_ci"));
        assertTrue(indexExists("uq_users_phone"));
        assertTrue(checkConstraintExists("users_phone_format_check"));
        assertTrue(columnExists("users", "phone"));
        assertEquals("YES", nullableValue("users", "phone"));
        assertEquals("YES", nullableValue("users", "email"));
        assertTrue(indexExists("uq_users_single_super_admin"));
        assertEquals("character varying", dataType("users", "phone"));
        assertEquals(11, jdbcTemplate.queryForObject("""
                SELECT character_maximum_length
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'phone'
                """, Integer.class));
        assertEquals(10000L, jdbcTemplate.queryForObject(
                "SELECT next_user_no FROM user_number_sequences WHERE id = 1", Long.class));
        for (String column : List.of(
                "user_id", "type", "amount", "delta", "balance_before", "balance_after",
                "reason", "operator_id", "order_id", "created_at")) {
            assertTrue(columnExists("balance_transactions", column),
                    "missing balance transaction column: " + column);
        }
        assertTrue(indexExists("idx_balance_transactions_user_created"));

        UUID defaultBalanceUserId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users(id, username, email, password_hash, role, user_no)
                VALUES (?, ?, ?, 'test-hash', 'USER', ?)
                """, defaultBalanceUserId, "balance-default-user", "balance-default@example.com", 10000L);
        jdbcTemplate.update("UPDATE users SET phone = '13800138000' WHERE id = ?", defaultBalanceUserId);
        BigDecimal defaultBalance = jdbcTemplate.queryForObject(
                "SELECT balance FROM users WHERE id = ?", BigDecimal.class, defaultBalanceUserId);
        assertNotNull(defaultBalance);
        assertEquals(0, defaultBalance.compareTo(BigDecimal.ZERO));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                INSERT INTO users(id, username, email, password_hash, role, user_no, balance)
                VALUES (?, ?, ?, 'test-hash', 'USER', ?, ?)
                """, UUID.randomUUID(), "negative-balance-user", "negative-balance@example.com", 10001L,
                new BigDecimal("-0.01")));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                INSERT INTO users(id, username, email, password_hash, role, user_no, phone)
                VALUES (?, ?, ?, 'test-hash', 'USER', ?, ?)
                """, UUID.randomUUID(), "duplicate-phone-user", "duplicate-phone@example.com", 10001L,
                "13800138000"));
        UUID superAdminId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users(id, username, password_hash, role)
                VALUES (?, 'migration-super-admin', 'test-hash', 'SUPER_ADMIN')
                """, superAdminId);
        assertEquals("SUPER_ADMIN", jdbcTemplate.queryForObject(
                "SELECT role FROM users WHERE id = ?", String.class, superAdminId));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                INSERT INTO users(id, username, password_hash, role, user_no)
                VALUES (?, 'invalid-super-admin', 'test-hash', 'SUPER_ADMIN', 10002)
                """, UUID.randomUUID()));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                INSERT INTO users(id, username, password_hash, role)
                VALUES (?, 'invalid-user-without-number', 'test-hash', 'USER')
                """, UUID.randomUUID()));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                INSERT INTO users(id, username, password_hash, role)
                VALUES (?, 'second-super-admin', 'test-hash', 'SUPER_ADMIN')
                """, UUID.randomUUID()));

        assertFalse(columnExists("users", "points"));
        assertFalse(columnExists("orders", "points_deducted"));
        assertFalse(columnExists("orders", "points_discount"));
        assertFalse(columnExists("products", "currency"));
        assertEquals(0, configCount("points_enabled"));
        assertEquals(0, configCount("points_rate"));

        assertTrue(columnExists("products", "delivery_type"));
        assertTrue(columnExists("products", "customer_input_fields"));
        assertTrue(columnExists("products", "stock_mode"));
        assertTrue(columnExists("order_items", "customer_inputs"));
        assertTrue(columnExists("order_items", "delivery_notified_at"));
        assertTrue(columnExists("card_keys", "content_parts"));
        assertTrue(columnExists("card_keys", "content_fingerprint"));

        assertFalse(columnExists("products", "customer_input_type"));
        assertFalse(columnExists("order_items", "customer_input_type"));
        assertFalse(columnExists("order_items", "customer_input_value"));
        assertFalse(columnExists("card_keys", "content"));

        for (String[] column : List.of(
                new String[]{"products", "delivery_type"},
                new String[]{"products", "customer_input_fields"},
                new String[]{"products", "stock_mode"},
                new String[]{"order_items", "delivery_type"},
                new String[]{"order_items", "customer_inputs"},
                new String[]{"order_items", "stock_mode"},
                new String[]{"order_items", "fulfillment_status"})) {
            assertEquals("NO", nullableValue(column[0], column[1]),
                    column[0] + "." + column[1] + " must be required");
        }
        assertNotNull(defaultValue("products", "delivery_type"));
        assertNotNull(defaultValue("products", "customer_input_fields"));
        assertNotNull(defaultValue("products", "stock_mode"));
        assertNotNull(defaultValue("order_items", "customer_inputs"));

        assertTrue(indexExists("idx_card_keys_pool_fingerprint"));
        assertTrue(indexExists("idx_card_keys_available_pool"));
        assertFalse(indexExists("uq_card_key_pool_content"));
        assertEquals("[]", configValue("contact_items"));
        for (String retiredKey : List.of("github_url", "contact_telegram", "contact_telegram_group")) {
            assertEquals(0, configCount(retiredKey), "retired config key must not be seeded: " + retiredKey);
        }
    }

    @Test
    void v3DoesNotConvertLegacyPointsIntoBalance() {
        flyway("2").migrate();
        UUID legacyUserId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users(id, username, email, password_hash, role, points)
                VALUES (?, ?, ?, 'test-hash', 'USER', 73)
                """, legacyUserId, "legacy-points-user", "legacy-points@example.com");

        flyway().migrate();

        BigDecimal balance = jdbcTemplate.queryForObject(
                "SELECT balance FROM users WHERE id = ?", BigDecimal.class, legacyUserId);
        assertNotNull(balance);
        assertEquals(0, balance.compareTo(BigDecimal.ZERO));
        assertEquals(10000L, jdbcTemplate.queryForObject(
                "SELECT user_no FROM users WHERE id = ?", Long.class, legacyUserId));
        assertEquals(10001L, jdbcTemplate.queryForObject(
                "SELECT next_user_no FROM user_number_sequences WHERE id = 1", Long.class));
    }

    @Test
    void v5FailsFastBeforeCreatingUsernameIndexWhenExistingNamesConflictIgnoringCase() {
        flyway("4").migrate();
        jdbcTemplate.update("""
                INSERT INTO users(id, username, email, password_hash, role, user_no)
                VALUES (?, 'Tom123', 'tom-one@example.com', 'test-hash', 'USER', 10000)
                """, UUID.randomUUID());
        jdbcTemplate.update("""
                INSERT INTO users(id, username, email, password_hash, role, user_no)
                VALUES (?, 'tom123', 'tom-two@example.com', 'test-hash', 'USER', 10001)
                """, UUID.randomUUID());

        assertThrows(FlywayException.class, () -> flyway().migrate());
        assertFalse(indexExists("uq_users_username_ci"));
        assertFalse(columnExists("users", "phone"));
        assertEquals("Tom123", jdbcTemplate.queryForObject(
                "SELECT username FROM users WHERE email = 'tom-one@example.com'", String.class));
        assertEquals("tom123", jdbcTemplate.queryForObject(
                "SELECT username FROM users WHERE email = 'tom-two@example.com'", String.class));
    }

    @Test
    void forwardContactMigrationRemovesRetiredRowsAndKeepsReplacementKey() {
        flyway("1").migrate();
        for (String retiredKey : List.of("github_url", "contact_telegram", "contact_telegram_group")) {
            jdbcTemplate.update("""
                    INSERT INTO site_configs(config_key, config_value, config_group)
                    VALUES (?, 'legacy-value', 'basic')
                    """, retiredKey);
        }

        flyway().migrate();

        for (String retiredKey : List.of("github_url", "contact_telegram", "contact_telegram_group")) {
            assertEquals(0, configCount(retiredKey), "retired config row must be removed: " + retiredKey);
        }
        assertEquals("[]", configValue("contact_items"));
    }

    @Test
    void v6RemovesCartSchemaButPreservesHistoricalOrders() {
        flyway("5").migrate();
        UUID orderId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO orders(id, total_amount, actual_amount, status, order_type)
                VALUES (?, 10.00, 10.00, 'PENDING', 'NORMAL')
                """, orderId);
        jdbcTemplate.update("""
                INSERT INTO cart_items(id, session_token, product_id, quantity)
                VALUES (?, 'legacy-session', ?, 2)
                """, UUID.randomUUID(), UUID.randomUUID());

        flyway("6").migrate();

        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM orders WHERE id = ?", Integer.class, orderId));
        assertFalse(tableExists("cart_items"));
        assertFalse(columnExists("orders", "order_type"));
    }

    @Test
    void currentJsonColumnsStoreEmptySingleAndMultipleValuesWithoutCompactingOrder() {
        flyway().migrate();
        UUID categoryId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID orderItemId = UUID.randomUUID();

        jdbcTemplate.update("""
                INSERT INTO product_categories(id, name)
                VALUES (?, 'baseline-category')
                """, categoryId);
        jdbcTemplate.update("""
                INSERT INTO products(id, title, base_price, category_id, customer_input_fields)
                VALUES (?, 'baseline-product', 10.00, ?, ?::jsonb)
                """, productId, categoryId,
                "[{\"id\":\"account\",\"label\":\"账号\"},{\"id\":\"zone\",\"label\":\"区服\"}]");
        jdbcTemplate.update("""
                INSERT INTO orders(id, total_amount, actual_amount, status)
                VALUES (?, 10.00, 10.00, 'PENDING')
                """, orderId);
        jdbcTemplate.update("""
                INSERT INTO order_items(id, order_id, product_id, product_title, quantity, unit_price, subtotal,
                                        customer_inputs)
                VALUES (?, ?, ?, 'baseline-product', 1, 10.00, 10.00, ?::jsonb)
                """, orderItemId, orderId, productId, "[]");
        jdbcTemplate.update("""
                INSERT INTO card_keys(id, product_id, content_parts, content_fingerprint, status)
                VALUES (?, ?, ?::jsonb, 'single-fingerprint', 'AVAILABLE')
                """, UUID.randomUUID(), productId, "[\"single\"]");
        jdbcTemplate.update("""
                INSERT INTO card_keys(id, product_id, content_parts, content_fingerprint, status)
                VALUES (?, ?, ?::jsonb, 'multi-fingerprint', 'AVAILABLE')
                """, UUID.randomUUID(), productId, "[\"username\",\"password\"]");

        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT jsonb_array_length(customer_inputs) FROM order_items WHERE id = ?",
                Integer.class, orderItemId));
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT jsonb_array_length(customer_input_fields) FROM products WHERE id = ?",
                Integer.class, productId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT jsonb_array_length(content_parts) FROM card_keys WHERE content_fingerprint = 'single-fingerprint'",
                Integer.class));
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT jsonb_array_length(content_parts) FROM card_keys WHERE content_fingerprint = 'multi-fingerprint'",
                Integer.class));
        assertEquals("username", jdbcTemplate.queryForObject(
                "SELECT content_parts->>0 FROM card_keys WHERE content_fingerprint = 'multi-fingerprint'",
                String.class));
        assertEquals("password", jdbcTemplate.queryForObject(
                "SELECT content_parts->>1 FROM card_keys WHERE content_fingerprint = 'multi-fingerprint'",
                String.class));
    }

    private Flyway flyway() {
        return flyway(null);
    }

    private Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource())
                .locations("classpath:db/migration")
                .cleanDisabled(false);
        if (target != null) configuration.target(MigrationVersion.fromVersion(target));
        return configuration.load();
    }

    private DriverManagerDataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }

    private boolean tableExists(String table) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = ?
                """, Integer.class, table);
        return count != null && count == 1;
    }

    private boolean columnExists(String table, String column) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """, Integer.class, table, column);
        return count != null && count == 1;
    }

    private String nullableValue(String table, String column) {
        return jdbcTemplate.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """, String.class, table, column);
    }

    private String defaultValue(String table, String column) {
        return jdbcTemplate.queryForObject("""
                SELECT column_default FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """, String.class, table, column);
    }

    private String dataType(String table, String column) {
        return jdbcTemplate.queryForObject("""
                SELECT data_type FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """, String.class, table, column);
    }

    private String numericPrecision(String table, String column) {
        return jdbcTemplate.queryForObject("""
                SELECT numeric_precision::text FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """, String.class, table, column);
    }

    private String numericScale(String table, String column) {
        return jdbcTemplate.queryForObject("""
                SELECT numeric_scale::text FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """, String.class, table, column);
    }

    private boolean checkConstraintExists(String constraint) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_constraint c
                WHERE c.conname = ? AND c.contype = 'c'
                """, Integer.class, constraint);
        return count != null && count == 1;
    }

    private boolean indexExists(String index) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_indexes
                WHERE schemaname = 'public' AND indexname = ?
                """, Integer.class, index);
        return count != null && count == 1;
    }

    private String configValue(String key) {
        return jdbcTemplate.queryForObject(
                "SELECT config_value FROM site_configs WHERE config_key = ?", String.class, key);
    }

    private int configCount(String key) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM site_configs WHERE config_key = ?", Integer.class, key);
        return count == null ? 0 : count;
    }
}
