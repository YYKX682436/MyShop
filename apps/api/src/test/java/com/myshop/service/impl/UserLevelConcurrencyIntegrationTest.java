package com.myshop.service.impl;

import com.myshop.constant.PricingType;
import com.myshop.model.request.UserLevelAssignmentRequest;
import com.myshop.model.request.UserLevelMigrationRequest;
import com.myshop.model.request.UserLevelUpsertRequest;
import com.myshop.service.OperationLogService;
import com.myshop.service.UserLevelService;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real PostgreSQL lock waits exercise level changes against account writes. */
@EnabledIf("postgresAvailable")
@SpringBootTest
class UserLevelConcurrencyIntegrationTest {

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
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET statement_timeout = '15s'");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("jwt.secret", () -> "level-concurrency-test-secret-at-least-64-bytes-long-and-not-a-production-secret");
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

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private UserLevelService levels;
    @MockitoBean private OperationLogService operationLogService;

    private UUID originalDefault;
    private UUID firstLevel;
    private UUID secondLevel;
    private String fixturePrefix;

    @BeforeEach
    void createLevels() {
        originalDefault = jdbc.queryForObject("SELECT id FROM user_levels WHERE is_default = true", UUID.class);
        fixturePrefix = "level-lock-" + UUID.randomUUID();
        // String/DB ordering puts firstLevel before secondLevel even though
        // UUID.compareTo uses signed longs and orders these two the other way.
        firstLevel = UUID.fromString("10000000-" + UUID.randomUUID().toString().substring(9));
        secondLevel = UUID.fromString("f0000000-" + UUID.randomUUID().toString().substring(9));
        insertLevel(firstLevel, "-first");
        insertLevel(secondLevel, "-second");
    }

    @AfterEach
    void removeFixtures() {
        jdbc.update("UPDATE user_levels SET is_default = false WHERE is_default = true");
        jdbc.update("UPDATE user_levels SET is_default = true WHERE id = ?", originalDefault);
        jdbc.update("DELETE FROM users WHERE user_level_id IN (SELECT id FROM user_levels WHERE name LIKE ?)",
                fixturePrefix + "%");
        jdbc.update("DELETE FROM user_levels WHERE name LIKE ?", fixturePrefix + "%");
    }

