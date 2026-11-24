package com.myshop.service.impl;

import com.myshop.constant.StockMode;
import com.myshop.exception.BusinessException;
import com.myshop.model.request.AdminProductUpsertRequest;
import com.myshop.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** The old global trigger was removed: exercise the replacement transaction boundaries. */
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
    @Autowired MediaMaintenanceService maintenance;
    @Autowired MediaAssetRegistry assets;
    @Autowired ManagedMediaReferences paths;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired UploadStorage storage;
    @Autowired PublicImageResolver resolver;
    @Autowired AdminProductServiceImpl products;
    @Autowired AnnouncementService announcements;
    @Autowired SiteConfigService siteConfigs;
    @Autowired SubsiteManagementService subsites;
    @Autowired MediaLibraryService library;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired WebApplicationContext webContext;
    @Autowired FilterChainProxy security;
    UUID category;

    @BeforeEach void setup() {
        category = UUID.randomUUID();
        jdbc.update("INSERT INTO product_categories(id,name) VALUES (?,?)", category, "media fixture " + category);
    }
    @AfterEach void clearFixture() {
        jdbc.update("DELETE FROM media_references WHERE owner_type='PRODUCT' AND owner_id IN (SELECT id FROM products WHERE category_id=?)", category);
        jdbc.update("DELETE FROM products WHERE category_id=?", category);
        jdbc.update("DELETE FROM product_categories WHERE id=?", category);
    }

    @Test void upgradingLegacySharedImageRewritesOnlyCurrentOwnerFieldsAndKeepsTheOriginalUrlReadable() throws Exception {
        UUID asset = fixture("legacy-shared.png");
        String oldPath = path(asset), oldUrl = url(asset);
        String markdown = "说明\n![正文图](" + oldUrl + "?live=1)\n[查看原图](" + oldUrl + ")\n";
        UUID product = products.createProduct(request(oldUrl, markdown)).id();
        UUID announcement = announcements.create(null, new AnnouncementService.Edit("媒体优化", markdown));
        UUID siteConfig = UUID.randomUUID();
        jdbc.update("INSERT INTO site_configs(id,config_key,config_value) VALUES (?,?,?)", siteConfig, "media-test-" + siteConfig, oldUrl);
        jdbc.update("INSERT INTO media_references VALUES (?,'SITE_CONFIG',?,'favicon_url')", asset, siteConfig);
        var displayFile = Files.createTempFile(UPLOADS, ".legacy-display-", ".webp");
        var thumbFile = Files.createTempFile(UPLOADS, ".legacy-thumb-", ".webp");
        var clearFile = Files.createTempFile(UPLOADS, ".legacy-clear-", ".webp");
        Files.write(displayFile, new byte[]{1,2,3,4});
        Files.write(thumbFile, new byte[]{1,2});
        Files.write(clearFile, new byte[]{1,2,3,4,5,6});
        var image = mock(VipsImageProcessor.Processed.class);
        when(image.display()).thenReturn(new VipsImageProcessor.Variant(displayFile, "image/webp", 100, 80, 4));
        when(image.thumbnail()).thenReturn(new VipsImageProcessor.Variant(thumbFile, "image/webp", 100, 80, 2));
        when(image.clear()).thenReturn(new VipsImageProcessor.Variant(clearFile, "image/webp", 100, 80, 6));
        when(image.originalSize()).thenReturn(8L);
        when(image.reason()).thenReturn("compressed");
        try {
            var upgraded = assets.upgradeLegacyImage(asset, "c".repeat(64), "legacy-shared.png", null, image);
            String displayUrl = "/api/uploads/" + upgraded.displayPath();
            String expected = "说明\n![正文图](" + displayUrl + ")\n[查看原图](" + displayUrl + ")\n";
            assertEquals(oldPath, jdbc.queryForObject("SELECT storage_path FROM media_assets WHERE id=?", String.class, asset));
            assertEquals(upgraded.displayPath(), jdbc.queryForObject("SELECT display_path FROM media_assets WHERE id=?", String.class, asset));
            assertEquals(displayUrl, jdbc.queryForObject("SELECT cover_url FROM products WHERE id=?", String.class, product));
            assertEquals(expected, jdbc.queryForObject("SELECT detail_md FROM products WHERE id=?", String.class, product));
            assertEquals(expected, jdbc.queryForObject("SELECT body FROM site_announcements WHERE id=?", String.class, announcement));
            assertEquals(displayUrl, jdbc.queryForObject("SELECT config_value FROM site_configs WHERE id=?", String.class, siteConfig));
            assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM media_references WHERE asset_id=?", Integer.class, asset));
            assertNotNull(resolver.visible(oldUrl));
            assertNotNull(resolver.visible(displayUrl));
            assertTrue(Files.exists(UPLOADS.resolve(oldPath)), "legacy URL bytes remain available");
            assertThrows(BusinessException.class, () -> products.updateProduct(product, request(oldUrl, expected)),
                    "stale forms may not reattach the raw legacy URL");
        } finally {
            jdbc.update("DELETE FROM media_references WHERE owner_type='SITE_CONFIG' AND owner_id=?", siteConfig);
            jdbc.update("DELETE FROM site_configs WHERE id=?", siteConfig);
            jdbc.update("DELETE FROM media_references WHERE owner_type='ANNOUNCEMENT' AND owner_id=?", announcement);
            jdbc.update("DELETE FROM site_announcements WHERE id=?", announcement);
        }
    }

    @Test void sharedCurrentReferencesAreRemovedWithoutDamagingMarkdownOrSnapshots() throws Exception {
        UUID asset = fixture("shared.png"); String url = url(asset);
        String markdown = "前文\n下一行\n\n- ![图](" + url + ")\n- [帮助](https://example.com/help)";
        String expected = "前文\n下一行\n\n- （资源已删除）\n- [帮助](https://example.com/help)";
        UUID first = products.createProduct(request(url, markdown)).id();
        UUID second = products.createProduct(request(url + "?v=1", "![图](" + url + ")")).id();
        UUID announcement = announcements.create(null, new AnnouncementService.Edit("通知", markdown));
        UUID item = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO order_items(id,order_id,product_id,product_no,product_title,product_cover_url,quantity,
                  unit_price,cost_price_snapshot,retail_price_snapshot,pricing_source,subtotal)
                VALUES (?,?,?,1,'snapshot',?,1,1,0.5,1,'RETAIL',1)
                """, item, UUID.randomUUID(), first, url);
        try {
            assertEquals(5, maintenance.inspect(asset.toString()).references().size());
            assertEquals(1, library.list(1, 24, "", "desc", "time", "", "site", false, false, asset).total());
            assertTrue(maintenance.inspect(asset.toString()).references().stream().noneMatch(r -> r.table().equals("order_items")));
            maintenance.delete(asset.toString());
            assertNull(jdbc.queryForObject("SELECT cover_url FROM products WHERE id=?", String.class, first));
            assertNull(jdbc.queryForObject("SELECT cover_url FROM products WHERE id=?", String.class, second));
            assertEquals(expected, jdbc.queryForObject("SELECT detail_md FROM products WHERE id=?", String.class, first));
            assertEquals(expected, jdbc.queryForObject("SELECT body FROM site_announcements WHERE id=?", String.class, announcement));
            assertEquals(url, jdbc.queryForObject("SELECT product_cover_url FROM order_items WHERE id=?", String.class, item));
            assertNull(resolver.visible(url));
            products.updateProduct(first, request(null, expected + "\n继续编辑"));
            announcements.edit(null, announcement, new AnnouncementService.Edit("通知", expected + "\n继续编辑"));
            assertThrows(BusinessException.class, () -> products.updateProduct(first, request(url, expected)));
            assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE order_items SET product_cover_url='/different' WHERE id=?", item));
        } finally {
            jdbc.update("DELETE FROM order_items WHERE id=?", item);
            jdbc.update("DELETE FROM media_references WHERE owner_type='ANNOUNCEMENT' AND owner_id=?", announcement);
            jdbc.update("DELETE FROM site_announcements WHERE id=?", announcement);
        }
    }

    @Test void unrelatedOrderWriteDoesNotWaitForAssetLock() throws Exception {
        UUID asset = fixture("order.png"), order = UUID.randomUUID();
        try (Connection lock = dataSource.getConnection()) {
            lock.setAutoCommit(false);
            try (var q = lock.prepareStatement("SELECT id FROM media_assets WHERE id=? FOR UPDATE")) { q.setObject(1, asset); q.executeQuery(); }
            var insert = CompletableFuture.runAsync(() -> jdbc.update("INSERT INTO orders(id,status,order_type) VALUES (?,'PENDING','ADMIN_PICKUP')", order));
            insert.get(2, TimeUnit.SECONDS);
            lock.rollback();
        } finally { jdbc.update("DELETE FROM orders WHERE id=?", order); }
    }

    @Test void deletingLinkedMediaKeepsUnrelatedNestedImageWithoutLockingIt() throws Exception {
        UUID removed = fixture("linked.png"), kept = fixture("kept.png");
        String removedUrl = url(removed), keptUrl = url(kept);
        String description = "前文 [描述 ![保留](" + keptUrl + ") 尾部](" + removedUrl + ") 后文";
        UUID first = products.createProduct(request(null, description)).id();
        // A nested image in alt text is not a separately displayed asset.
        UUID second = products.createProduct(request(null, "![![仅为替代文字](" + keptUrl + ")](" + removedUrl + ")")).id();
        assertEquals(1, maintenance.inspect(kept.toString()).references().size());
        // Simulate a stale reference recorded by an older parser/backfill.
        jdbc.update("INSERT INTO media_references VALUES (?,'PRODUCT',?,'detail_md')", kept, second);
        try (Connection lock = dataSource.getConnection()) {
            lock.setAutoCommit(false);
            try (var query = lock.prepareStatement("SELECT id FROM media_assets WHERE id=? FOR UPDATE")) {
                query.setObject(1, kept); query.executeQuery();
            }
            var deleting = CompletableFuture.runAsync(() -> maintenance.delete(removed.toString()));
            try { deleting.get(3, TimeUnit.SECONDS); }
            finally { lock.rollback(); }
        }
        String expected = "前文 描述 ![保留](" + keptUrl + ") 尾部（资源已删除） 后文";
        assertEquals(expected, jdbc.queryForObject("SELECT detail_md FROM products WHERE id=?", String.class, first));
        assertEquals("（资源已删除）", jdbc.queryForObject("SELECT detail_md FROM products WHERE id=?", String.class, second));
        assertNotNull(resolver.visible(keptUrl));
        assertEquals(1, maintenance.inspect(kept.toString()).references().size());
        assertEquals(first, maintenance.inspect(kept.toString()).references().getFirst().ownerId());
        products.updateProduct(first, request(null, expected + "\n继续编辑"));
    }

    @Test void mediaDeletionLocksProductRowsInTheExistingJavaUuidOrder() throws Exception {
        UUID asset = fixture("order-of-locks.png"); String url = url(asset);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        UUID first = UUID.fromString("80000000-0000-4000-8000-" + suffix);
        UUID second = UUID.fromString("00000000-0000-4000-8000-" + suffix);
        assertTrue(first.compareTo(second) < 0);
        for (UUID id : List.of(first, second)) {
            jdbc.update("INSERT INTO products(id,title,category_id,cover_url,retail_price,cost_price,stock_mode,delivery_type) VALUES (?,'fixture',?,?,1,0.5,'UNLIMITED','MANUAL')", id, category, url);
            jdbc.update("INSERT INTO media_references VALUES (?,'PRODUCT',?,'cover_url')", asset, id);
        }
        try (Connection transaction = dataSource.getConnection()) {
            transaction.setAutoCommit(false);
            try (var settings = transaction.createStatement()) { settings.execute("SET LOCAL lock_timeout='1s'"); }
            try (var firstLock = transaction.prepareStatement("SELECT id FROM products WHERE id=? FOR UPDATE")) { firstLock.setObject(1, first); firstLock.executeQuery(); }
            var deleting = CompletableFuture.runAsync(() -> maintenance.delete(asset.toString()));
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                boolean waiting = false;
                while (System.nanoTime() < deadline && !waiting) {
                    waiting = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE 'SELECT cover_url FROM products%FOR UPDATE%')", Boolean.class);
                    if (!waiting) Thread.sleep(20);
                }
                assertTrue(waiting, "deletion should wait for the first product lock");
                try (var secondLock = transaction.prepareStatement("SELECT id FROM products WHERE id=? FOR UPDATE")) { secondLock.setObject(1, second); secondLock.executeQuery(); }
            } finally { transaction.rollback(); }
            deleting.get(5, TimeUnit.SECONDS);
        }
    }

    @Test void announcementContainingOnlyDeletedMediaKeepsNonemptyPlaceholder() throws Exception {
        UUID asset = fixture("only.png"); String url = url(asset);
        UUID announcement = announcements.create(null, new AnnouncementService.Edit("通知", "![图](" + url + ")"));
        try {
            maintenance.delete(asset.toString());
            assertEquals("（资源已删除）", jdbc.queryForObject("SELECT body FROM site_announcements WHERE id=?", String.class, announcement));
            announcements.edit(null, announcement, new AnnouncementService.Edit("通知", "（资源已删除）\n补充内容"));
        } finally { jdbc.update("DELETE FROM site_announcements WHERE id=?", announcement); }
    }

    @Test void announcementKeepsAnUnrelatedImageInsideADeletedMediaLink() throws Exception {
        UUID removed = fixture("announcement-link.png"), kept = fixture("announcement-picture.png");
        String body = "公告 [![保留](" + url(kept) + ")](" + url(removed) + ") 后文";
        UUID announcement = announcements.create(null, new AnnouncementService.Edit("通知", body));
        try {
            maintenance.delete(removed.toString());
            String expected = "公告 ![保留](" + url(kept) + ")（资源已删除） 后文";
            assertEquals(expected, jdbc.queryForObject("SELECT body FROM site_announcements WHERE id=?", String.class, announcement));
            assertEquals(announcement, maintenance.inspect(kept.toString()).references().getFirst().ownerId());
            announcements.edit(null, announcement, new AnnouncementService.Edit("通知", expected + "\n继续编辑"));
        } finally {
            jdbc.update("DELETE FROM media_references WHERE owner_type='ANNOUNCEMENT' AND owner_id=?", announcement);
            jdbc.update("DELETE FROM site_announcements WHERE id=?", announcement);
        }
    }

    @Test void saveAndDeleteSerializeOnAssetAndStaleFormIsRejected() throws Exception {
        UUID asset = fixture("concurrent.png"); String url = url(asset);
        UUID product = products.createProduct(request(null, "正文")).id();
        try (Connection lock = dataSource.getConnection()) {
            lock.setAutoCommit(false);
            try (var q = lock.prepareStatement("SELECT id FROM media_assets WHERE id=? FOR UPDATE")) { q.setObject(1, asset); q.executeQuery(); }
            var save = CompletableFuture.runAsync(() -> products.updateProduct(product, request(url, "正文")));
            assertThrows(java.util.concurrent.TimeoutException.class, () -> save.get(250, TimeUnit.MILLISECONDS));
            lock.rollback();
            save.get(5, TimeUnit.SECONDS);
        }
        maintenance.delete(asset.toString());
        assertNull(jdbc.queryForObject("SELECT cover_url FROM products WHERE id=?", String.class, product));
        assertThrows(BusinessException.class, () -> products.updateProduct(product, request(url, "正文")));
    }

    @Test void rollbackKeepsContentAndFilesWhenOwnerUpdateFails() throws Exception {
        UUID asset = fixture("rollback.png"); String url = url(asset);
        UUID product = products.createProduct(request(url, "![图](" + url + ")")).id();
        jdbc.execute("ALTER TABLE products ADD CONSTRAINT media_test_cover_required CHECK (cover_url IS NOT NULL)");
        try {
            assertThrows(DataAccessException.class, () -> maintenance.delete(asset.toString()));
            assertEquals(url, jdbc.queryForObject("SELECT cover_url FROM products WHERE id=?", String.class, product));
            assertEquals(2, maintenance.inspect(asset.toString()).references().size());
            assertNotNull(resolver.visible(url));
        } finally { jdbc.execute("ALTER TABLE products DROP CONSTRAINT media_test_cover_required"); }
    }

    @Test void failedFilesystemCleanupIsPrivateAndRetryable() throws Exception {
        UUID asset = fixture("failure.png"); String url = url(asset), path = path(asset);
        var failing = spy(storage); doReturn(false).when(failing).deleteManagedFile(path);
        var broken = new MediaMaintenanceService(failing, jdbc, paths, transactionManager);
        assertThrows(BusinessException.class, () -> broken.delete(asset.toString()));
        assertTrue(Files.exists(UPLOADS.resolve(path)));
        assertNull(resolver.visible(url));
        assertNotNull(library.list(1, 24, "", "desc", "time", "", "", false, false, asset).items().getFirst().cleanupError());
        var mvc = MockMvcBuilders.webAppContextSetup(webContext).addFilters(security).build();
        mvc.perform(get("/api/uploads/" + path).contextPath("/api")).andExpect(status().isNotFound());
        mvc.perform(head("/api/uploads/" + path).contextPath("/api")).andExpect(status().isNotFound());
        maintenance.delete(asset.toString());
        assertFalse(Files.exists(UPLOADS.resolve(path)));
    }

    @Test void publicReadAndDeleteEndpointAuthorizationRemainIntact() throws Exception {
        UUID asset = fixture("access.png"); String path = path(asset);
        var mvc = MockMvcBuilders.webAppContextSetup(webContext).addFilters(security).build();
        mvc.perform(get("/api/uploads/" + path).contextPath("/api")).andExpect(status().isOk());
        mvc.perform(head("/api/uploads/" + path).contextPath("/api")).andExpect(status().isOk())
                .andExpect(header().longValue("Content-Length", Files.size(UPLOADS.resolve(path))));
        mvc.perform(delete("/api/admin/media").contextPath("/api").param("filename", asset.toString())).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/admin/media").contextPath("/api").param("filename", asset.toString()).with(user("customer").roles("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/media/optimize").contextPath("/api").param("filename", path).with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
        assertThrows(BusinessException.class, () -> maintenance.delete("../" + path));
    }

    @Test void duplicateRegistrationReusesOnlyLiveAssetAndRollbackRemovesPublishedFiles() throws Exception {
        String hash = "a".repeat(64);
        Path source = Files.createTempFile(UPLOADS, ".test-image-", ".webp"); Files.writeString(source, "fixture");
        var variant = new VipsImageProcessor.Variant(source, "image/webp", 2, 2, 7);
        var image = mock(VipsImageProcessor.Processed.class);
        when(image.display()).thenReturn(variant); when(image.thumbnail()).thenReturn(variant); when(image.clear()).thenReturn(variant);
        when(image.originalSize()).thenReturn(7L);
        var first = assets.registerImage(hash, "first.webp", null, null, image);
        var reused = assets.registerImage(hash, "second.webp", null, null, image);
        assertTrue(reused.reused()); assertEquals(first.id(), reused.id());
        maintenance.delete(first.id().toString());
        var next = assets.registerImage(hash, "third.webp", null, null, image);
        assertNotEquals(first.id(), next.id()); assertNotEquals(first.displayPath(), next.displayPath());
        assertNull(resolver.visible("/api/uploads/" + first.displayPath()));
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        var rolledBack = tx.execute(status -> {
            try {
                var created = assets.registerImage("b".repeat(64), "rollback.webp", null, null, image);
                status.setRollbackOnly(); return created;
            } catch (Exception exception) { throw new RuntimeException(exception); }
        });
        assertNotNull(rolledBack);
        assertFalse(Files.exists(UPLOADS.resolve(rolledBack.displayPath())));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM media_assets WHERE id=?", Integer.class, rolledBack.id()));
        Files.deleteIfExists(source);
    }

    @Test void concurrentVideoCompletionReusesLiveAssetAndDeletionNeverRevivesOldUrl() throws Exception {
        UUID ownerA = UUID.randomUUID(), ownerB = UUID.randomUUID();
        UUID first = assets.createVideo("lesson-a.mp4", ownerA, 8), second = assets.createVideo("lesson-b.mp4", ownerB, 8);
        assets.bindVideoUpload(first, ownerA, "/tus/" + first, 8);
        assets.bindVideoUpload(second, ownerB, "/tus/" + second, 8);
        assets.markVideoProcessing(first); assets.markVideoProcessing(second);
        String firstPath = videoFile(first), secondPath = videoFile(second);
        String sourceHash = java.util.HexFormat.of().formatHex(UploadStorage.sha256(UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        String outputHash = "b".repeat(64);
        var one = CompletableFuture.supplyAsync(() -> assets.markVideoReady(first, firstPath, "video/mp4", 8, 100L, outputHash, sourceHash));
        var two = CompletableFuture.supplyAsync(() -> assets.markVideoReady(second, secondPath, "video/mp4", 8, 100L, outputHash, sourceHash));
        var a = one.get(5, TimeUnit.SECONDS); var b = two.get(5, TimeUnit.SECONDS);
        assertEquals(a.assetId(), b.assetId()); assertNotEquals(a.reused(), b.reused());
        UUID reusedSession = a.reused() ? first : second, reusedOwner = a.reused() ? ownerA : ownerB;
        var session = assets.findVideoIncludingDeleted(reusedSession, reusedOwner).orElseThrow();
        assertEquals("REUSED", session.status()); assertEquals(a.assetId(), session.reusedAssetId());
        assertTrue(assets.findReadyVideoForReuse(a.assetId()).isPresent());
        assertFalse(assets.findCancelledVideosNeedingCleanup().stream().noneMatch(s -> s.id().equals(reusedSession)));
        assets.markVideoCleanupComplete(reusedSession, reusedOwner);
        maintenance.delete(a.assetId().toString());
        assertTrue(assets.findReadyVideoForReuse(a.assetId()).isEmpty());
        UUID next = assets.createVideo("same-again.mp4", ownerA, 8);
        assets.bindVideoUpload(next, ownerA, "/tus/" + next, 8);
        assertTrue(assets.reuseVideoIfExists(next, sourceHash).isEmpty());
        assets.markVideoProcessing(next);
        var after = assets.markVideoReady(next, videoFile(next), "video/mp4", 8, 100L, outputHash, sourceHash);
        assertFalse(after.reused()); assertNotEquals(a.assetId(), after.assetId()); assertNotEquals(a.storagePath(), after.storagePath());
    }

    @Test void abandonedUnboundVideoSessionExpiresWithoutAffectingRecentOrBoundUploads() {
        UUID owner = UUID.randomUUID();
        UUID abandoned = assets.createVideo("abandoned.mp4", owner, 8);
        UUID recent = assets.createVideo("recent.mp4", owner, 8);
        UUID bound = assets.createVideo("bound.mp4", owner, 8);
        assets.bindVideoUpload(bound, owner, "/tus/" + bound, 8);
        jdbc.update("UPDATE media_assets SET created_at=CURRENT_TIMESTAMP - INTERVAL '8 days' WHERE id IN (?,?)", abandoned, bound);
        assertEquals(1, assets.expireUnboundVideoSessions());
        assertEquals("FAILED", assets.findVideo(abandoned, owner).orElseThrow().status());
        assertEquals("UPLOADING", assets.findVideo(recent, owner).orElseThrow().status());
        assertEquals("UPLOADING", assets.findVideo(bound, owner).orElseThrow().status());
    }

    @Test void videoReuseSkipsMissingOlderCandidatesAndFindsTheValidAsset() throws Exception {
        String hash = java.util.HexFormat.of().formatHex(UploadStorage.sha256(UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        UUID missing = UUID.fromString("00000000-0000-4000-8000-" + suffix);
        UUID valid = UUID.fromString("ffffffff-ffff-4fff-8fff-" + suffix);
        String missingPath = "videos/" + missing + ".mp4", validPath = videoFile(valid);
        jdbc.update("INSERT INTO media_assets(id,storage_path,display_path,original_filename,media_type,mime_type,size_bytes,source_hash,scope,processing_status) VALUES (?,?,?,'missing','VIDEO','video/mp4',8,?,'SHARED','READY')",
                missing, missingPath, missingPath, hash);
        jdbc.update("INSERT INTO media_assets(id,storage_path,display_path,original_filename,media_type,mime_type,size_bytes,source_hash,scope,processing_status) VALUES (?,?,?,'valid','VIDEO','video/mp4',8,?,'SHARED','READY')",
                valid, validPath, validPath, hash);
        UUID owner = UUID.randomUUID(), pending = assets.createVideo("repeat.mp4", owner, 8);
        assets.bindVideoUpload(pending, owner, "/tus/" + pending, 8);
        assertEquals(valid, assets.reuseVideoIfExists(pending, hash).orElseThrow().assetId());
        jdbc.update("DELETE FROM media_assets WHERE id IN (?, ?, ?)", pending, missing, valid);
    }

    @Test void deletingReadyVideoKeepsIndependentTusCleanupRetryUntilItSucceeds() throws Exception {
        UUID owner = UUID.randomUUID(), video = assets.createVideo("lesson.mp4", owner, 8);
        assets.bindVideoUpload(video, owner, "/tus/" + video, 8); assets.markVideoProcessing(video);
        String relative = videoFile(video);
        assets.markVideoReady(video, relative, "video/mp4", 8, 100L, "c".repeat(64), "d".repeat(64));
        assertNotNull(library.list(1, 24, "", "desc", "time", "", "", false, false, video).items().getFirst().uploadCleanupError());
        maintenance.delete(video.toString());
        assertFalse(Files.exists(UPLOADS.resolve(relative)));
        var pending = library.list(1, 24, "", "desc", "time", "", "", false, false, video).items().getFirst();
        assertEquals("deleted", pending.status()); assertNull(pending.cleanupError()); assertNotNull(pending.uploadCleanupError());
        assertTrue(assets.findReadyVideosNeedingUploadCleanup().stream().anyMatch(session -> session.id().equals(video)));
        assets.markReadyVideoCleanupFailed(video, owner, "上传临时文件不可删除");
        assertEquals("上传临时文件不可删除", library.list(1, 24, "", "desc", "time", "", "", false, false, video).items().getFirst().uploadCleanupError());
        assets.markReadyVideoCleanupComplete(video, owner);
        assertEquals(0, library.list(1, 24, "", "desc", "time", "", "", false, false, video).total());
    }

    @Test void aCommittedDeletionPendingPhysicalCleanupRemainsRetryableAfterRestart() throws Exception {
        UUID asset = fixture("pending.png"); String path = path(asset);
        jdbc.update("UPDATE media_assets SET deleted_at=CURRENT_TIMESTAMP,cleanup_error='资源文件待清理' WHERE id=?", asset);
        assertTrue(Files.exists(UPLOADS.resolve(path)));
        assertEquals("deleted", library.list(1, 24, "", "desc", "time", "", "", false, false, asset).items().getFirst().status());
        assertNull(resolver.visible("/api/uploads/" + path));
        maintenance.delete(asset.toString());
        assertFalse(Files.exists(UPLOADS.resolve(path)));
        assertEquals(0, library.list(1, 24, "", "desc", "time", "", "", false, false, asset).total());
    }

    @Test void concurrentVideoDeletionAndTusCleanupFailureDoNotOverwriteEitherState() throws Exception {
        UUID owner = UUID.randomUUID(), video = assets.createVideo("concurrent.mp4", owner, 8);
        assets.bindVideoUpload(video, owner, "/tus/" + video, 8); assets.markVideoProcessing(video);
        String relative = videoFile(video);
        assets.markVideoReady(video, relative, "video/mp4", 8, 100L, "e".repeat(64), "f".repeat(64));
        var start = new java.util.concurrent.CountDownLatch(1);
        var delete = CompletableFuture.runAsync(() -> { try { start.await(); maintenance.delete(video.toString()); } catch (InterruptedException e) { throw new RuntimeException(e); } });
        var fail = CompletableFuture.runAsync(() -> { try { start.await(); assets.markReadyVideoCleanupFailed(video, owner, "tus cleanup failed"); } catch (InterruptedException e) { throw new RuntimeException(e); } });
        start.countDown(); delete.get(5, TimeUnit.SECONDS); fail.get(5, TimeUnit.SECONDS);
        var item = library.list(1, 24, "", "desc", "time", "", "", false, false, video).items().getFirst();
        assertEquals("deleted", item.status()); assertNull(item.cleanupError()); assertEquals("tus cleanup failed", item.uploadCleanupError());
        assertFalse(Files.exists(UPLOADS.resolve(relative)));
        assets.markReadyVideoCleanupComplete(video, owner);
        assertEquals(0, library.list(1, 24, "", "desc", "time", "", "", false, false, video).total());
    }

    @Test void historicalAvatarIsRetainedWhileAnyAccountOrProductStillReferencesIt() throws Exception {
        UUID asset = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID(), product = UUID.randomUUID();
        String path = "avatars/avatar-" + asset + ".png", url = "/api/uploads/" + path;
        Files.createDirectories(UPLOADS.resolve("avatars")); Files.write(UPLOADS.resolve(path), new byte[]{1,2,3});
        jdbc.update("INSERT INTO media_assets(id,storage_path,display_path,clear_path,original_filename,media_type,mime_type,size_bytes,scope,owner_user_id) VALUES (?,?,?,?,?,'IMAGE','image/png',3,'AVATAR',?)",
                asset, path, path, path, "old avatar", first);
        jdbc.update("INSERT INTO media_references VALUES (?,'AVATAR',?,'avatar_url'),(?,'AVATAR',?,'avatar_url'),(?,'PRODUCT',?,'cover_url')",
                asset, first, asset, second, asset, product);
        jdbc.update("DELETE FROM media_references WHERE asset_id=? AND owner_type='AVATAR' AND owner_id=?", asset, first);
        maintenance.retireUnreferencedAvatar(url, first);
        assertTrue(Files.exists(UPLOADS.resolve(path)));
        jdbc.update("DELETE FROM media_references WHERE asset_id=? AND owner_type='AVATAR' AND owner_id=?", asset, second);
        maintenance.retireUnreferencedAvatar(url, second);
        assertTrue(Files.exists(UPLOADS.resolve(path)));
        jdbc.update("DELETE FROM media_references WHERE asset_id=? AND owner_type='PRODUCT'", asset);
        maintenance.retireUnreferencedAvatar(url, second);
        assertFalse(Files.exists(UPLOADS.resolve(path)));
        assertNull(resolver.visible(url));
    }

    @Test void retiringAvatarWaitsForConcurrentReferenceSave() throws Exception {
        UUID asset = UUID.randomUUID(), owner = UUID.randomUUID();
        String path = "avatars/avatar-" + asset + ".png", url = "/api/uploads/" + path;
        Files.createDirectories(UPLOADS.resolve("avatars")); Files.write(UPLOADS.resolve(path), new byte[]{1,2,3});
        jdbc.update("INSERT INTO media_assets(id,storage_path,display_path,clear_path,original_filename,media_type,mime_type,size_bytes,scope,owner_user_id) VALUES (?,?,?,?,?,'IMAGE','image/png',3,'AVATAR',?)",
                asset, path, path, path, "old avatar", owner);
        try (Connection transaction = dataSource.getConnection()) {
            transaction.setAutoCommit(false);
            try (var lock = transaction.prepareStatement("SELECT id FROM media_assets WHERE id=? FOR UPDATE")) { lock.setObject(1, asset); lock.executeQuery(); }
            try (var insert = transaction.prepareStatement("INSERT INTO media_references VALUES (?,'AVATAR',?,'avatar_url')")) {
                insert.setObject(1, asset); insert.setObject(2, owner); insert.executeUpdate();
            }
            var retire = CompletableFuture.runAsync(() -> maintenance.retireUnreferencedAvatar(url, owner));
            assertThrows(java.util.concurrent.TimeoutException.class, () -> retire.get(250, TimeUnit.MILLISECONDS));
            transaction.commit(); retire.get(5, TimeUnit.SECONDS);
        }
        assertTrue(Files.exists(UPLOADS.resolve(path)));
        assertNotNull(resolver.visible(url));
    }

    @Test void aProductMayRetainButCannotAddHistoricalAvatarReferences() throws Exception {
        UUID avatar = UUID.randomUUID(), owner = UUID.randomUUID(), product = UUID.randomUUID();
        String path = "avatars/avatar-" + avatar + ".png", url = "/api/uploads/" + path;
        Files.createDirectories(UPLOADS.resolve("avatars")); Files.write(UPLOADS.resolve(path), new byte[]{1,2,3});
        jdbc.update("INSERT INTO media_assets(id,storage_path,display_path,clear_path,original_filename,media_type,mime_type,size_bytes,scope,owner_user_id) VALUES (?,?,?,?,?,'IMAGE','image/png',3,'AVATAR',?)",
                avatar, path, path, path, "old avatar", owner);
        String detail = "旧正文\n![历史头像](" + url + ")";
        jdbc.update("INSERT INTO products(id,title,category_id,cover_url,detail_md,retail_price,cost_price,stock_mode,delivery_type) VALUES (?,'fixture',?,?,?,1,0.5,'UNLIMITED','MANUAL')", product, category, url, detail);
        jdbc.update("INSERT INTO media_references VALUES (?,'PRODUCT',?,'cover_url'),(?,'PRODUCT',?,'detail_md'),(?,'AVATAR',?,'avatar_url')",
                avatar, product, avatar, product, avatar, owner);
        products.updateProduct(product, request(url, detail + "\n继续编辑"));
        assertThrows(BusinessException.class, () -> products.updateProduct(product, request(url, detail + "\n![新增](" + url + ")")));
        assertThrows(BusinessException.class, () -> products.createProduct(request(url, "正文")));
        jdbc.update("DELETE FROM media_references WHERE owner_type='AVATAR' AND owner_id=?", owner);
        maintenance.retireUnreferencedAvatar(url, owner);
        assertTrue(Files.exists(UPLOADS.resolve(path)));
        products.updateProduct(product, request(null, "不再引用头像"));
        assertFalse(Files.exists(UPLOADS.resolve(path)));
        assertThrows(BusinessException.class, () -> products.updateProduct(product, request(url, "不再引用头像")));
        maintenance.retireUnreferencedAvatar(url, owner);
        assertFalse(Files.exists(UPLOADS.resolve(path)));
    }

    @Test void historicalAvatarReferencesInSiteIconAndAnnouncementRemainEditable() throws Exception {
        UUID avatar = UUID.randomUUID(), owner = UUID.randomUUID(), site = UUID.randomUUID(), announcement = UUID.randomUUID();
        String path = "avatars/avatar-" + avatar + ".png", avatarUrl = "/api/uploads/" + path;
        Files.createDirectories(UPLOADS.resolve("avatars")); Files.write(UPLOADS.resolve(path), new byte[]{1,2,3});
        jdbc.update("INSERT INTO media_assets(id,storage_path,display_path,clear_path,original_filename,media_type,mime_type,size_bytes,scope,owner_user_id) VALUES (?,?,?,?,?,'IMAGE','image/png',3,'AVATAR',?)",
                avatar, path, path, path, "old avatar", owner);
        var existingConfig = jdbc.query("SELECT id,config_value FROM site_configs WHERE config_key='favicon_url'",
                (rs, n) -> new Object[]{rs.getObject(1, UUID.class), rs.getString(2)});
        UUID configId = existingConfig.isEmpty() ? UUID.randomUUID() : (UUID) existingConfig.getFirst()[0];
        String oldIcon = existingConfig.isEmpty() ? null : (String) existingConfig.getFirst()[1];
        if (existingConfig.isEmpty()) jdbc.update("INSERT INTO site_configs(id,config_key,config_value) VALUES (?,'favicon_url','')", configId);
        List<UUID> oldConfigReferences = jdbc.queryForList("SELECT asset_id FROM media_references WHERE owner_type='SITE_CONFIG' AND owner_id=? AND field='favicon_url'", UUID.class, configId);
        jdbc.update("UPDATE site_configs SET config_value=? WHERE id=?", avatarUrl, configId);
        jdbc.update("DELETE FROM media_references WHERE owner_type='SITE_CONFIG' AND owner_id=? AND field='favicon_url'", configId);
        jdbc.update("INSERT INTO media_references VALUES (?,'SITE_CONFIG',?,'favicon_url')", avatar, configId);
        UUID userLevel = jdbc.queryForObject("SELECT id FROM user_levels ORDER BY created_at,id LIMIT 1", UUID.class);
        long userNo = 100_000_000_000L + Math.floorMod(owner.getMostSignificantBits(), 10_000_000_000L);
        jdbc.update("INSERT INTO users(id,username,password_hash,role,avatar_url,user_level_id,user_no) VALUES (?,?, 'test','USER',?,?,?)",
                owner, "avatar-icon-" + owner, avatarUrl, userLevel, userNo);
        jdbc.update("INSERT INTO subsites(id,owner_id,prefix,name,favicon_url) VALUES (?,?,?,'旧分站',?)", site, owner, "s" + site.toString().replace("-", ""), avatarUrl);
        String body = "公告 ![旧头像](" + avatarUrl + ")";
        jdbc.update("INSERT INTO site_announcements(id,title,body,status) VALUES (?,'旧公告',?,'DRAFT')", announcement, body);
        jdbc.update("INSERT INTO media_references VALUES (?,'AVATAR',?,'avatar_url'),(?,'SUBSITE',?,'favicon_url'),(?,'ANNOUNCEMENT',?,'body')",
                avatar, owner, avatar, site, avatar, announcement);
        try {
            siteConfigs.updateConfigs(List.of(java.util.Map.of("config_key", "favicon_url", "config_value", avatarUrl)));
            assertEquals(avatarUrl, jdbc.queryForObject("SELECT config_value FROM site_configs WHERE id=?", String.class, configId));
            subsites.updateMine(owner, new SubsiteManagementService.SiteUpdate("新分站", avatarUrl));
            announcements.edit(null, announcement, new AnnouncementService.Edit("旧公告", body + "\n继续编辑"));
            assertThrows(BusinessException.class, () -> announcements.edit(null, announcement,
                    new AnnouncementService.Edit("旧公告", body + "\n![新增](" + avatarUrl + ")")));
            assertThrows(BusinessException.class, () -> announcements.create(null, new AnnouncementService.Edit("新公告", body)));
            jdbc.update("UPDATE users SET avatar_url=NULL WHERE id=?", owner);
            jdbc.update("DELETE FROM media_references WHERE asset_id=? AND owner_type='AVATAR'", avatar);
            maintenance.retireUnreferencedAvatar(avatarUrl, owner);
            assertTrue(Files.exists(UPLOADS.resolve(path)));
            siteConfigs.updateConfigs(List.of(java.util.Map.of("config_key", "favicon_url", "config_value", "")));
            assertThrows(BusinessException.class, () -> siteConfigs.updateConfigs(List.of(java.util.Map.of("config_key", "favicon_url", "config_value", avatarUrl))));
            subsites.updateMine(owner, new SubsiteManagementService.SiteUpdate("新分站", null));
            assertTrue(Files.exists(UPLOADS.resolve(path)));
            announcements.edit(null, announcement, new AnnouncementService.Edit("旧公告", "正文保留"));
            assertFalse(Files.exists(UPLOADS.resolve(path)));
            assertNotNull(jdbc.queryForObject("SELECT deleted_at FROM media_assets WHERE id=?", java.sql.Timestamp.class, avatar));
        } finally {
            jdbc.update("DELETE FROM media_references WHERE asset_id=?", avatar);
            if (existingConfig.isEmpty()) jdbc.update("DELETE FROM site_configs WHERE id=?", configId);
            else jdbc.update("UPDATE site_configs SET config_value=? WHERE id=?", oldIcon, configId);
            for (UUID prior : oldConfigReferences) jdbc.update("INSERT INTO media_references VALUES (?,'SITE_CONFIG',?,'favicon_url') ON CONFLICT DO NOTHING", prior, configId);
            jdbc.update("DELETE FROM site_announcements WHERE id=?", announcement);
            jdbc.update("DELETE FROM subsites WHERE id=?", site);
            jdbc.update("DELETE FROM users WHERE id=?", owner);
        }
    }

    @Test void staleHistoricalAvatarSavesRecheckTheLockedBusinessRow() throws Exception {
        UUID avatar = UUID.randomUUID(), owner = UUID.randomUUID(), announcement = UUID.randomUUID();
        String path = "avatars/avatar-" + avatar + ".png", avatarUrl = "/api/uploads/" + path;
        Files.createDirectories(UPLOADS.resolve("avatars")); Files.write(UPLOADS.resolve(path), new byte[]{1,2,3});
        jdbc.update("INSERT INTO media_assets(id,storage_path,display_path,clear_path,original_filename,media_type,mime_type,size_bytes,scope,owner_user_id) VALUES (?,?,?,?,?,'IMAGE','image/png',3,'AVATAR',?)",
                avatar, path, path, path, "old avatar", owner);
        var existing = jdbc.query("SELECT id,config_value FROM site_configs WHERE config_key='favicon_url'",
                (rs, n) -> new Object[]{rs.getObject(1, UUID.class), rs.getString(2)});
        UUID config = existing.isEmpty() ? UUID.randomUUID() : (UUID) existing.getFirst()[0];
        String original = existing.isEmpty() ? null : (String) existing.getFirst()[1];
        if (existing.isEmpty()) jdbc.update("INSERT INTO site_configs(id,config_key,config_value) VALUES (?,'favicon_url','')", config);
        List<UUID> previous = jdbc.queryForList("SELECT asset_id FROM media_references WHERE owner_type='SITE_CONFIG' AND owner_id=? AND field='favicon_url'", UUID.class, config);
        jdbc.update("DELETE FROM media_references WHERE owner_type='SITE_CONFIG' AND owner_id=? AND field='favicon_url'", config);
        jdbc.update("UPDATE site_configs SET config_value=? WHERE id=?", avatarUrl, config);
        String body = "![旧头像](" + avatarUrl + ")";
        jdbc.update("INSERT INTO site_announcements(id,title,body,status) VALUES (?,'并发公告',?,'DRAFT')", announcement, body);
        jdbc.update("INSERT INTO media_references VALUES (?,'SITE_CONFIG',?,'favicon_url'),(?,'ANNOUNCEMENT',?,'body')", avatar, config, avatar, announcement);
        try {
            try (Connection transaction = dataSource.getConnection()) {
                transaction.setAutoCommit(false);
                try (var update = transaction.prepareStatement("UPDATE site_configs SET config_value='' WHERE id=?")) { update.setObject(1, config); update.executeUpdate(); }
                try (var remove = transaction.prepareStatement("DELETE FROM media_references WHERE asset_id=? AND owner_type='SITE_CONFIG' AND owner_id=?")) {
                    remove.setObject(1, avatar); remove.setObject(2, config); remove.executeUpdate();
                }
                var staleSave = CompletableFuture.runAsync(() -> siteConfigs.updateConfigs(List.of(java.util.Map.of("config_key", "favicon_url", "config_value", avatarUrl))));
                assertThrows(java.util.concurrent.TimeoutException.class, () -> staleSave.get(250, TimeUnit.MILLISECONDS));
                transaction.commit();
                assertInstanceOf(BusinessException.class, assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> staleSave.get(5, TimeUnit.SECONDS)).getCause());
            }
            try (Connection transaction = dataSource.getConnection()) {
                transaction.setAutoCommit(false);
                try (var update = transaction.prepareStatement("UPDATE site_announcements SET body='正文保留' WHERE id=?")) { update.setObject(1, announcement); update.executeUpdate(); }
                try (var remove = transaction.prepareStatement("DELETE FROM media_references WHERE asset_id=? AND owner_type='ANNOUNCEMENT' AND owner_id=?")) {
                    remove.setObject(1, avatar); remove.setObject(2, announcement); remove.executeUpdate();
                }
                var staleSave = CompletableFuture.runAsync(() -> announcements.edit(null, announcement, new AnnouncementService.Edit("并发公告", body)));
                assertThrows(java.util.concurrent.TimeoutException.class, () -> staleSave.get(250, TimeUnit.MILLISECONDS));
                transaction.commit();
                assertInstanceOf(BusinessException.class, assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> staleSave.get(5, TimeUnit.SECONDS)).getCause());
            }
            assertEquals("", jdbc.queryForObject("SELECT config_value FROM site_configs WHERE id=?", String.class, config));
            assertEquals("正文保留", jdbc.queryForObject("SELECT body FROM site_announcements WHERE id=?", String.class, announcement));
            assertEquals(0, maintenance.inspect(avatar.toString()).references().size());
            maintenance.retireUnreferencedAvatar(avatarUrl, owner);
            assertFalse(Files.exists(UPLOADS.resolve(path)));
        } finally {
            jdbc.update("DELETE FROM media_references WHERE asset_id=?", avatar);
            if (existing.isEmpty()) jdbc.update("DELETE FROM site_configs WHERE id=?", config);
            else jdbc.update("UPDATE site_configs SET config_value=? WHERE id=?", original, config);
            for (UUID prior : previous) jdbc.update("INSERT INTO media_references VALUES (?,'SITE_CONFIG',?,'favicon_url') ON CONFLICT DO NOTHING", prior, config);
            jdbc.update("DELETE FROM site_announcements WHERE id=?", announcement);
        }
    }

    @Test void reuploadOfLegacyBytesAddsVariantsWithoutChangingOldAddressOrIdentity() throws Exception {
        UUID id = UUID.randomUUID(); String oldPath = id + ".png", hash = "9".repeat(64);
        Files.write(UPLOADS.resolve(oldPath), new byte[]{1,2,3});
        jdbc.update("INSERT INTO media_assets(id,storage_path,display_path,clear_path,original_filename,media_type,mime_type,size_bytes,content_hash,scope) VALUES (?,?,?,?,?,'IMAGE','image/png',3,?,'SHARED')",
                id, oldPath, oldPath, oldPath, "legacy.png", hash);
        UUID product = products.createProduct(request("/api/uploads/" + oldPath, "正文")).id();
        assertTrue(assets.findReusableImage(hash).isEmpty(), "legacy original must first gain display variants");
        Path temporary = Files.createTempFile(UPLOADS, ".test-legacy-", ".webp"); Files.writeString(temporary, "variant");
        var variant = new VipsImageProcessor.Variant(temporary, "image/webp", 2, 2, 7);
        var image = mock(VipsImageProcessor.Processed.class);
        when(image.display()).thenReturn(variant); when(image.thumbnail()).thenReturn(variant); when(image.clear()).thenReturn(variant);
        when(image.originalSize()).thenReturn(3L);
        when(image.reason()).thenReturn("color_unspecified");
        var promoted = assets.registerImage(hash, "legacy.png", null, null, image);
        assertEquals(id, promoted.id()); assertTrue(promoted.reused()); assertNotEquals(oldPath, promoted.displayPath());
        assertEquals(oldPath, jdbc.queryForObject("SELECT storage_path FROM media_assets WHERE id=?", String.class, id));
        assertEquals("/api/uploads/" + promoted.displayPath(), jdbc.queryForObject("SELECT cover_url FROM products WHERE id=?", String.class, product));
        assertEquals(3, Files.size(UPLOADS.resolve(oldPath)));
        assertNotNull(resolver.visible("/api/uploads/" + oldPath));
        assertEquals(id, assets.findReusableImage(hash).orElseThrow().id());
        assertEquals("color_unspecified", assets.findReusableImage(hash).orElseThrow().processingReason());
        maintenance.delete(id.toString());
        assertFalse(Files.exists(UPLOADS.resolve(oldPath))); assertFalse(Files.exists(UPLOADS.resolve(promoted.displayPath())));
        Files.deleteIfExists(temporary);
    }

    @Test void rollbackWithFailedFileCleanupCreatesAVisibleRetryableTombstone() throws Exception {
        Path source = Files.createTempFile(UPLOADS, ".rollback-source-", ".webp"); Files.writeString(source, "variant");
        var variant = new VipsImageProcessor.Variant(source, "image/webp", 2, 2, 7);
        var image = mock(VipsImageProcessor.Processed.class);
        when(image.display()).thenReturn(variant); when(image.thumbnail()).thenReturn(variant); when(image.clear()).thenReturn(variant);
        when(image.originalSize()).thenReturn(7L);
        UploadStorage failing = spy(storage); doReturn(false).when(failing).deleteManagedFile(anyString());
        var registry = new MediaAssetRegistry(jdbc, failing, paths);
        var tx = new TransactionTemplate(transactionManager);
        var rolledBack = tx.execute(status -> {
            try {
                var created = registry.registerImage("1".repeat(64), "rollback.webp", null, null, image);
                status.setRollbackOnly(); return created;
            } catch (Exception failure) { throw new RuntimeException(failure); }
        });
        assertNotNull(rolledBack);
        assertTrue(Files.exists(UPLOADS.resolve(rolledBack.displayPath())));
        UUID tombstone = jdbc.queryForObject("SELECT id FROM media_assets WHERE storage_path=? AND deleted_at IS NOT NULL", UUID.class, rolledBack.displayPath());
        assertNotNull(tombstone); assertNotEquals(rolledBack.id(), tombstone);
        assertNotNull(library.list(1, 24, "", "desc", "time", "", "", false, false, tombstone).items().getFirst().cleanupError());
        assertNull(resolver.visible("/api/uploads/" + rolledBack.displayPath()));
        maintenance.delete(tombstone.toString());
        assertFalse(Files.exists(UPLOADS.resolve(rolledBack.displayPath())));
        assertEquals(0, library.list(1, 24, "", "desc", "time", "", "", false, false, tombstone).total());
        Files.deleteIfExists(source);
    }

    private String videoFile(UUID id) throws Exception {
        String relative = "videos/" + id + ".mp4";
        Files.createDirectories(UPLOADS.resolve("videos")); Files.write(UPLOADS.resolve(relative), new byte[]{1,2,3,4,5,6,7,8});
        return relative;
    }

    private UUID fixture(String name) throws Exception {
        UUID id = UUID.randomUUID(); String path = "images/" + id + "-display.png";
        Files.createDirectories(UPLOADS.resolve("images")); Files.write(UPLOADS.resolve(path), new byte[]{(byte) 137,80,78,71,1,2,3,4});
        jdbc.update("INSERT INTO media_assets(id,storage_path,display_path,clear_path,original_filename,media_type,mime_type,size_bytes,scope,processing_status) VALUES (?,?,?,?,?,'IMAGE','image/png',8,'SHARED','READY')",
                id, path, path, path, name);
        return id;
    }
    private String path(UUID id) { return jdbc.queryForObject("SELECT storage_path FROM media_assets WHERE id=?", String.class, id); }
    private String url(UUID id) { return "/api/uploads/" + path(id); }
    private AdminProductUpsertRequest request(String cover, String detail) {
        return new AdminProductUpsertRequest("媒体商品", "", detail, cover, new BigDecimal("0.50"), BigDecimal.ONE,
                category, 10, false, false, 0, 0, "MANUAL", List.of(), StockMode.UNLIMITED, null, List.of(), List.of());
    }
    private static Path tempDirectory() {
        try { return Files.createTempDirectory("myshop-media-test-"); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
