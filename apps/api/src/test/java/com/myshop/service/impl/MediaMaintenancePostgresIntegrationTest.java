package com.myshop.service.impl;

import com.myshop.exception.BusinessException;
import com.myshop.service.ImageProcessor;
import com.myshop.service.ImageUploadService;
import com.myshop.service.MediaFileGuard;
import com.myshop.service.MediaMaintenanceService;
import com.myshop.service.PublicImageResolver;
import com.myshop.service.UploadStorage;
import net.coobird.thumbnailator.Thumbnails;
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
    @Autowired PublicImageResolver imageResolver;
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

    @Test void deletionClearsProductCoverAndPreservesOtherReferencesWhileBlockingStaleWrites() throws Exception {
        String filename = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa.png";
        Files.write(UPLOADS.resolve(filename), pngWithAncillaryText());
        UUID product = product("/api/uploads/" + filename, "");
        String encoded = "%41" + filename.substring(1).replace(".png", "&period;png");
        jdbc.update("INSERT INTO site_configs(id,config_key,config_value) VALUES (?,?,?)", UUID.randomUUID(),
                "media_test_qr", "{\"qr_url\":\"/api/uploads/" + encoded + "\"}");
        var inspection = service.inspect(filename);
        assertTrue(inspection.references().stream().anyMatch(ref -> ref.table().equals("products")));
        assertTrue(inspection.references().stream().anyMatch(ref -> ref.table().equals("site_configs")));
        service.delete(filename);
        assertFalse(Files.exists(UPLOADS.resolve(filename)));
        assertNull(jdbc.queryForObject("SELECT cover_url FROM products WHERE id=?", String.class, product));
        assertTrue(jdbc.queryForObject("SELECT config_value FROM site_configs WHERE config_key='media_test_qr'", String.class).contains(encoded));
        jdbc.update("UPDATE site_configs SET config_group='other' WHERE config_key='media_test_qr'");
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
            service.delete(filename);
            assertFalse(Files.exists(UPLOADS.resolve(filename)));
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

    @Test void largeLegacyJpegCanBeDownscaledAndItsCoverReplaced() throws Exception {
        String filename = UUID.randomUUID() + ".jpg";
        BufferedImage image = new BufferedImage(4134, 1280, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            int red = x * 255 / image.getWidth();
            int green = y * 255 / image.getHeight();
            int blue = (red + green) / 2;
            image.setRGB(x, y, (red << 16) | (green << 8) | blue);
        }
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        Thumbnails.of(image).scale(1).outputFormat("jpg").outputQuality(0.99).toOutputStream(encoded);
        byte[] original = encoded.toByteArray();
        Files.write(UPLOADS.resolve(filename), original);
        UUID productId = product("/api/uploads/" + filename, "details stay unchanged");

        assertTrue(service.inspect(filename).optimizable());
        var result = service.optimize(filename);

        assertEquals(1, result.replacedCovers());
        assertTrue(result.optimizedSize() < result.originalSize());
        assertArrayEquals(original, Files.readAllBytes(UPLOADS.resolve(filename)));
        assertEquals(result.url(), jdbc.queryForObject("SELECT cover_url FROM products WHERE id=?", String.class, productId));
        BufferedImage optimized = ImageIO.read(UPLOADS.resolve(result.url().substring(result.url().lastIndexOf('/') + 1)).toFile());
        assertEquals(2560, optimized.getWidth());
        assertTrue(Math.abs((double) image.getWidth() / image.getHeight() - (double) optimized.getWidth() / optimized.getHeight()) < 0.01);
        assertEquals("details stay unchanged", jdbc.queryForObject("SELECT detail_md FROM products WHERE id=?", String.class, productId));
    }

    @Test void mixedReferencesAndUnsafePathsAreRejected() throws Exception {
        String filename = fixturePng();
        product("/api/uploads/" + filename, "![image](/api/uploads/" + filename + ")");
        var optimized = service.optimize(filename);
        assertEquals(1, optimized.replacedCovers());
        assertTrue(optimized.retainedReferences().stream().anyMatch(ref -> ref.table().equals("products")));
        assertThrows(BusinessException.class, () -> service.delete("../" + filename));
        Path outside = Files.createTempFile("myshop-media-outside-", ".png");
        try {
            String link = UUID.randomUUID() + ".png";
            Files.createSymbolicLink(UPLOADS.resolve(link), outside);
            assertThrows(BusinessException.class, () -> service.delete(link));
            assertTrue(Files.exists(outside));
        } finally { Files.deleteIfExists(outside); }
    }

    @Test void immutableOrderSnapshotsSurviveOptimizationDeletionAndUnrelatedUpdates() throws Exception {
        String filename = fixturePng();
        UUID productId = product("/api/uploads/" + filename, "");
        UUID orderItem = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO order_items(id,order_id,product_id,product_no,product_title,product_cover_url,quantity,
                  unit_price,cost_price_snapshot,retail_price_snapshot,pricing_source,subtotal)
                VALUES (?,?,?,1,'snapshot',?,1,1,0.5,1,'RETAIL',1)
                """, orderItem, UUID.randomUUID(), UUID.randomUUID(), "/api/uploads/" + filename);
        assertTrue(service.inspect(filename).references().stream().anyMatch(ref -> ref.table().equals("order_items")));
        var result = service.optimize(filename);
        assertEquals(1, result.replacedCovers());
        assertTrue(result.retainedReferences().stream().anyMatch(ref -> ref.table().equals("order_items")));
        service.delete(filename);
        assertEquals(result.url(), jdbc.queryForObject("SELECT cover_url FROM products WHERE id=?", String.class, productId));
        assertEquals("/api/uploads/" + filename, jdbc.queryForObject("SELECT product_cover_url FROM order_items WHERE id=?", String.class, orderItem));
        assertNull(imageResolver.visible("/api/uploads/" + filename));
        jdbc.update("UPDATE order_items SET customer_delivery_content='delivered' WHERE id=?", orderItem);
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE order_items SET product_cover_url='/other' WHERE id=?", orderItem));
    }

    @Test void deletingSharedProductAndOrderImageClearsCoverAndPreservesSnapshot() throws Exception {
        String filename = fixturePng(), url = "/api/uploads/" + filename;
        UUID productId = product(url, "Details remain");
        UUID itemId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO order_items(id,order_id,product_id,product_no,product_title,product_cover_url,quantity,
                  unit_price,cost_price_snapshot,retail_price_snapshot,pricing_source,subtotal)
                VALUES (?,?,?,1,'snapshot',?,1,1,0.5,1,'RETAIL',1)
                """, itemId, UUID.randomUUID(), productId, url);
        service.delete(filename);
        assertNull(jdbc.queryForObject("SELECT cover_url FROM products WHERE id=?", String.class, productId));
        assertEquals(url, jdbc.queryForObject("SELECT product_cover_url FROM order_items WHERE id=?", String.class, itemId));
        assertNull(imageResolver.visible(url));
        assertNull(imageResolver.visible(url + "?v=1"));
        assertEquals("https://elsewhere.example" + url, imageResolver.visible("https://elsewhere.example" + url));
        jdbc.update("UPDATE order_items SET customer_delivery_content='safe update' WHERE id=?", itemId);
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE products SET cover_url=? WHERE id=?", url, productId));
    }

    @Test void deletingRecognizesLocalUrlVariantsWithoutChangingExternalImages() throws Exception {
        String filename = fixturePng();
        UUID local = product("/api/uploads/" + filename + "?v=1", "");
        String externalUrl = "https://external.example/" + filename;
        UUID external = product(externalUrl, "");
        service.delete(filename);
        assertNull(jdbc.queryForObject("SELECT cover_url FROM products WHERE id=?", String.class, local));
        assertEquals(externalUrl, jdbc.queryForObject("SELECT cover_url FROM products WHERE id=?", String.class, external));
    }

    @Test void siteAndSubsiteFaviconsAreClearedWhenTheImageIsDeleted() throws Exception {
        String filename = fixturePng(), url = "/api/uploads/" + filename;
        UUID owner = UUID.randomUUID(), subsite = UUID.randomUUID();
        String suffix = owner.toString().substring(0, 8);
        jdbc.update("INSERT INTO site_configs(id,config_key,config_value) VALUES (?, 'favicon_url', ?) ON CONFLICT(config_key) DO UPDATE SET config_value=EXCLUDED.config_value", UUID.randomUUID(), url);
        jdbc.update("INSERT INTO users(id,username,password_hash,role) VALUES (?,?,?,'ADMIN')", owner, "u" + suffix, "test");
        jdbc.update("INSERT INTO subsites(id,owner_id,prefix,name,favicon_url,announcement) VALUES (?,?,?,?,?,?)", subsite, owner, "s" + suffix, "media fixture", url, "unchanged");
        try {
            service.delete(filename);
            assertEquals("", jdbc.queryForObject("SELECT config_value FROM site_configs WHERE config_key='favicon_url'", String.class));
            assertNull(jdbc.queryForObject("SELECT favicon_url FROM subsites WHERE id=?", String.class, subsite));
            assertEquals("unchanged", jdbc.queryForObject("SELECT announcement FROM subsites WHERE id=?", String.class, subsite));
        } finally {
            jdbc.update("DELETE FROM subsites WHERE id=?", subsite);
            jdbc.update("DELETE FROM users WHERE id=?", owner);
        }
    }

    @Test void markdownEscapesAndNestedJsonAreNotMistakenForUnused() throws Exception {
        String filename = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa.png";
        Files.write(UPLOADS.resolve(filename), pngWithAncillaryText());
        product(null, "![image](/api/uploads/" + filename.replace(".png", "\\.png") + ")");
        jdbc.update("INSERT INTO site_configs(id,config_key,config_value) VALUES (?,?,?)", UUID.randomUUID(),
                "media_test_nested", "{\"url\":\"/api/uploads/\\u0061" + filename.substring(1) + "\"}");
        assertTrue(service.inspect(filename).references().stream().anyMatch(ref -> ref.table().equals("site_configs")));
        service.delete(filename);
        assertFalse(Files.exists(UPLOADS.resolve(filename)));
        assertTrue(jdbc.queryForObject("SELECT detail_md FROM products LIMIT 1", String.class).contains(filename.replace(".png", "\\.png")));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE site_configs SET config_value=? WHERE config_key='media_test_nested'",
                "/api/uploads/" + filename));
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

    @Test void failedFileDeletionIsGuardedAndRetryable() throws Exception {
        String filename = fixturePng();
        UploadStorage failing = spy(storage);
        doReturn(false).when(failing).deleteManagedFile(filename);
        var failingService = new MediaMaintenanceService(failing, guard, new ImageProcessor(), "/api/uploads");
        assertThrows(BusinessException.class, () -> failingService.delete(filename));
        assertTrue(Files.exists(UPLOADS.resolve(filename)));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM media_deleted_files WHERE filename=?", Integer.class, filename));
        assertThrows(DataAccessException.class, () -> product("/api/uploads/" + filename, ""));
        service.delete(filename);
        assertFalse(Files.exists(UPLOADS.resolve(filename)));
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
        service.delete(filename);
        assertFalse(Files.exists(UPLOADS.resolve(filename)));
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