    @Test
    void assignmentWaitingForItsLevelDoesNotBlockOrOverwriteAccountWrites() throws Exception {
        UUID user = insertUser(firstLevel);
        var worker = Executors.newSingleThreadExecutor();
        try (Connection held = dataSource.getConnection()) {
            held.setAutoCommit(false);
            lockLevel(held, secondLevel);
            var assignment = worker.submit(() -> levels.assignUser(user, new UserLevelAssignmentRequest(secondLevel)));
            try {
                awaitLevelWaiters(1);
                // Models migration's level -> user order. An assignment must
                // not hold this user while waiting for the same level.
                try (var timeout = held.createStatement()) {
                    timeout.execute("SET LOCAL lock_timeout = '2s'");
                }
                try (var update = held.prepareStatement("UPDATE users SET balance = 90, auth_version = 7 WHERE id = ?")) {
                    update.setObject(1, user);
                    assertEquals(1, update.executeUpdate());
                }
                held.commit();
                assignment.get(20, TimeUnit.SECONDS);
            } finally {
                held.rollback();
            }
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(20, TimeUnit.SECONDS));
        }
        assertEquals(secondLevel, jdbc.queryForObject("SELECT user_level_id FROM users WHERE id = ?", UUID.class, user));
        assertEquals(new BigDecimal("90.00"), jdbc.queryForObject("SELECT balance FROM users WHERE id = ?", BigDecimal.class, user));
        assertEquals(7, jdbc.queryForObject("SELECT auth_version FROM users WHERE id = ?", Integer.class, user));
    }

    @Test
    void oppositeMigrationsSerializeWithoutADeadlock() throws Exception {
        UUID firstUser = insertUser(firstLevel);
        UUID secondUser = insertUser(secondLevel);
        var workers = Executors.newFixedThreadPool(2);
        try (Connection held = dataSource.getConnection()) {
            held.setAutoCommit(false);
            lockLevel(held, secondLevel);
            var backward = workers.submit(() -> levels.migrate(secondLevel, new UserLevelMigrationRequest(firstLevel)));
            try {
                awaitLevelWaiters(1);
                var forward = workers.submit(() -> levels.migrate(firstLevel, new UserLevelMigrationRequest(secondLevel)));
                awaitLevelWaiters(2);
                held.commit();
                assertEquals(1, backward.get(20, TimeUnit.SECONDS));
                assertEquals(2, forward.get(20, TimeUnit.SECONDS));
            } finally {
                held.rollback();
            }
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS));
        }
        for (UUID user : java.util.List.of(firstUser, secondUser)) {
            assertEquals(secondLevel, jdbc.queryForObject("SELECT user_level_id FROM users WHERE id = ?", UUID.class, user));
        }
    }

    @Test
    void defaultSwitchAndMigrationUseTheSameLevelLockOrder() throws Exception {
        jdbc.update("UPDATE user_levels SET is_default = false WHERE is_default = true");
        jdbc.update("UPDATE user_levels SET is_default = true WHERE id = ?", firstLevel);
        UUID user = insertUser(firstLevel);
        var workers = Executors.newFixedThreadPool(2);
        try (Connection held = dataSource.getConnection()) {
            held.setAutoCommit(false);
            lockLevel(held, secondLevel);
            var changeDefault = workers.submit(() -> levels.setDefault(secondLevel));
            try {
                awaitLevelWaiters(1);
                var migration = workers.submit(() -> levels.migrate(firstLevel, new UserLevelMigrationRequest(secondLevel)));
                awaitLevelWaiters(2);
                held.commit();
                changeDefault.get(20, TimeUnit.SECONDS);
                assertEquals(1, migration.get(20, TimeUnit.SECONDS));
            } finally {
                held.rollback();
            }
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS));
        }
        assertDefault(secondLevel);
        assertEquals(secondLevel, jdbc.queryForObject("SELECT user_level_id FROM users WHERE id = ?", UUID.class, user));
    }

    @Test
    void creatingAndUpdatingDefaultsPreserveTheSingleDefaultConstraint() {
        var created = levels.create(new UserLevelUpsertRequest(
                fixturePrefix + "-created", PricingType.RETAIL, null, true, true, 0, null,
                null, null, null, null));
        assertDefault(created.id());
        levels.update(firstLevel, new UserLevelUpsertRequest(
                fixturePrefix + "-first", PricingType.RETAIL, null, true, true, 0, null,
                null, null, null, null));
        assertDefault(firstLevel);
        levels.setDefault(secondLevel);
        assertDefault(secondLevel);
    }

    private void insertLevel(UUID id, String suffix) {
        Integer rank = jdbc.queryForObject("SELECT coalesce(max(upgrade_rank), -100) + 100 FROM user_levels", Integer.class);
        jdbc.update("INSERT INTO user_levels (id, name, pricing_type, enabled, is_default, upgrade_rank) VALUES (?, ?, 'RETAIL', true, false, ?)",
                id, fixturePrefix + suffix, rank);
    }

    private UUID insertUser(UUID level) {
        UUID id = UUID.randomUUID();
        long userNo = Math.floorMod(id.getLeastSignificantBits(), 1_000_000_000L) + 10000;
        jdbc.update("INSERT INTO users (id, username, password_hash, role, user_no, user_level_id, balance) "
                        + "VALUES (?, ?, 'test-password-hash', 'USER', ?, ?, 100)",
                id, "level_" + id.toString().substring(0, 8), userNo, level);
        return id;
    }

    private void lockLevel(Connection connection, UUID id) throws Exception {
        try (var statement = connection.prepareStatement("SELECT id FROM user_levels WHERE id = ? FOR UPDATE")) {
            statement.setObject(1, id);
            try (var row = statement.executeQuery()) {
                assertTrue(row.next());
            }
        }
    }

    private void awaitLevelWaiters(int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            Integer waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity "
                            + "WHERE datname = current_database() AND pid <> pg_backend_pid() "
                            + "AND state = 'active' AND wait_event_type = 'Lock' AND query ILIKE '%user_levels%'",
                    Integer.class);
            if (waiting != null && waiting >= expected) return;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Expected " + expected + " real PostgreSQL level lock waiters");
    }

    private void assertDefault(UUID id) {
        assertEquals(id, jdbc.queryForObject("SELECT id FROM user_levels WHERE is_default = true", UUID.class));
    }
}
