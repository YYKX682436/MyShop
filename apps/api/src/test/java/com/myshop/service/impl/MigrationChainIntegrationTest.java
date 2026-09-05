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
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

        assertEquals("15", flyway.info().current().getVersion().getVersion());
        assertEquals(15, flyway.info().all().length);
        for (String table : List.of(
                "users", "user_levels", "product_categories", "products", "product_specs",
                "product_level_price_overrides",
                "card_import_batches", "orders", "order_items", "card_keys",
                "payment_channels", "site_configs", "user_number_sequences", "balance_transactions", "operation_logs",
                "visit_stats", "webhook_events", "manual_stocks")) {
            assertTrue(tableExists(table), "missing table: " + table);
        }
        assertFalse(tableExists("cart_items"));
        assertFalse(tableExists("wholesale_rules"));
        assertFalse(columnExists("orders", "email"));
        assertFalse(indexExists("idx_orders_email_status"));
        assertTrue(columnExists("orders", "order_type"));
        assertEquals("NO", nullableValue("orders", "order_type"));
        assertTrue(defaultValue("orders", "order_type").contains("NORMAL"));
        assertTrue(checkConstraintExists("orders_order_type_check"));
        assertTrue(indexExists("idx_orders_type_created"));
        assertTrue(columnExists("products", "product_no"));
        assertEquals("bigint", dataType("products", "product_no"));
        assertEquals("NO", nullableValue("products", "product_no"));
        assertTrue(columnExists("products", "sort_weight"));
        assertFalse(columnExists("products", "sort_order"));
        assertTrue(columnExists("order_items", "product_no"));
        assertEquals("bigint", dataType("order_items", "product_no"));
        assertEquals("NO", nullableValue("order_items", "product_no"));
        assertTrue(indexExists("products_product_no_key"));
        assertTrue(sequenceExists("product_no_sequence"));
        assertFalse(columnExists("products", "product_code"));
        assertFalse(columnExists("order_items", "product_code"));
        assertFalse(sequenceExists("product_code_sequence"));
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
        assertTrue(columnExists("users", "user_level_id"));
        assertTrue(columnExists("users", "auth_version"));
        assertEquals("NO", nullableValue("users", "auth_version"));
        assertEquals("0", defaultValue("users", "auth_version"));
        assertTrue(columnExists("users", "last_login_ip"));
        assertEquals("YES", nullableValue("users", "last_login_ip"));
        assertTrue(columnExists("users", "last_login_at"));
        assertEquals("YES", nullableValue("users", "last_login_at"));
        assertTrue(checkConstraintExists("users_user_level_role_check"));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_levels WHERE is_default = true", Integer.class));
        assertEquals("普通会员", jdbcTemplate.queryForObject(
                "SELECT name FROM user_levels WHERE is_default = true", String.class));
        assertEquals("RETAIL", jdbcTemplate.queryForObject(
                "SELECT pricing_type FROM user_levels WHERE is_default = true", String.class));
        assertEquals(true, jdbcTemplate.queryForObject(
                "SELECT enabled FROM user_levels WHERE is_default = true", Boolean.class));
        assertEquals("7", numericPrecision("user_levels", "markup_rate"));
        assertEquals("4", numericScale("user_levels", "markup_rate"));
        assertTrue(indexExists("uq_user_levels_single_default"));
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
        assertEquals("YES", nullableValue("balance_transactions", "reason"));

        UUID defaultLevelId = defaultUserLevelId();
        UUID defaultBalanceUserId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users(id, username, email, password_hash, role, user_no, user_level_id)
                VALUES (?, ?, ?, 'test-hash', 'USER', ?, ?)
                """, defaultBalanceUserId, "balance-default-user", "balance-default@example.com", 10000L,
                defaultLevelId);
        jdbcTemplate.update("UPDATE users SET phone = '13800138000' WHERE id = ?", defaultBalanceUserId);
        BigDecimal defaultBalance = jdbcTemplate.queryForObject(
                "SELECT balance FROM users WHERE id = ?", BigDecimal.class, defaultBalanceUserId);
        assertNotNull(defaultBalance);
        assertEquals(0, defaultBalance.compareTo(BigDecimal.ZERO));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                INSERT INTO users(id, username, email, password_hash, role, user_no, user_level_id, balance)
                VALUES (?, ?, ?, 'test-hash', 'USER', ?, ?, ?)
                """, UUID.randomUUID(), "negative-balance-user", "negative-balance@example.com", 10001L,
                defaultLevelId, new BigDecimal("-0.01")));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                INSERT INTO users(id, username, email, password_hash, role, user_no, user_level_id, phone)
                VALUES (?, ?, ?, 'test-hash', 'USER', ?, ?, ?)
                """, UUID.randomUUID(), "duplicate-phone-user", "duplicate-phone@example.com", 10001L,
                defaultLevelId, "13800138000"));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                INSERT INTO users(id, username, password_hash, role, user_no)
                VALUES (?, 'invalid-user-without-level', 'test-hash', 'USER', 10002)
                """, UUID.randomUUID()));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                INSERT INTO users(id, username, password_hash, role, user_level_id)
                VALUES (?, 'invalid-admin-with-level', 'test-hash', 'ADMIN', ?)
                """, UUID.randomUUID(), defaultLevelId));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                INSERT INTO users(id, username, password_hash, role, user_level_id)
                VALUES (?, 'invalid-super-admin-with-level', 'test-hash', 'SUPER_ADMIN', ?)
                """, UUID.randomUUID(), defaultLevelId));
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
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                INSERT INTO user_levels(name, pricing_type, markup_rate, enabled, is_default)
                VALUES ('另一个默认等级', 'RETAIL', 0.0000, true, true)
                """));

        assertFalse(columnExists("users", "points"));
        assertFalse(columnExists("orders", "points_deducted"));
        assertFalse(columnExists("orders", "points_discount"));
        assertFalse(columnExists("products", "currency"));
        assertEquals(0, configCount("points_enabled"));
        assertEquals(0, configCount("points_rate"));

        assertTrue(columnExists("products", "delivery_type"));
        assertTrue(columnExists("products", "customer_input_fields"));
        assertTrue(columnExists("products", "stock_mode"));
        assertTrue(columnExists("products", "retail_price"));
        assertTrue(columnExists("products", "cost_price"));
        assertFalse(columnExists("products", "base_price"));
        assertFalse(columnExists("products", "wholesale_enabled"));
        assertEquals("10", numericPrecision("products", "retail_price"));
        assertEquals("2", numericScale("products", "retail_price"));
        assertEquals("10", numericPrecision("products", "cost_price"));
        assertEquals("2", numericScale("products", "cost_price"));
        assertTrue(columnExists("product_specs", "retail_price"));
        assertTrue(columnExists("product_specs", "cost_price"));
        assertFalse(columnExists("product_specs", "price"));
        assertTrue(columnExists("orders", "user_level_id_snapshot"));
        assertTrue(columnExists("orders", "user_level_name_snapshot"));
        assertEquals("YES", nullableValue("orders", "user_level_id_snapshot"));
        assertEquals("YES", nullableValue("orders", "user_level_name_snapshot"));
        assertFalse(columnExists("orders", "user_level_id"));
        assertFalse(columnExists("orders", "user_level_name"));
        assertFalse(columnExists("orders", "user_level_pricing_type"));
        assertFalse(columnExists("orders", "user_level_markup_rate"));
        assertTrue(columnExists("order_items", "cost_price_snapshot"));
        assertTrue(columnExists("order_items", "retail_price_snapshot"));
        assertTrue(columnExists("order_items", "pricing_source"));
        assertFalse(columnExists("order_items", "cost_price"));
        assertFalse(columnExists("order_items", "retail_price"));
        assertEquals("NO", nullableValue("order_items", "cost_price_snapshot"));
        assertEquals("NO", nullableValue("order_items", "retail_price_snapshot"));
        assertEquals("NO", nullableValue("order_items", "pricing_source"));
        assertTrue(indexExists("uq_product_level_price_overrides_no_spec"));
        assertTrue(indexExists("uq_product_level_price_overrides_with_spec"));
        assertTrue(checkConstraintExists("user_levels_pricing_markup_check"));
        assertTrue(checkConstraintExists("user_levels_default_enabled_check"));
        assertTrue(checkConstraintExists("order_items_pricing_source_check"));
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
        assertTrue(columnExists("payment_channels", "payment_hint"));
        assertTrue(columnExists("payment_channels", "badge_text"));
        for (String retiredKey : List.of("github_url", "contact_telegram", "contact_telegram_group")) {
            assertEquals(0, configCount(retiredKey), "retired config key must not be seeded: " + retiredKey);
        }
    }

    @Test
    void v15NormalizesLegacyPaymentTypeAndRemovesAdministratorManagedUrls() {
        flyway("14").migrate();
        UUID channelId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO payment_channels(id, channel_code, channel_name, provider_type,
                                              payment_type, config_data)
                VALUES (?, 'wechat', '微信', 'epay', 'wechat',
                        '{"pid":"743794","key":"legacy-secret","api_url":"https://pay.example.com","notify_url":"https://old.example/notify","return_url":"https://old.example/return"}')
                """, channelId);

        flyway().migrate();

        assertEquals("wxpay", jdbcTemplate.queryForObject(
                "SELECT payment_type FROM payment_channels WHERE id = ?", String.class, channelId));
        String configData = jdbcTemplate.queryForObject(
                "SELECT config_data FROM payment_channels WHERE id = ?", String.class, channelId);
        assertNotNull(configData);
        assertFalse(configData.contains("notify_url"));
        assertFalse(configData.contains("return_url"));
        assertTrue(columnExists("payment_channels", "payment_hint"));
        assertTrue(columnExists("payment_channels", "badge_text"));
    }

    @Test
    void v10ToV11RenamesProductWeightBackfillsNumbersAndProtectsSnapshots() {
        flyway("9").migrate();
        UUID categoryId = UUID.randomUUID();
        UUID firstProductId = UUID.randomUUID();
        UUID secondProductId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID orderItemId = UUID.randomUUID();
        LocalDateTime firstCreatedAt = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime secondCreatedAt = firstCreatedAt.plusMinutes(1);

        jdbcTemplate.update("""
                INSERT INTO product_categories(id, name)
                VALUES (?, 'v10-category')
                """, categoryId);
        jdbcTemplate.update("""
                INSERT INTO products(id, created_at, updated_at, title, retail_price, cost_price, category_id, sort_order)
                VALUES (?, ?, ?, 'v10-first', 10.00, 5.00, ?, 7)
                """, firstProductId, firstCreatedAt, firstCreatedAt, categoryId);
        jdbcTemplate.update("""
                INSERT INTO products(id, created_at, updated_at, title, retail_price, cost_price, category_id, sort_order)
                VALUES (?, ?, ?, 'v10-second', 12.00, 6.00, ?, -2)
                """, secondProductId, secondCreatedAt, secondCreatedAt, categoryId);
        jdbcTemplate.update("""
                INSERT INTO orders(id, total_amount, actual_amount, status, order_type)
                VALUES (?, 10.00, 10.00, 'PENDING', 'NORMAL')
                """, orderId);
        jdbcTemplate.update("""
                INSERT INTO order_items(id, order_id, product_id, product_title, quantity, unit_price,
                                        cost_price_snapshot, retail_price_snapshot, pricing_source, subtotal)
                VALUES (?, ?, ?, 'v10-first', 1, 10.00, 5.00, 10.00, 'RETAIL', 10.00)
                """, orderItemId, orderId, firstProductId);

        flyway().migrate();

        assertEquals("15", flyway().info().current().getVersion().getVersion());
        assertFalse(columnExists("products", "sort_order"));
        assertEquals("NO", nullableValue("products", "sort_weight"));
        assertEquals("NO", nullableValue("products", "product_no"));
        assertEquals("bigint", dataType("products", "product_no"));
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT product_no FROM products WHERE id = ?", Long.class, firstProductId));
        assertEquals(2L, jdbcTemplate.queryForObject(
                "SELECT product_no FROM products WHERE id = ?", Long.class, secondProductId));
        assertEquals(7, jdbcTemplate.queryForObject(
                "SELECT sort_weight FROM products WHERE id = ?", Integer.class, firstProductId));
        assertEquals(-2, jdbcTemplate.queryForObject(
                "SELECT sort_weight FROM products WHERE id = ?", Integer.class, secondProductId));
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT product_no FROM order_items WHERE id = ?", Long.class, orderItemId));

        UUID sequenceProductId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO products(id, title, retail_price, cost_price, category_id)
                VALUES (?, 'v10-sequence-product', 10.00, 5.00, ?)
                """, sequenceProductId, categoryId);
        assertEquals(3L, jdbcTemplate.queryForObject(
                "SELECT product_no FROM products WHERE id = ?", Long.class, sequenceProductId));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                INSERT INTO products(id, title, retail_price, cost_price, category_id, product_no)
                VALUES (?, 'v11-duplicate-number', 10.00, 5.00, ?, 1)
                """, UUID.randomUUID(), categoryId));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "UPDATE products SET product_no = 9999 WHERE id = ?", firstProductId));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "UPDATE order_items SET product_no = 9999 WHERE id = ?", orderItemId));
        assertFalse(columnExists("products", "product_code"));
        assertFalse(columnExists("order_items", "product_code"));
        assertFalse(sequenceExists("product_code_sequence"));
    }

    @Test
    void v11ConvertsPaddedV10CodesAndKeepsProductAndOrderSnapshotNumbersAligned() {
        flyway("10").migrate();
        UUID categoryId = UUID.randomUUID();
        UUID firstProductId = UUID.randomUUID();
        UUID product138Id = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();

        jdbcTemplate.update("""
                INSERT INTO product_categories(id, name)
                VALUES (?, 'v11-category')
                """, categoryId);
        jdbcTemplate.update("""
                INSERT INTO products(id, title, retail_price, cost_price, category_id, product_code)
                VALUES (?, 'v11-first', 10.00, 5.00, ?, 'P000001')
                """, firstProductId, categoryId);
        jdbcTemplate.update("""
                INSERT INTO products(id, title, retail_price, cost_price, category_id, product_code)
                VALUES (?, 'v11-138', 10.00, 5.00, ?, 'P000138')
                """, product138Id, categoryId);
        jdbcTemplate.update("""
                INSERT INTO orders(id, total_amount, actual_amount, status, order_type)
                VALUES (?, 10.00, 10.00, 'PENDING', 'NORMAL')
                """, orderId);
        jdbcTemplate.update("""
                INSERT INTO order_items(id, order_id, product_id, product_code, product_title, quantity,
                                        unit_price, cost_price_snapshot, retail_price_snapshot, pricing_source, subtotal)
                VALUES (?, ?, ?, (SELECT product_code FROM products WHERE id = ?), 'v11-138', 1,
                        10.00, 5.00, 10.00, 'RETAIL', 10.00)
                """, itemId, orderId, product138Id, product138Id);

        flyway().migrate();

        assertEquals("15", flyway().info().current().getVersion().getVersion());
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT product_no FROM products WHERE id = ?", Long.class, firstProductId));
        assertEquals(138L, jdbcTemplate.queryForObject(
                "SELECT product_no FROM products WHERE id = ?", Long.class, product138Id));
        assertEquals(138L, jdbcTemplate.queryForObject(
                "SELECT product_no FROM order_items WHERE id = ?", Long.class, itemId));
        assertEquals("bigint", dataType("products", "product_no"));
        assertEquals("bigint", dataType("order_items", "product_no"));
        assertTrue(checkConstraintExists("products_product_no_positive"));
        assertTrue(checkConstraintExists("order_items_product_no_positive"));
        assertTrue(indexExists("products_product_no_key"));
        assertEquals(139L, jdbcTemplate.queryForObject(
                "SELECT nextval('product_no_sequence')", Long.class));
        assertFalse(columnExists("products", "product_code"));
        assertFalse(columnExists("order_items", "product_code"));
        assertFalse(sequenceExists("product_code_sequence"));
        assertFalse(functionExists("prevent_product_code_update"));
        assertTrue(functionExists("prevent_product_no_update"));

        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                INSERT INTO products(id, title, retail_price, cost_price, category_id, product_no)
                VALUES (?, 'v11-duplicate', 10.00, 5.00, ?, 1)
                """, UUID.randomUUID(), categoryId));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                INSERT INTO products(id, title, retail_price, cost_price, category_id, product_no)
                VALUES (?, 'v11-invalid', 10.00, 5.00, ?, 0)
                """, UUID.randomUUID(), categoryId));
    }

    @Test
    void v12AddsStableOrderNumbersBackfillsInCreationOrderAndProtectsThem() {
        flyway("11").migrate();
        UUID firstOrderId = UUID.randomUUID();
        UUID secondOrderId = UUID.randomUUID();
        LocalDateTime firstCreatedAt = LocalDateTime.of(2026, 2, 1, 0, 0);
        LocalDateTime secondCreatedAt = firstCreatedAt.plusMinutes(1);

        jdbcTemplate.update("""
                INSERT INTO orders(id, created_at, updated_at, total_amount, actual_amount, status, order_type)
                VALUES (?, ?, ?, 10.00, 10.00, 'PENDING', 'NORMAL')
                """, secondOrderId, secondCreatedAt, secondCreatedAt);
        jdbcTemplate.update("""
                INSERT INTO orders(id, created_at, updated_at, total_amount, actual_amount, status, order_type)
                VALUES (?, ?, ?, 20.00, 20.00, 'PENDING', 'NORMAL')
                """, firstOrderId, firstCreatedAt, firstCreatedAt);

        flyway().migrate();

        assertEquals("15", flyway().info().current().getVersion().getVersion());
        assertFalse(columnExists("orders", "email"));
        assertFalse(indexExists("idx_orders_email_status"));
        assertEquals("bigint", dataType("orders", "order_no"));
        assertEquals("NO", nullableValue("orders", "order_no"));
        assertTrue(defaultValue("orders", "order_no").contains("order_no_sequence"));
        assertTrue(checkConstraintExists("orders_order_no_positive"));
        assertTrue(indexExists("orders_order_no_key"));
        assertTrue(indexExists("idx_card_keys_order"));
        assertTrue(sequenceExists("order_no_sequence"));
        assertTrue(functionExists("prevent_order_no_update"));
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_trigger
                WHERE tgname = 'trg_orders_order_no_immutable'
                """, Integer.class));
        assertEquals(1000000001L, jdbcTemplate.queryForObject(
                "SELECT order_no FROM orders WHERE id = ?", Long.class, firstOrderId));
        assertEquals(1000000002L, jdbcTemplate.queryForObject(
                "SELECT order_no FROM orders WHERE id = ?", Long.class, secondOrderId));
        assertEquals(1000000003L, jdbcTemplate.queryForObject(
                "SELECT nextval('order_no_sequence')", Long.class));

        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "UPDATE orders SET order_no = 999 WHERE id = ?", firstOrderId));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                INSERT INTO orders(id, total_amount, actual_amount, status, order_type, order_no)
                VALUES (?, 1.00, 1.00, 'PENDING', 'NORMAL', 1000000001)
                """, UUID.randomUUID()));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                INSERT INTO orders(id, total_amount, actual_amount, status, order_type, order_no)
                VALUES (?, 1.00, 1.00, 'PENDING', 'NORMAL', 0)
                """, UUID.randomUUID()));
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
    void v6RemovesCartSchemaAndOrderTypeBeforeV7RecreatesIt() {
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

        flyway("7").migrate();

        assertTrue(columnExists("orders", "order_type"));
        assertEquals("NORMAL", jdbcTemplate.queryForObject(
                "SELECT order_type FROM orders WHERE id = ?", String.class, orderId));
    }

    @Test
    void v7RecreatesOrderTypeWithNormalDefaultForHistoricalRows() {
        flyway("5").migrate();
        UUID normalId = UUID.randomUUID();
        UUID directId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID pickupId = UUID.randomUUID();
        for (String[] order : List.of(
                new String[]{normalId.toString(), "NORMAL"},
                new String[]{directId.toString(), "DIRECT"},
                new String[]{cartId.toString(), "CART"},
                new String[]{pickupId.toString(), "ADMIN_PICKUP"})) {
            jdbcTemplate.update("""
                    INSERT INTO orders(id, total_amount, actual_amount, status, order_type)
                    VALUES (?::uuid, 10.00, 10.00, 'PENDING', ?)
                    """, order[0], order[1]);
        }

        flyway("6").migrate();
        assertFalse(columnExists("orders", "order_type"));

        flyway("7").migrate();

        assertTrue(columnExists("orders", "order_type"));
        assertEquals("NORMAL", jdbcTemplate.queryForObject(
                "SELECT order_type FROM orders WHERE id = ?", String.class, directId));
        assertEquals("NORMAL", jdbcTemplate.queryForObject(
                "SELECT order_type FROM orders WHERE id = ?", String.class, cartId));
        assertEquals("NORMAL", jdbcTemplate.queryForObject(
                "SELECT order_type FROM orders WHERE id = ?", String.class, pickupId));
        assertEquals("character varying", dataType("orders", "order_type"));
        assertEquals(32, jdbcTemplate.queryForObject("""
                SELECT character_maximum_length
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'orders' AND column_name = 'order_type'
                """, Integer.class));
    }

    @Test
    void v8RemovesRetiredPendingOrderLimitConfigsFromAnExistingV7Database() {
        flyway("7").migrate();
        for (String retiredKey : List.of("max_pending_orders_per_ip", "max_pending_orders_per_user")) {
            jdbcTemplate.update("""
                    INSERT INTO site_configs(config_key, config_value, config_group)
                    VALUES (?, '5', 'risk')
                    """, retiredKey);
        }
        jdbcTemplate.update("""
                INSERT INTO site_configs(config_key, config_value, config_group)
                VALUES ('rate_limit_per_second', '25', 'risk')
                """);

        flyway().migrate();

        assertEquals("15", flyway().info().current().getVersion().getVersion());
        for (String retiredKey : List.of("max_pending_orders_per_ip", "max_pending_orders_per_user")) {
            assertEquals(0, configCount(retiredKey),
                    "retired pending-order config must be removed: " + retiredKey);
        }
        assertEquals("25", configValue("rate_limit_per_second"));
    }

    @Test
    void v10ToV11BackfillsLegacyV8PricesOrderSnapshotsAndProductNumbers() {
        flyway("8").migrate();
        UUID userId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID specId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();

        jdbcTemplate.update("""
                INSERT INTO users(id, username, email, password_hash, role, user_no)
                VALUES (?, 'legacy-v8-user', 'legacy-v8@example.com', 'test-hash', 'USER', 10000)
                """, userId);
        jdbcTemplate.update("""
                INSERT INTO product_categories(id, name)
                VALUES (?, 'legacy-v8-category')
                """, categoryId);
        jdbcTemplate.update("""
                INSERT INTO products(id, title, base_price, category_id, customer_input_fields)
                VALUES (?, 'legacy-v8-product', 12.34, ?, '[]'::jsonb)
                """, productId, categoryId);
        jdbcTemplate.update("""
                INSERT INTO product_specs(id, product_id, name, price)
                VALUES (?, ?, 'legacy-v8-spec', 15.67)
                """, specId, productId);
        jdbcTemplate.update("""
                INSERT INTO orders(id, user_id, total_amount, actual_amount, status, order_type)
                VALUES (?, ?, 15.67, 15.67, 'PENDING', 'NORMAL')
                """, orderId, userId);
        jdbcTemplate.update("""
                INSERT INTO order_items(id, order_id, product_id, product_title, spec_id, spec_name,
                                        quantity, unit_price, subtotal, customer_inputs)
                VALUES (?, ?, ?, 'legacy-v8-product', ?, 'legacy-v8-spec', 1, 15.67, 15.67, '[]'::jsonb)
                """, itemId, orderId, productId, specId);

        flyway().migrate();

        assertEquals("15", flyway().info().current().getVersion().getVersion());
        UUID levelId = defaultUserLevelId();
        assertEquals(levelId, jdbcTemplate.queryForObject(
                "SELECT user_level_id FROM users WHERE id = ?", UUID.class, userId));
        assertEquals(new BigDecimal("12.34"), jdbcTemplate.queryForObject(
                "SELECT retail_price FROM products WHERE id = ?", BigDecimal.class, productId));
        assertEquals(new BigDecimal("12.34"), jdbcTemplate.queryForObject(
                "SELECT cost_price FROM products WHERE id = ?", BigDecimal.class, productId));
        assertEquals(new BigDecimal("15.67"), jdbcTemplate.queryForObject(
                "SELECT retail_price FROM product_specs WHERE id = ?", BigDecimal.class, specId));
        assertEquals(new BigDecimal("15.67"), jdbcTemplate.queryForObject(
                "SELECT cost_price FROM product_specs WHERE id = ?", BigDecimal.class, specId));
        assertNull(jdbcTemplate.queryForObject(
                "SELECT user_level_id_snapshot FROM orders WHERE id = ?", UUID.class, orderId));
        assertNull(jdbcTemplate.queryForObject(
                "SELECT user_level_name_snapshot FROM orders WHERE id = ?", String.class, orderId));
        assertEquals(new BigDecimal("15.67"), jdbcTemplate.queryForObject(
                "SELECT cost_price_snapshot FROM order_items WHERE id = ?", BigDecimal.class, itemId));
        assertEquals(new BigDecimal("15.67"), jdbcTemplate.queryForObject(
                "SELECT retail_price_snapshot FROM order_items WHERE id = ?", BigDecimal.class, itemId));
        assertEquals("RETAIL", jdbcTemplate.queryForObject(
                "SELECT pricing_source FROM order_items WHERE id = ?", String.class, itemId));
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT product_no FROM products WHERE id = ?", Long.class, productId));
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT product_no FROM order_items WHERE id = ?", Long.class, itemId));
        assertFalse(columnExists("products", "product_code"));
        assertFalse(columnExists("order_items", "product_code"));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT sort_weight FROM products WHERE id = ?", Integer.class, productId));
        assertFalse(tableExists("wholesale_rules"));
        assertFalse(columnExists("products", "base_price"));
        assertFalse(columnExists("products", "wholesale_enabled"));
        assertFalse(columnExists("product_specs", "price"));
        assertFalse(columnExists("order_items", "cost_price"));
        assertFalse(columnExists("order_items", "retail_price"));
    }

    @Test
    void v9EnforcesDefaultLevelRoleAndOverrideConstraints() {
        flyway().migrate();
        UUID levelId = defaultUserLevelId();
        UUID productId = UUID.randomUUID();
        UUID specProductId = UUID.randomUUID();
        UUID otherProductId = UUID.randomUUID();
        UUID productSpecId = UUID.randomUUID();
        UUID otherProductSpecId = UUID.randomUUID();
        insertCurrentProduct(productId, "override-product");
        insertCurrentSpecProduct(specProductId, "spec-override-product");
        insertCurrentProduct(otherProductId, "other-override-product");
        insertCurrentSpec(productSpecId, specProductId, "override-spec");
        insertCurrentSpec(otherProductSpecId, otherProductId, "other-spec");

        jdbcTemplate.update("""
                INSERT INTO product_level_price_overrides(product_id, user_level_id, price)
                VALUES (?, ?, 8.00)
                """, productId, levelId);
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                INSERT INTO product_level_price_overrides(product_id, user_level_id, price)
                VALUES (?, ?, 11.00)
                """, productId, levelId));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                INSERT INTO product_level_price_overrides(product_id, user_level_id, price)
                VALUES (?, ?, 7.00)
                """, productId, levelId));

        jdbcTemplate.update("""
                INSERT INTO product_level_price_overrides(product_id, spec_id, user_level_id, price)
                VALUES (?, ?, ?, 9.00)
                """, specProductId, productSpecId, levelId);
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                INSERT INTO product_level_price_overrides(product_id, spec_id, user_level_id, price)
                VALUES (?, ?, ?, 8.50)
                """, specProductId, productSpecId, levelId));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                INSERT INTO product_level_price_overrides(product_id, spec_id, user_level_id, price)
                VALUES (?, ?, ?, 8.50)
                """, specProductId, otherProductSpecId, levelId));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                INSERT INTO product_level_price_overrides(product_id, user_level_id, price)
                VALUES (?, ?, 8.50)
                """, specProductId, levelId));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                INSERT INTO product_level_price_overrides(product_id, spec_id, user_level_id, price)
                VALUES (?, ?, ?, 8.50)
                """, productId, productSpecId, levelId));
    }

    @Test
    void v9EnforcesLevelAndProductMoneyInvariants() {
        flyway().migrate();

        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                INSERT INTO user_levels(name, pricing_type, markup_rate)
                VALUES ('普通会员', 'RETAIL', NULL)
                """));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                INSERT INTO user_levels(name, pricing_type, markup_rate)
                VALUES ('invalid-markup-null', 'COST_MARKUP', NULL)
                """));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                INSERT INTO user_levels(name, pricing_type, markup_rate)
                VALUES ('invalid-markup-negative', 'COST_MARKUP', -0.0001)
                """));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                INSERT INTO user_levels(name, pricing_type, markup_rate)
                VALUES ('invalid-retail-markup', 'RETAIL', 1.0000)
                """));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                INSERT INTO user_levels(name, pricing_type, markup_rate, enabled, is_default)
                VALUES ('invalid-disabled-default', 'RETAIL', NULL, false, true)
                """));

        UUID productId = UUID.randomUUID();
        UUID specProductId = UUID.randomUUID();
        UUID specId = UUID.randomUUID();
        insertCurrentProduct(productId, "money-product");
        insertCurrentSpecProduct(specProductId, "money-spec-product");
        insertCurrentSpec(specId, specProductId, "money-spec");

        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "UPDATE products SET cost_price = NULL WHERE id = ?", productId));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "UPDATE products SET cost_price = -0.01 WHERE id = ?", productId));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "UPDATE products SET retail_price = 0 WHERE id = ?", productId));
        assertNull(jdbcTemplate.queryForObject(
                "SELECT cost_price FROM products WHERE id = ?", BigDecimal.class, specProductId));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "UPDATE product_specs SET cost_price = -0.01 WHERE id = ?", specId));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "UPDATE product_specs SET retail_price = 0 WHERE id = ?", specId));
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
                INSERT INTO products(id, title, retail_price, cost_price, category_id, customer_input_fields)
                VALUES (?, 'baseline-product', 10.00, 10.00, ?, ?::jsonb)
                """, productId, categoryId,
                "[{\"id\":\"account\",\"label\":\"账号\"},{\"id\":\"zone\",\"label\":\"区服\"}]");
        jdbcTemplate.update("""
                INSERT INTO orders(id, total_amount, actual_amount, status)
                VALUES (?, 10.00, 10.00, 'PENDING')
                """, orderId);
        jdbcTemplate.update("""
                INSERT INTO order_items(id, order_id, product_id, product_no, product_title, quantity, unit_price,
                                        cost_price_snapshot, retail_price_snapshot, pricing_source, subtotal, customer_inputs)
                VALUES (?, ?, ?, (SELECT product_no FROM products WHERE id = ?), 'baseline-product', 1, 10.00,
                        10.00, 10.00, 'RETAIL', 10.00, ?::jsonb)
                """, orderItemId, orderId, productId, productId, "[]");
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

    private UUID defaultUserLevelId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM user_levels WHERE is_default = true", UUID.class);
    }

    private void insertCurrentProduct(UUID productId, String title) {
        jdbcTemplate.update("""
                INSERT INTO products(id, title, retail_price, cost_price, category_id)
                VALUES (?, ?, 10.00, 10.00, ?)
                """, productId, title, UUID.randomUUID());
    }

    private void insertCurrentSpecProduct(UUID productId, String title) {
        jdbcTemplate.update("""
                INSERT INTO products(id, title, retail_price, cost_price, spec_enabled, category_id)
                VALUES (?, ?, 10.00, NULL, true, ?)
                """, productId, title, UUID.randomUUID());
    }

    private void insertCurrentSpec(UUID specId, UUID productId, String name) {
        jdbcTemplate.update("""
                INSERT INTO product_specs(id, product_id, name, retail_price, cost_price)
                VALUES (?, ?, ?, 10.00, 10.00)
                """, specId, productId, name);
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

    private boolean sequenceExists(String sequence) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.sequences
                WHERE sequence_schema = 'public' AND sequence_name = ?
                """, Integer.class, sequence);
        return count != null && count == 1;
    }

    private boolean functionExists(String functionName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_proc p
                JOIN pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname = 'public' AND p.proname = ?
                """, Integer.class, functionName);
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
