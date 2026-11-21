package com.myshop.service.impl;

import com.myshop.exception.BusinessException;
import com.myshop.service.ImageProcessor;
import com.myshop.service.ImageUploadService;
import com.myshop.service.MediaFileGuard;
import com.myshop.service.MediaMaintenanceService;
import com.myshop.service.UploadStorage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.security.web.FilterChainProxy;

import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

@EnabledIf(value = "postgresAvailable", disabledReason = "Disposable PostgreSQL required")
@SpringBootTest
class MediaMaintenancePostgresIntegrationTest {
    static final IntegrationPostgres POSTGRES = new IntegrationPostgres();
    static final Path UPLOADS = tempDirectory();
    static boolean postgresAvailable() { return POSTGRES.available(); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("upload.path", UPLOADS::toString);
        registry.add("supplier.sync-initial-delay-ms", () -> "3600000");
    }
    @AfterAll static void cleanup() throws Exception {
        POSTGRES.close();
        if (Files.exists(UPLOADS)) try (var files = Files.walk(UPLOADS)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
    @Autowired MediaMaintenanceService service;
    @Autowired MediaFileGuard guard;
    @Autowired ImageUploadService upload;
    @Autowired UploadStorage storage;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired WebApplicationContext webContext;
    @Autowired FilterChainProxy security;
    UUID category;

    @BeforeEach void setup() throws Exception {
        jdbc.update("DELETE FROM media_deleted_files");
        jdbc.update("DELETE FROM order_items");
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM site_configs WHERE config_key LIKE 'media_test_%'");
        category = UUID.randomUUID();
        jdbc.update("INSERT INTO product_categories(id,name) VALUES (?,?)", category, "media fixture " + category);
        try (var files = Files.list(UPLOADS)) {
            for (Path path : files.toList()) {
                if (Files.isDirectory(path)) try (var children = Files.walk(path)) {
                    for (Path child : children.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(child);
                } else Files.deleteIfExists(path);
            }
        }
    }

    @Test void referencesAndEncodedUrlsBlockDeletionAndStaleWrites() throws Exception {
        String filename = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa.png";
        Files.write(UPLOADS.resolve(filename), pngWithAncillaryText());
        UUID product = product("/api/uploads/" + filename, "");
        String encoded = "%41" + filename.substring(1).replace(".png", "&period;png");
        jdbc.update("INSERT INTO site_configs(id,config_key,config_value) VALUES (?,?,?)", UUID.randomUUID(),
                "media_test_qr", "{\"qr_url\":\"/api/uploads/" + encoded + "\"}");
        var inspection = service.inspect(filename);
        assertTrue(inspection.references().stream().anyMatch(ref -> ref.table().equals("products")));
        assertTrue(inspection.references().stream().anyMatch(ref -> ref.table().equals("site_configs")));
        assertThrows(BusinessException.class, () -> service.delete(filename));
        assertTrue(Files.exists(UPLOADS.resolve(filename)));
        jdbc.update("DELETE FROM products WHERE id=?", product);
        jdbc.update("DELETE FROM site_configs WHERE config_key='media_test_qr'");
        service.delete(filename);
        assertFalse(Files.exists(UPLOADS.resolve(filename)));
        assertThrows(DataAccessException.class, () -> product("/api/uploads/" + filename, ""));
        assertThrows(DataAccessException.class, () -> jdbc.update("INSERT INTO site_configs(id,config_key,config_value) VALUES (?,?,?)",
                UUID.randomUUID(), "media_test_stale", "/api/uploads/" + encoded));
    }

    @Test void escapedCssIsTreatedAsAmbiguous() throws Exception {
        String filename = fixturePng();
        jdbc.update("INSERT INTO site_configs(id,config_key,config_value) VALUES (?, 'custom_css', ?) ON CONFLICT (config_key) DO UPDATE SET config_value=EXCLUDED.config_value",
                UUID.randomUUID(), "body { background: url(/api/uploads/\\31 file.png) }");
        try {
            assertTrue(service.inspect(filename).references().stream().anyMatch(ref -> ref.table().equals("site_configs")));
            assertThrows(BusinessException.class, () -> service.delete(filename));
        } finally { jdbc.update("DELETE FROM site_configs WHERE config_key='custom_css'"); }
    }

    @Test void deleteRequiresAnAdministrator() throws Exception {
        String filename = fixturePng();
        var mvc = MockMvcBuilders.webAppContextSetup(webContext).addFilters(security).build();
        mvc.perform(delete("/api/admin/media").contextPath("/api").param("filename", filename))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/admin/media").contextPath("/api").param("filename", filename).with(user("customer").roles("USER")))
                .andExpect(status().isForbidden());
        assertTrue(Files.exists(UPLOADS.resolve(filename)));
    }

    @Test void sharedCoversAreReplacedLosslesslyAndOriginalRemains() throws Exception {
        String filename = fixturePng();
        byte[] original = Files.readAllBytes(UPLOADS.resolve(filename));
        UUID first = product("/api/uploads/" + filename, "first");
        UUID second = product("/api/uploads/" + filename, "second");
        var result = service.optimize(filename);
        assertEquals(2, result.replacedCovers());
        assertTrue(result.optimizedSize() < result.originalSize());
        assertArrayEquals(original, Files.readAllBytes(UPLOADS.resolve(filename)));
        assertTrue(Files.exists(UPLOADS.resolve(result.url().substring(result.url().lastIndexOf('/') + 1))));
        assertEquals(result.url(), jdbc.queryForObject("SELECT cover_url FROM products WHERE id=?", String.class, first));
        assertEquals(result.url(), jdbc.queryForObject("SELECT cover_url FROM products WHERE id=?", String.class, second));
        service.delete(filename);
    }

    @Test void mixedReferencesAndUnsafePathsAreRejected() throws Exception {
        String filename = fixturePng();
        product("/api/uploads/" + filename, "![image](/api/uploads/" + filename + ")");
        assertThrows(BusinessException.class, () -> service.optimize(filename));
        assertThrows(BusinessException.class, () -> service.delete("../" + filename));
        Path outside = Files.createTempFile("myshop-media-outside-", ".png");
        try {
            String link = UUID.randomUUID() + ".png";
            Files.createSymbolicLink(UPLOADS.resolve(link), outside);
            assertThrows(BusinessException.class, () -> service.delete(link));
            assertTrue(Files.exists(outside));
        } finally { Files.deleteIfExists(outside); }
    }

    @Test void immutableOrderSnapshotsBlockBothDeletionAndReplacement() throws Exception {
        String filename = fixturePng();
        jdbc.update("""
                INSERT INTO order_items(id,order_id,product_id,product_no,product_title,product_cover_url,quantity,
                  unit_price,cost_price_snapshot,retail_price_snapshot,pricing_source,subtotal)
                VALUES (?,?,?,1,'snapshot',?,1,1,0.5,1,'RETAIL',1)
                """, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "/api/uploads/" + filename);
        assertTrue(service.inspect(filename).references().stream().anyMatch(ref -> ref.table().equals("order_items")));
        assertThrows(BusinessException.class, () -> service.delete(filename));
        assertThrows(BusinessException.class, () -> service.optimize(filename));
    }

    @Test void markdownEscapesAndNestedJsonAreNotMistakenForUnused() throws Exception {
        String filename = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa.png";
        Files.write(UPLOADS.resolve(filename), pngWithAncillaryText());
        product(null, "![image](/api/uploads/" + filename.replace(".png", "\\.png") + ")");
        assertThrows(BusinessException.class, () -> service.delete(filename));
        jdbc.update("DELETE FROM products");
        jdbc.update("INSERT INTO site_configs(id,config_key,config_value) VALUES (?,?,?)", UUID.randomUUID(),
                "media_test_nested", "{\"url\":\"/api/uploads/\\u0061" + filename.substring(1) + "\"}");
        assertTrue(service.inspect(filename).references().stream().anyMatch(ref -> ref.table().equals("site_configs")));
        assertThrows(BusinessException.class, () -> service.delete(filename));
    }

    @Test void optimizationSkipsAProductLockedByAnotherEditor() throws Exception {
        String filename = fixturePng();
        UUID id = product("/api/uploads/" + filename, "");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var lock = connection.prepareStatement("SELECT id FROM products WHERE id=? FOR UPDATE")) {
                lock.setObject(1, id); lock.executeQuery();
            }
            assertThrows(BusinessException.class, () -> service.optimize(filename));
            assertEquals("/api/uploads/" + filename, jdbc.queryForObject("SELECT cover_url FROM products WHERE id=?", String.class, id));
            connection.rollback();
        }
        assertTrue(Files.exists(UPLOADS.resolve(filename)));
    }

    @Test void failedFileDeletionLeavesTheOriginalUsable() throws Exception {
        String filename = fixturePng();
        UploadStorage failing = spy(storage);
        doReturn(false).when(failing).deleteManagedFile(filename);
        var failingService = new MediaMaintenanceService(failing, guard, new ImageProcessor(), "/api/uploads");
        assertThrows(BusinessException.class, () -> failingService.delete(filename));
        assertTrue(Files.exists(UPLOADS.resolve(filename)));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM media_deleted_files WHERE filename=?", Integer.class, filename));
        product("/api/uploads/" + filename, "");
    }

    @Test void concurrentReferenceCannotSlipThroughDeletion() throws Exception {
        String filename = fixturePng();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("INSERT INTO site_configs(id,config_key,config_value) VALUES (?,?,?)")) {
                statement.setObject(1, UUID.randomUUID()); statement.setString(2, "media_test_pending");
                statement.setString(3, "/api/uploads/" + filename); statement.executeUpdate();
            }
            var attempt = CompletableFuture.runAsync(() -> assertThrows(BusinessException.class, () -> service.delete(filename)));
            attempt.get(8, TimeUnit.SECONDS);
            assertTrue(Files.exists(UPLOADS.resolve(filename)));
            connection.commit();
        }
        assertThrows(BusinessException.class, () -> service.delete(filename));
    }

    @Test void reuploadOfIdenticalContentClearsTheTombstone() throws Exception {
        byte[] bytes = pngWithAncillaryText();
        var input = new MockMultipartFile("file", "fixture.png", "image/png", bytes);
        String url = upload.uploadAdminImage(input).url();
        String filename = url.substring(url.lastIndexOf('/') + 1);
        Path metadata = UPLOADS.resolve(".media-info").resolve(filename.substring(0, 64) + ".txt");
        assertTrue(Files.exists(metadata));
        service.delete(filename);
        assertFalse(Files.exists(metadata));
        assertEquals(url, upload.uploadAdminImage(input).url());
        assertTrue(Files.exists(metadata));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM media_deleted_files WHERE filename=?", Integer.class, filename));
        product(url, "");
    }

    private UUID product(String cover, String detail) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO products(id,title,detail_md,cover_url,retail_price,cost_price,category_id,stock_mode,delivery_type) VALUES (?,?,?,?,1,0.5,?,'UNLIMITED','MANUAL')",
                id, "fixture", detail, cover, category);
        return id;
    }
    private String fixturePng() throws Exception {
        String filename = UUID.randomUUID() + ".png";
        Files.write(UPLOADS.resolve(filename), pngWithAncillaryText());
        return filename;
    }
    private static byte[] pngWithAncillaryText() throws Exception {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++)
            image.setRGB(x, y, (x + y) % 3 == 0 ? 0 : 0xff000000 | (x * 8 << 16) | (y * 8 << 8));
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        ImageIO.write(image, "png", raw);
        byte[] png = raw.toByteArray();
        byte[] text = ("note\0" + "x".repeat(20000)).getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(png, 0, png.length - 12);
        var data = new java.io.DataOutputStream(output);
        data.writeInt(text.length); byte[] type = "tEXt".getBytes(StandardCharsets.US_ASCII);
        data.write(type); data.write(text);
        CRC32 crc = new CRC32(); crc.update(type); crc.update(text); data.writeInt((int) crc.getValue());
        output.write(png, png.length - 12, 12);
        return output.toByteArray();
    }
    private static Path tempDirectory() {
        try { return Files.createTempDirectory("myshop-media-test-"); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
}
