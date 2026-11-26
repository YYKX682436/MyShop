package com.myshop.service.impl;

import com.myshop.constant.BalanceAdjustmentType;
import com.myshop.constant.UserRole;
import com.myshop.context.RequestContext;
import com.myshop.entity.User;
import com.myshop.exception.BusinessException;
import com.myshop.model.request.AdminAdjustBalanceRequest;
import com.myshop.repository.UserRepository;
import com.myshop.service.AdminUserService;
import com.myshop.utils.CaptchaUtils;
import com.myshop.utils.JwtUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Proves that the real PostgreSQL row lock serializes balance adjustments. */
@org.junit.jupiter.api.condition.EnabledIf("postgresAvailable")
@ExtendWith(SpringExtension.class)
@SpringBootTest
class BalanceAdjustmentConcurrencyIntegrationTest {

    static final IntegrationPostgres POSTGRES = new IntegrationPostgres();
    static boolean postgresAvailable() { return POSTGRES.available(); }
    @org.junit.jupiter.api.AfterAll static void stopPostgres() { POSTGRES.close(); }

    @Autowired
    private AdminUserService adminUserService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private CaptchaUtils captchaUtils;

    @MockitoBean
    private JwtUtils jwtUtils;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("jwt.secret", () -> "balance-adjustment-integration-secret-with-at-least-64-bytes-and-random-looking-value-2026");
        registry.add("mail.enabled", () -> false);
        registry.add("bootstrap.admin.username", () -> "");
        registry.add("bootstrap.admin.email", () -> "");
        registry.add("bootstrap.admin.password", () -> "");
    }

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM support_system_events");
        jdbcTemplate.update("DELETE FROM support_activity_receipts");
        jdbcTemplate.update("DELETE FROM support_activity");
        jdbcTemplate.update("DELETE FROM support_messages");
        jdbcTemplate.update("DELETE FROM support_read_states");
        jdbcTemplate.update("DELETE FROM support_conversations");
        jdbcTemplate.update("DELETE FROM balance_transactions");
        jdbcTemplate.update("DELETE FROM operation_logs");
        jdbcTemplate.update("DELETE FROM users");
        when(captchaUtils.verify(anyString(), anyString())).thenReturn(true);
        when(jwtUtils.generateToken(any(), anyString(), anyString(), anyInt())).thenReturn("integration-token");
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    @Test
    void concurrentAdjustmentsKeepFinalBalanceAndLedgerChainContinuous() throws Exception {
        User target = new User();
        target.setUsername("balance-concurrency-user");
        target.setEmail("balance-concurrency@example.com");
        target.setPasswordHash("test-password-hash");
        target.setRole(UserRole.USER);
        target.setUserNo(10000L);
        target.setUserLevelId(jdbcTemplate.queryForObject(
                "SELECT id FROM user_levels WHERE is_default = true", UUID.class));
        target.setBalance(new BigDecimal("0.00"));
        target.setIsDeleted(0);
        target = userRepository.saveAndFlush(target);
        UUID userId = target.getId();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < 2; index++) {
                int adjustmentIndex = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    RequestContext.set(new RequestContext.UserInfo(
                            UUID.randomUUID(), "integration-admin", "ADMIN"));
                    try {
                        AdminAdjustBalanceRequest request = new AdminAdjustBalanceRequest();
                        request.setType(BalanceAdjustmentType.INCREASE);
                        request.setAmount(new BigDecimal("1.00"));
                        request.setReason("并发余额调整 " + adjustmentIndex);
                        return adminUserService.adjustBalance(userId, request);
                    } finally {
                        RequestContext.clear();
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        BigDecimal finalBalance = jdbcTemplate.queryForObject(
                "SELECT balance FROM users WHERE id = ?", BigDecimal.class, userId);
        assertMoney("2.00", finalBalance);

        List<LedgerRow> rows = jdbcTemplate.query(
                "SELECT balance_before, balance_after FROM balance_transactions "
                        + "WHERE user_id = ? AND type = 'ADMIN_ADJUSTMENT' "
                        + "ORDER BY balance_before ASC, id ASC",
                (resultSet, rowNumber) -> new LedgerRow(
                        resultSet.getBigDecimal("balance_before"),
                        resultSet.getBigDecimal("balance_after")),
                userId);

        assertEquals(2, rows.size());
        assertMoney("0.00", rows.get(0).before());
        assertMoney("1.00", rows.get(0).after());
        assertMoney("1.00", rows.get(1).before());
        assertMoney("2.00", rows.get(1).after());
        assertEquals(2, jdbcTemplate.queryForObject("SELECT count(*) FROM support_system_events WHERE source_type='BALANCE_TRANSACTION' AND event_type='BALANCE_ADJUSTMENT' AND user_id=?", Integer.class, userId));
        assertEquals(2, jdbcTemplate.queryForObject("SELECT count(*) FROM support_activity WHERE source_type='SYSTEM_RESULT' AND notify_customer", Integer.class));
    }

    @Test
    void adminLedgerLocationRejectsAnotherUsersTransaction() {
        User target = new User();
        target.setUsername("ledger-location-user"); target.setEmail("ledger-location@example.com");
        target.setPasswordHash("test-password-hash"); target.setRole(UserRole.USER); target.setUserNo(19001L);
        target.setUserLevelId(jdbcTemplate.queryForObject("SELECT id FROM user_levels WHERE is_default=true", UUID.class));
        target.setBalance(new BigDecimal("0.00")); target.setIsDeleted(0);
        UUID userId = userRepository.saveAndFlush(target).getId();
        UUID adminId = UUID.randomUUID();
        RequestContext.set(new RequestContext.UserInfo(adminId, "ledger-admin", "ADMIN"));
        AdminAdjustBalanceRequest adjustment = new AdminAdjustBalanceRequest();
        adjustment.setType(BalanceAdjustmentType.INCREASE); adjustment.setAmount(new BigDecimal("3.00")); adjustment.setReason("测试流水定位");
        UUID ledgerId = adminUserService.adjustBalance(userId, adjustment).id();

        var location = adminUserService.balanceTransactionLocation(userId, ledgerId, 10);
        assertEquals(ledgerId, ((com.myshop.model.response.BalanceTransactionResponse) location.get("transaction")).id());
        assertEquals(1, location.get("page"));
        assertThrows(BusinessException.class, () -> adminUserService.balanceTransactionLocation(UUID.randomUUID(), ledgerId, 10));
    }

    private void assertMoney(String expected, BigDecimal actual) {
        assertTrue(actual != null, "expected a persisted money value");
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    private record LedgerRow(BigDecimal before, BigDecimal after) {
    }
}
