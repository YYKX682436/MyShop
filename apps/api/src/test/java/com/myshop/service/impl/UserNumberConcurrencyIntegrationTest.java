package com.myshop.service.impl;

import com.myshop.model.request.RegisterRequest;
import com.myshop.service.AuthService;
import com.myshop.utils.CaptchaUtils;
import com.myshop.utils.JwtUtils;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Proves the real PostgreSQL row lock, rather than a JVM-only counter, serializes registration. */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@SpringBootTest
class UserNumberConcurrencyIntegrationTest {

    private static final int REGISTRATION_COUNT = 12;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private AuthService authService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
        registry.add("jwt.secret", () -> "user-number-integration-secret-with-at-least-64-bytes-and-random-looking-value-2026");
        registry.add("mail.enabled", () -> false);
        registry.add("bootstrap.admin.username", () -> "");
        registry.add("bootstrap.admin.email", () -> "");
        registry.add("bootstrap.admin.password", () -> "");
    }

    @BeforeEach
    void resetUsersAndMocks() {
        jdbcTemplate.update("DELETE FROM users");
        jdbcTemplate.update("UPDATE user_number_sequences SET start_user_no = 10000, next_user_no = 10000 WHERE id = 1");
        when(captchaUtils.verify(anyString(), anyString())).thenReturn(true);
        when(jwtUtils.generateToken(any(), anyString(), anyString())).thenReturn("integration-token");
    }

    @Test
    void concurrentRegistrationsReceiveDistinctIncreasingNumbers() throws Exception {
        CountDownLatch ready = new CountDownLatch(REGISTRATION_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(REGISTRATION_COUNT);
        List<Future<Long>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < REGISTRATION_COUNT; index++) {
                int registrationIndex = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    RegisterRequest request = new RegisterRequest();
                    request.setUsername("c" + registrationIndex
                            + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
                    request.setEmail("concurrent-" + registrationIndex + "-" + UUID.randomUUID() + "@example.com");
                    request.setPassword("a-secure-password");
                    request.setPhone(String.format("1380013%04d", registrationIndex));
                    request.setCaptchaId("captcha-" + registrationIndex);
                    request.setCaptcha("1234");
                    return authService.register(request).user().getUserNo();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            List<Long> numbers = new ArrayList<>();
            for (Future<Long> future : futures) {
                numbers.add(future.get(20, TimeUnit.SECONDS));
            }
            numbers.sort(Long::compareTo);
            for (int index = 0; index < REGISTRATION_COUNT; index++) {
                assertEquals(10000L + index, numbers.get(index));
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(REGISTRATION_COUNT, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE role = 'USER'", Integer.class));
        assertEquals(REGISTRATION_COUNT, jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT user_no) FROM users WHERE role = 'USER'", Integer.class));
        assertEquals(10000L + REGISTRATION_COUNT, jdbcTemplate.queryForObject(
                "SELECT next_user_no FROM user_number_sequences WHERE id = 1", Long.class));
    }
}
