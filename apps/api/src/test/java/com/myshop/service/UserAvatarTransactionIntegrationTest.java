package com.myshop.service;

import com.myshop.constant.UserRole;
import com.myshop.entity.User;
import com.myshop.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises avatar files and the real PostgreSQL transaction boundary
 * together. The image service is deliberately real so the callback checks
 * prove physical file cleanup rather than only Mockito call ordering.
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@SpringBootTest
class UserAvatarTransactionIntegrationTest {

    private static final String PREVIOUS_URL =
            "/api/uploads/avatars/avatar-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.png";
    private static final String PREVIOUS_RELATIVE_PATH =
            "avatars/avatar-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.png";
    private static final String ROLLBACK_CONSTRAINT = "user_avatar_transaction_rollback_check";
    private static final Path UPLOAD_DIRECTORY = createUploadDirectory();
    private static final AtomicLong USER_NO = new AtomicLong(700_000_000L);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private UserAvatarService userAvatarService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.open-in-view", () -> false);
        registry.add("upload.path", UPLOAD_DIRECTORY::toString);
        registry.add("upload.url-prefix", () -> "/api/uploads");
        registry.add("jwt.secret", () ->
                "avatar-transaction-integration-secret-with-at-least-64-bytes-2026");
        registry.add("mail.enabled", () -> false);
        registry.add("bootstrap.admin.username", () -> "");
        registry.add("bootstrap.admin.email", () -> "");
        registry.add("bootstrap.admin.password", () -> "");
    }

    @AfterEach
    void cleanFixture() throws IOException {
        jdbcTemplate.execute("ALTER TABLE users DROP CONSTRAINT IF EXISTS " + ROLLBACK_CONSTRAINT);
        jdbcTemplate.update("DELETE FROM after_sale_attachments WHERE request_id IN (SELECT id FROM after_sale_requests WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'avatar-transaction-%'))");
        jdbcTemplate.update("DELETE FROM after_sale_requests WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'avatar-transaction-%')");
        jdbcTemplate.update("DELETE FROM users WHERE username LIKE 'avatar-transaction-%'");
        clearUploadDirectory();
        entityManager.clear();
    }

    @AfterAll
    static void removeUploadDirectory() throws IOException {
        if (Files.exists(UPLOAD_DIRECTORY)) {
            try (var paths = Files.walk(UPLOAD_DIRECTORY)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException exception) {
                        throw new RuntimeException(exception);
                    }
                });
            }
        }
    }

    @Test
    void committedDatabaseUpdateDeletesPreviousFileOnlyAfterCommit() throws IOException {
        User user = persistUserWithPreviousAvatar();

        var result = userAvatarService.uploadAvatar(user.getId(), validPng());

        entityManager.clear();
        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertEquals(result.getAvatarUrl(), reloaded.getAvatarUrl());
        assertFalse(Files.exists(UPLOAD_DIRECTORY.resolve(PREVIOUS_RELATIVE_PATH)));
        assertTrue(Files.exists(UPLOAD_DIRECTORY.resolve(relativePath(result.getAvatarUrl()))));
    }

    @Test
    void databaseFlushFailureRollsBackUrlAndDeletesOnlyNewFile() throws IOException {
        User user = persistUserWithPreviousAvatar();
        jdbcTemplate.execute("""
                ALTER TABLE users
                ADD CONSTRAINT user_avatar_transaction_rollback_check
                CHECK (avatar_url IS NULL OR avatar_url = '/api/uploads/avatars/avatar-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.png')
                """);

        assertThrows(RuntimeException.class,
                () -> userAvatarService.uploadAvatar(user.getId(), validPng()));

        entityManager.clear();
        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertEquals(PREVIOUS_URL, reloaded.getAvatarUrl());
        assertTrue(Files.exists(UPLOAD_DIRECTORY.resolve(PREVIOUS_RELATIVE_PATH)));

        Path avatarsDirectory = UPLOAD_DIRECTORY.resolve("avatars");
        try (var files = Files.list(avatarsDirectory)) {
            List<String> filenames = files.map(path -> path.getFileName().toString()).toList();
            assertEquals(List.of(Path.of(PREVIOUS_RELATIVE_PATH).getFileName().toString()), filenames);
        }
    }

    private User persistUserWithPreviousAvatar() throws IOException {
        Path previousFile = UPLOAD_DIRECTORY.resolve(PREVIOUS_RELATIVE_PATH);
        Files.createDirectories(previousFile.getParent());
        Files.write(previousFile, pngBytes());

        UUID defaultLevelId = jdbcTemplate.queryForObject(
                "SELECT id FROM user_levels WHERE is_default = true AND enabled = true LIMIT 1",
                UUID.class);
        User user = new User();
        user.setUsername("avatar-transaction-" + UUID.randomUUID());
        user.setEmail(user.getUsername() + "@example.com");
        user.setPasswordHash("test-password-hash");
        user.setRole(UserRole.USER);
        user.setUserNo(USER_NO.incrementAndGet());
        user.setUserLevelId(defaultLevelId);
        user.setAvatarUrl(PREVIOUS_URL);
        return userRepository.saveAndFlush(user);
    }

    private static MockMultipartFile validPng() {
        return new MockMultipartFile("file", "replacement.png", "image/png", pngBytes());
    }

    private static byte[] pngBytes() {
        return new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    }

    private static String relativePath(String url) {
        return url.substring("/api/uploads/".length());
    }

    private static Path createUploadDirectory() {
        try {
            return Files.createTempDirectory("myshop-avatar-transaction-");
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void clearUploadDirectory() throws IOException {
        if (!Files.exists(UPLOAD_DIRECTORY)) {
            return;
        }
        try (var paths = Files.walk(UPLOAD_DIRECTORY)) {
            paths.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(UPLOAD_DIRECTORY))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            throw new RuntimeException(exception);
                        }
                    });
        }
    }
}
