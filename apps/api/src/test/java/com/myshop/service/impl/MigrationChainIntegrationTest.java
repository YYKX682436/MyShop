package com.myshop.service.impl;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
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

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        flyway(null).clean();
    }

    @Test
    void cleanV1ToV5CreatesStructuredColumns() {
        flyway(null).migrate();

        assertEquals("5", flyway(null).info().current().getVersion().getVersion());
        assertTrue(columnExists("products", "customer_input_fields"));
        assertTrue(columnExists("cart_items", "customer_inputs"));
        assertTrue(columnExists("order_items", "customer_inputs"));
        assertTrue(columnExists("order_items", "delivery_notified_at"));
    }

    @Test
    void V4DataUpgradesToV5AndPreservesLegacyLabels() {
        flyway("4").migrate();
        UUID categoryId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID orderItemId = UUID.randomUUID();
        UUID cartItemId = UUID.randomUUID();
        UUID phoneProductId = UUID.randomUUID();
        UUID eitherProductId = UUID.randomUUID();
        UUID noneProductId = UUID.randomUUID();

        jdbcTemplate.update("""
                insert into product_categories (id, created_at, updated_at, name, sort_order, is_deleted)
                values (?, current_timestamp, current_timestamp, 'migration-category', 0, 0)
                """, categoryId);
        jdbcTemplate.update("""
                insert into products (id, created_at, updated_at, title, base_price, category_id,
                    delivery_type, customer_input_type, stock_mode, is_enabled, is_deleted)
                values (?, current_timestamp, current_timestamp, 'migration-product', 10.00, ?,
                    'AUTO', 'QQ', 'CARD_KEY', true, 0)
                """, productId, categoryId);
        insertLegacyProduct(phoneProductId, categoryId, "PHONE");
        insertLegacyProduct(eitherProductId, categoryId, "QQ_OR_PHONE");
        insertLegacyProduct(noneProductId, categoryId, "NONE");
        jdbcTemplate.update("""
                insert into orders (id, created_at, updated_at, total_amount, actual_amount, status, order_type)
                values (?, current_timestamp, current_timestamp, 10.00, 10.00, 'PENDING', 'DIRECT')
                """, orderId);
        jdbcTemplate.update("""
                insert into order_items (id, created_at, updated_at, order_id, product_id, product_title,
                    quantity, unit_price, subtotal, delivery_type, customer_input_type,
                    customer_input_value, stock_mode, fulfillment_status, manual_stock_reserved)
                values (?, current_timestamp, current_timestamp, ?, ?, 'migration-product', 1, 10.00, 10.00,
                    'AUTO', 'PHONE', '13800138000', 'CARD_KEY', 'PENDING', 0)
                """, orderItemId, orderId, productId);
        jdbcTemplate.update("""
                insert into cart_items (id, created_at, updated_at, session_token, product_id,
                    customer_input_value, quantity)
                values (?, current_timestamp, current_timestamp, 'migration-session', ?, 'legacy-qq', 1)
                """, cartItemId, productId);

        flyway(null).migrate();

        assertEquals("QQ号码", jdbcTemplate.queryForObject(
                "select customer_input_fields->0->>'label' from products where id = ?", String.class, productId));
        assertEquals("手机号", jdbcTemplate.queryForObject(
                "select customer_input_fields->0->>'label' from products where id = ?", String.class, phoneProductId));
        assertEquals("QQ或手机号", jdbcTemplate.queryForObject(
                "select customer_input_fields->0->>'label' from products where id = ?", String.class, eitherProductId));
        assertEquals(0, jdbcTemplate.queryForObject(
                "select jsonb_array_length(customer_input_fields) from products where id = ?", Integer.class, noneProductId));
        assertEquals("QQ号码", jdbcTemplate.queryForObject(
                "select customer_inputs->0->>'label' from cart_items where id = ?", String.class, cartItemId));
        assertEquals("legacy-qq", jdbcTemplate.queryForObject(
                "select customer_inputs->0->>'value' from cart_items where id = ?", String.class, cartItemId));
        assertEquals("手机号", jdbcTemplate.queryForObject(
                "select customer_inputs->0->>'label' from order_items where id = ?", String.class, orderItemId));
        assertEquals("13800138000", jdbcTemplate.queryForObject(
                "select customer_inputs->0->>'value' from order_items where id = ?", String.class, orderItemId));
    }

    private Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource())
                .locations("classpath:db/migration");
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

    private void insertLegacyProduct(UUID productId, UUID categoryId, String customerInputType) {
        jdbcTemplate.update("""
                insert into products (id, created_at, updated_at, title, base_price, category_id,
                    delivery_type, customer_input_type, stock_mode, is_enabled, is_deleted)
                values (?, current_timestamp, current_timestamp, ?, 10.00, ?,
                    'AUTO', ?, 'CARD_KEY', true, 0)
                """, productId, "legacy-" + customerInputType, categoryId, customerInputType);
    }

    private boolean columnExists(String table, String column) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema = 'public' and table_name = ? and column_name = ?
                """, Integer.class, table, column);
        return count != null && count == 1;
    }
}
