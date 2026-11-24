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
import com.myshop.service.impl.IntegrationPostgres;
import org.junit.jupiter.api.condition.EnabledIf;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises avatar files and the real PostgreSQL transaction boundary
 * together. The image service is deliberately real so the callback checks
 * prove physical file cleanup rather than only Mockito call ordering.
 */
@EnabledIf(value = "postgresAvailable", disabledReason = "PostgreSQL test database or Docker required")
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

    static final IntegrationPostgres POSTGRES = new IntegrationPostgres();
    static boolean postgresAvailable() { return POSTGRES.available(); }

    @Autowired
    private UserAvatarService userAvatarService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;
    @Autowired private ImageUploadService images;
    @Autowired private MediaAssetRegistry assets;
    @Autowired private MediaMaintenanceService maintenance;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired private javax.sql.DataSource dataSource;

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
        jdbcTemplate.update("DELETE FROM media_references WHERE owner_type='AVATAR' AND owner_id IN (SELECT id FROM users WHERE username LIKE 'avatar-transaction-%')");
        jdbcTemplate.update("DELETE FROM media_assets WHERE owner_user_id IN (SELECT id FROM users WHERE username LIKE 'avatar-transaction-%')");
        jdbcTemplate.update("DELETE FROM after_sale_attachments WHERE request_id IN (SELECT id FROM after_sale_requests WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'avatar-transaction-%'))");
        jdbcTemplate.update("DELETE FROM after_sale_requests WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'avatar-transaction-%')");
        jdbcTemplate.update("DELETE FROM users WHERE username LIKE 'avatar-transaction-%'");
        clearUploadDirectory();
        entityManager.clear();
    }

    @AfterAll
    static void removeUploadDirectory() throws IOException {
        POSTGRES.close();
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
    void committedDatabaseUpdateDeletesPreviousFileOnlyAfterCommit() throws Exception {
        User user = persistUserWithPreviousAvatar();

        var result = userAvatarService.uploadAvatar(user.getId(), validPng());

        entityManager.clear();
        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertEquals(result.getAvatarUrl(), reloaded.getAvatarUrl());
        assertFalse(Files.exists(UPLOAD_DIRECTORY.resolve(PREVIOUS_RELATIVE_PATH)));
        assertTrue(Files.exists(UPLOAD_DIRECTORY.resolve(relativePath(result.getAvatarUrl()))));
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT deleted_at,cleanup_error FROM media_assets WHERE storage_path=?")) {
            statement.setString(1, PREVIOUS_RELATIVE_PATH);
            try (var row = statement.executeQuery()) {
                assertTrue(row.next()); assertNotNull(row.getTimestamp(1)); assertNull(row.getString(2));
            }
        }
    }

    @Test
    void databaseFlushFailureRollsBackUrlAndDeletesOnlyNewFile() throws Exception {
        User user = persistUserWithPreviousAvatar();
        jdbcTemplate.execute("""
                ALTER TABLE users
                ADD CONSTRAINT user_avatar_transaction_rollback_check
                CHECK (avatar_url IS NULL OR avatar_url = '/api/uploads/avatars/avatar-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.png')
                """);

        MockMultipartFile replacement = validPng();
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> userAvatarService.uploadAvatar(user.getId(), replacement));
        Throwable cause = failure;
        while (cause != null && !(cause instanceof SQLException)) {
            cause = cause.getCause();
        }
        assertTrue(cause instanceof SQLException, "Expected a database constraint failure");
        assertEquals("23514", ((SQLException) cause).getSQLState());
        assertTrue(cause.getMessage().contains(ROLLBACK_CONSTRAINT));

        entityManager.clear();
        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertEquals(PREVIOUS_URL, reloaded.getAvatarUrl());
        assertTrue(Files.exists(UPLOAD_DIRECTORY.resolve(PREVIOUS_RELATIVE_PATH)));
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT deleted_at FROM media_assets WHERE storage_path=?")) {
            statement.setString(1, PREVIOUS_RELATIVE_PATH);
            try (var row = statement.executeQuery()) { assertTrue(row.next()); assertNull(row.getTimestamp(1)); }
        }

        Path avatarsDirectory = UPLOAD_DIRECTORY.resolve("avatars");
        try (var files = Files.list(avatarsDirectory)) {
            List<String> filenames = files.map(path -> path.getFileName().toString()).toList();
            assertEquals(List.of(Path.of(PREVIOUS_RELATIVE_PATH).getFileName().toString()), filenames);
        }
    }

    @Test
    void changingAccountAvatarCannotDeleteSharedProductImage() throws Exception {
        User user = persistUserWithPreviousAvatar();
        var productImage = images.uploadAdminImage(validPng());
        UUID category = UUID.randomUUID(), product = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO product_categories(id,name) VALUES (?,?)", category, "avatar fixture " + category);
        try {
            new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
                var refs = assets.lockProduct(productImage.url(), "");
                jdbcTemplate.update("INSERT INTO products(id,title,category_id,cover_url,retail_price,cost_price,stock_mode,delivery_type) VALUES (?,'fixture',?,?,1,0.5,'UNLIMITED','MANUAL')", product, category, productImage.url());
                assets.replaceReferences("PRODUCT", product, refs, "cover_url", "detail_md");
            });
            String firstAvatar = userAvatarService.uploadAvatar(user.getId(), validPng()).getAvatarUrl();
            String secondAvatar = userAvatarService.uploadAvatar(user.getId(), validPng()).getAvatarUrl();
            assertFalse(firstAvatar.equals(secondAvatar));
            assertTrue(Files.isRegularFile(UPLOAD_DIRECTORY.resolve(productImage.relativePath())));
            assertEquals(productImage.url(), jdbcTemplate.queryForObject("SELECT cover_url FROM products WHERE id=?", String.class, product));
            assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM media_references WHERE asset_id=? AND owner_type='PRODUCT'", Integer.class, productImage.assetId()));
            assertThrows(com.myshop.exception.BusinessException.class, () -> new org.springframework.transaction.support.TransactionTemplate(transactions)
                    .executeWithoutResult(status -> assets.lockProduct(secondAvatar, "")));
        } finally {
            jdbcTemplate.update("DELETE FROM media_references WHERE owner_type='PRODUCT' AND owner_id=?", product);
            jdbcTemplate.update("DELETE FROM products WHERE id=?", product);
            jdbcTemplate.update("DELETE FROM product_categories WHERE id=?", category);
            jdbcTemplate.update("DELETE FROM media_assets WHERE id=?", productImage.assetId());
        }
    }

    @Test
    void optimizationUsesCurrentAvatarUsersAndKeepsSharedProductReferences() throws Exception {
        User disabled = persistUserWithPreviousAvatar();
        disabled.setIsDeleted(1);
        userRepository.saveAndFlush(disabled);
        User second = persistSecondAvatarUser(PREVIOUS_URL);
        UUID category = UUID.randomUUID(), product = UUID.randomUUID();
        String categoryName = "avatar optimization " + category;
        jdbcTemplate.update("INSERT INTO product_categories(id,name) VALUES (?,?)", category, categoryName);
        jdbcTemplate.update("INSERT INTO products(id,title,category_id,cover_url,retail_price,cost_price,stock_mode,delivery_type) VALUES (?, 'avatar optimization fixture', ?, ?, 1, 0.5, 'UNLIMITED', 'MANUAL')",
                product, category, PREVIOUS_URL);
        UUID oldAsset = jdbcTemplate.queryForObject("SELECT id FROM media_assets WHERE storage_path=?", UUID.class, PREVIOUS_RELATIVE_PATH);
        jdbcTemplate.update("INSERT INTO media_references(asset_id,owner_type,owner_id,field) VALUES (?,'AVATAR',?,'avatar_url')", oldAsset, second.getId());
        jdbcTemplate.update("INSERT INTO media_references(asset_id,owner_type,owner_id,field) VALUES (?,'PRODUCT',?,'cover_url')", oldAsset, product);
        try {
            var old = assets.findAvatarAsset(oldAsset).orElseThrow();
            assertEquals(2, assets.findCurrentAvatarTargets(old).size(), "current user fields, including a disabled user, determine the optimization targets");

            ImageUploadService.UploadedImage firstOptimized = createManagedAvatar(disabled.getId(), "optimized-disabled");
            assertTrue(userAvatarService.applyOptimizedAvatar(disabled.getId(), oldAsset, PREVIOUS_URL, firstOptimized));
            entityManager.clear();
            assertEquals(firstOptimized.url(), userRepository.findById(disabled.getId()).orElseThrow().getAvatarUrl());
            assertEquals(PREVIOUS_URL, userRepository.findById(second.getId()).orElseThrow().getAvatarUrl());
            assertTrue(Files.exists(UPLOAD_DIRECTORY.resolve(PREVIOUS_RELATIVE_PATH)), "the other user's reference keeps the old shared file alive");
            assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM media_references WHERE asset_id=? AND owner_type='AVATAR'", Integer.class, oldAsset));
            assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM media_references WHERE asset_id=? AND owner_type='PRODUCT' AND owner_id=?", Integer.class, oldAsset, product));

            ImageUploadService.UploadedImage reuploaded = createManagedAvatar(second.getId(), "concurrent-reupload");
            new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
                var refs = assets.lockAvatar(reuploaded.url(), second.getId());
                User locked = userRepository.findByIdForUpdate(second.getId()).orElseThrow();
                locked.setAvatarUrl(reuploaded.url()); userRepository.save(locked);
                assets.replaceReferences("AVATAR", second.getId(), refs, "avatar_url");
            });
            ImageUploadService.UploadedImage staleOptimization = createManagedAvatar(second.getId(), "stale-optimization");
            assertFalse(userAvatarService.applyOptimizedAvatar(second.getId(), oldAsset, PREVIOUS_URL, staleOptimization),
                    "the original URL comparison inside the user row lock must reject a stale optimization");
            maintenance.retireUnreferencedAvatar(staleOptimization.url(), second.getId());

            entityManager.clear();
            assertEquals(reuploaded.url(), userRepository.findById(second.getId()).orElseThrow().getAvatarUrl());
            assertNotNull(jdbcTemplate.queryForObject("SELECT deleted_at FROM media_assets WHERE id=?", java.sql.Timestamp.class, jdbcTemplate.queryForObject("SELECT id FROM media_assets WHERE storage_path=?", UUID.class, staleOptimization.relativePath())));
            assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM media_references WHERE asset_id=? AND owner_type='AVATAR' AND owner_id=?", Integer.class, oldAsset, second.getId()));
            assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM media_references WHERE asset_id=? AND owner_type='PRODUCT' AND owner_id=?", Integer.class, oldAsset, product));
            assertTrue(Files.exists(UPLOAD_DIRECTORY.resolve(PREVIOUS_RELATIVE_PATH)), "product use still retains the old shared file");
            assertNull(jdbcTemplate.queryForObject("SELECT deleted_at FROM media_assets WHERE id=?", java.sql.Timestamp.class, oldAsset));
        } finally {
            jdbcTemplate.update("DELETE FROM media_references WHERE owner_type='PRODUCT' AND owner_id=?", product);
            jdbcTemplate.update("DELETE FROM products WHERE id=?", product);
            jdbcTemplate.update("DELETE FROM product_categories WHERE id=?", category);
        }
    }

    @Test
    void failedOptimizationTransactionKeepsOldAvatarAndDoesNotRetireIt() throws Exception {
        User user = persistUserWithPreviousAvatar();
        ImageUploadService.UploadedImage replacement = createManagedAvatar(user.getId(), "rollback-optimization");
        jdbcTemplate.execute("ALTER TABLE users ADD CONSTRAINT avatar_optimization_rollback_check CHECK (avatar_url IS NULL OR avatar_url = '" + PREVIOUS_URL + "')");
        try {
            assertThrows(RuntimeException.class,
                    () -> userAvatarService.applyOptimizedAvatar(user.getId(),
                            jdbcTemplate.queryForObject("SELECT id FROM media_assets WHERE storage_path=?", UUID.class, PREVIOUS_RELATIVE_PATH),
                            PREVIOUS_URL, replacement));
            entityManager.clear();
            assertEquals(PREVIOUS_URL, userRepository.findById(user.getId()).orElseThrow().getAvatarUrl());
            assertTrue(Files.exists(UPLOAD_DIRECTORY.resolve(PREVIOUS_RELATIVE_PATH)));
            assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM media_references WHERE owner_type='AVATAR' AND owner_id=? AND asset_id=(SELECT id FROM media_assets WHERE storage_path=?)", Integer.class, user.getId(), PREVIOUS_RELATIVE_PATH));
            assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM media_references WHERE owner_type='AVATAR' AND owner_id=? AND asset_id=?", Integer.class, user.getId(), replacement.assetId()));
        } finally {
            jdbcTemplate.execute("ALTER TABLE users DROP CONSTRAINT IF EXISTS avatar_optimization_rollback_check");
            maintenance.retireUnreferencedAvatar(replacement.url(), user.getId());
        }
    }

    private User persistSecondAvatarUser(String avatarUrl) {
        UUID defaultLevelId = jdbcTemplate.queryForObject("SELECT id FROM user_levels WHERE is_default = true AND enabled = true LIMIT 1", UUID.class);
        User user = new User();
        user.setUsername("avatar-transaction-" + UUID.randomUUID());
        user.setEmail(user.getUsername() + "@example.com");
        user.setPasswordHash("test-password-hash");
        user.setRole(UserRole.USER);
        user.setUserNo(USER_NO.incrementAndGet());
        user.setUserLevelId(defaultLevelId);
        user.setAvatarUrl(avatarUrl);
        return userRepository.saveAndFlush(user);
    }

    private ImageUploadService.UploadedImage createManagedAvatar(UUID owner, String label) throws IOException {
        UUID id = UUID.randomUUID();
        String stem = "avatars/managed-" + UUID.randomUUID();
        String display = stem + "-display.webp", thumbnail = stem + "-thumb.webp", clear = stem + "-clear.webp";
        byte[] bytes = new byte[]{'R','I','F','F',1,2,3,4,'W','E','B','P'};
        for (String path : List.of(display, thumbnail, clear)) {
            Path file = UPLOAD_DIRECTORY.resolve(path); Files.createDirectories(file.getParent()); Files.write(file, bytes);
        }
        jdbcTemplate.update("""
                INSERT INTO media_assets(id,storage_path,display_path,thumbnail_path,clear_path,original_filename,media_type,mime_type,
                  size_bytes,original_size_bytes,thumbnail_size_bytes,clear_size_bytes,width,height,scope,owner_user_id,processing_status)
                VALUES (?,?,?,?,?,?,'IMAGE','image/webp',12,128,12,12,32,32,'AVATAR',?,'READY')
                """, id, display, display, thumbnail, clear, label + ".png", owner);
        return new ImageUploadService.UploadedImage("/api/uploads/" + display, display, 128, 12,
                "compressed", id, false, "/api/uploads/" + thumbnail, "/api/uploads/" + clear);
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
        User saved = userRepository.saveAndFlush(user);
        UUID assetId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO media_assets(id,storage_path,display_path,clear_path,original_filename,media_type,mime_type,size_bytes,scope,owner_user_id,processing_status) VALUES (?,?,?,?,?,'IMAGE','image/png',?,'AVATAR',?,'READY')",
                assetId, PREVIOUS_RELATIVE_PATH, PREVIOUS_RELATIVE_PATH, PREVIOUS_RELATIVE_PATH, "old.png", Files.size(previousFile), saved.getId());
        jdbcTemplate.update("INSERT INTO media_references(asset_id,owner_type,owner_id,field) VALUES (?,'AVATAR',?,'avatar_url')", assetId, saved.getId());
        return saved;
    }

    private static MockMultipartFile validPng() throws IOException {
        return new MockMultipartFile("file", "replacement.png", "image/png", pngBytes());
    }

    private static byte[] pngBytes() throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", bytes)) {
            throw new IOException("PNG writer is unavailable");
        }
        return bytes.toByteArray();
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
