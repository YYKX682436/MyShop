package com.myshop.config;

import com.myshop.service.UploadStorage;
import com.myshop.service.impl.IntegrationPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIf(value = "postgresAvailable", disabledReason = "Disposable PostgreSQL required")
class MediaMigrationConfigurationPostgresTest {
    static final IntegrationPostgres POSTGRES = new IntegrationPostgres();
    static boolean postgresAvailable() { return POSTGRES.available(); }
    @AfterAll static void close() { POSTGRES.close(); }

    @Test void cutoverGateIgnoresSameNamedTriggerInAnUnrelatedSchema() throws Exception {
        var data = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(data);
        Flyway flyway = Flyway.configure().dataSource(data).cleanDisabled(false).load();
        flyway.clean(); Flyway.configure().dataSource(data).target("63").load().migrate();
        String other = "myshop_test_other_" + UUID.randomUUID().toString().replace("-", "");
        String publicTable = "media_unrelated_" + UUID.randomUUID().toString().replace("-", "");
        Path root = Files.createTempDirectory("myshop-cutover-gate-");
        try {
            jdbc.execute("CREATE SCHEMA " + other);
            jdbc.execute("CREATE TABLE " + other + ".other_table (id integer)");
            jdbc.execute("CREATE FUNCTION " + other + ".other_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END $$");
            jdbc.execute("CREATE TRIGGER myshop_media_guard BEFORE INSERT ON " + other + ".other_table FOR EACH ROW EXECUTE FUNCTION " + other + ".other_guard()");
            jdbc.execute("CREATE TABLE public." + publicTable + " (id integer)");
            jdbc.execute("CREATE TRIGGER myshop_media_guard BEFORE INSERT ON public." + publicTable + " FOR EACH ROW EXECUTE FUNCTION " + other + ".other_guard()");
            UploadStorage storage = new UploadStorage(root.toString()); storage.initialize();
            MediaIndexGate gate = new MediaIndexGate();
            var before = request(); var unavailable = new MockHttpServletResponse();
            gate.doFilter(before, unavailable, new MockFilterChain());
            assertEquals(503, unavailable.getStatus());
            @SuppressWarnings("unchecked") ObjectProvider<UploadStorage> storageProvider = mock(ObjectProvider.class);
            @SuppressWarnings("unchecked") ObjectProvider<MediaIndexGate> gateProvider = mock(ObjectProvider.class);
            when(storageProvider.getObject()).thenReturn(storage); when(gateProvider.getObject()).thenReturn(gate);
            new MediaMigrationConfiguration().stagedMediaMigration(data, storageProvider, gateProvider,
                    "/api/uploads", "http://localhost:3000").migrate(flyway);
            assertEquals("70", flyway.info().current().getVersion().getVersion());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND t.tgname='myshop_media_guard' AND NOT t.tgisinternal", Integer.class, other));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relname=? AND t.tgname='myshop_media_guard' AND NOT t.tgisinternal", Integer.class, publicTable));
            var after = request(); var available = new MockHttpServletResponse();
            gate.doFilter(after, available, new MockFilterChain());
            assertEquals(200, available.getStatus());
        } finally {
            // DROP SCHEMA CASCADE removes the test-only trigger from its public table too.
            jdbc.execute("DROP SCHEMA IF EXISTS " + other + " CASCADE");
            jdbc.execute("DROP TABLE IF EXISTS public." + publicTable);
            try (var files = Files.walk(root)) {
                for (var path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    @Test void completedV64StartupRepairsOnlyMissingCurrentAvatarReferences() throws Exception {
        var data = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(data);
        Flyway flyway = Flyway.configure().dataSource(data).cleanDisabled(false).load();
        flyway.clean(); Flyway.configure().configuration(flyway.getConfiguration()).target("63").load().migrate();
        Path root = Files.createTempDirectory("myshop-avatar-repair-");
        try {
            UploadStorage storage = new UploadStorage(root.toString()); storage.initialize();
            new com.myshop.service.MediaLegacyIndexer(jdbc, storage,
                    new com.myshop.service.ManagedMediaReferences(jdbc, "/api/uploads", "https://mc.mc520.top"))
                    .indexBeforeCutover();
            flyway.migrate();
            Files.createDirectories(root.resolve("avatars"));
            String sharedPath = "avatars/avatar-aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa.png";
            String tombstonePath = "avatars/avatar-bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb.png";
            String unindexedLegacyPath = "cccccccc-cccc-4ccc-8ccc-cccccccccccc.png";
            Files.write(root.resolve(sharedPath), java.util.Base64.getDecoder().decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScL8WQAAAABJRU5ErkJggg=="));
            Files.write(root.resolve(unindexedLegacyPath), new byte[]{1, 2, 3});
            UUID disabled = UUID.fromString("00000000-0000-4000-8000-000000000001");
            UUID active = UUID.fromString("00000000-0000-4000-8000-000000000002");
            UUID tombstonedUser = UUID.fromString("00000000-0000-4000-8000-000000000003");
            UUID sharedAsset = UUID.randomUUID(), tombstoneAsset = UUID.randomUUID(), productOwner = UUID.randomUUID();
            insertRepairUser(jdbc, disabled, 1, sharedPath, true);
            insertRepairUser(jdbc, active, 2, sharedPath, false);
            insertRepairUser(jdbc, tombstonedUser, 3, tombstonePath, true);
            jdbc.update("""
                    INSERT INTO media_assets(id,storage_path,display_path,clear_path,original_filename,media_type,mime_type,size_bytes,scope,processing_status)
                    VALUES (?,?,?, ?,?,'IMAGE','image/png',?,'AVATAR','READY')
                    """, sharedAsset, sharedPath, sharedPath, sharedPath, "avatar-legacy.png", Files.size(root.resolve(sharedPath)));
            jdbc.update("""
                    INSERT INTO media_assets(id,storage_path,display_path,clear_path,original_filename,media_type,mime_type,size_bytes,scope,processing_status,deleted_at)
                    VALUES (?,?,?, ?,?,'IMAGE','image/png',0,'AVATAR','READY',CURRENT_TIMESTAMP)
                    """, tombstoneAsset, tombstonePath, tombstonePath, tombstonePath, "avatar-deleted.png");
            jdbc.update("INSERT INTO media_references(asset_id,owner_type,owner_id,field) VALUES (?,'AVATAR',?,'avatar_url')", sharedAsset, active);
            jdbc.update("INSERT INTO media_references(asset_id,owner_type,owner_id,field) VALUES (?,'PRODUCT',?,'cover_url')", sharedAsset, productOwner);

            MediaIndexGate gate = new MediaIndexGate();
            @SuppressWarnings("unchecked") ObjectProvider<UploadStorage> storageProvider = mock(ObjectProvider.class);
            @SuppressWarnings("unchecked") ObjectProvider<MediaIndexGate> gateProvider = mock(ObjectProvider.class);
            when(storageProvider.getObject()).thenReturn(storage); when(gateProvider.getObject()).thenReturn(gate);
            var strategy = new MediaMigrationConfiguration().stagedMediaMigration(data, storageProvider, gateProvider,
                    "/api/uploads", "https://mc.mc520.top");
            assertEquals("70", flyway.info().current().getVersion().getVersion());
            strategy.migrate(flyway);

            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM media_references WHERE asset_id=? AND owner_type='AVATAR' AND field='avatar_url'", Integer.class, sharedAsset));
            assertEquals(disabled, jdbc.queryForObject("SELECT owner_user_id FROM media_assets WHERE id=?", UUID.class, sharedAsset),
                    "the first current owner by UUID fills the legacy owner field, even when disabled");
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM media_references WHERE asset_id=? AND owner_type='PRODUCT' AND owner_id=? AND field='cover_url'", Integer.class, sharedAsset, productOwner));
            assertNull(jdbc.queryForObject("SELECT avatar_url FROM users WHERE id=?", String.class, tombstonedUser));
            assertNotNull(jdbc.queryForObject("SELECT deleted_at FROM media_assets WHERE id=?", java.sql.Timestamp.class, tombstoneAsset));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM media_assets WHERE storage_path=?", Integer.class, unindexedLegacyPath),
                    "a post-V64 startup must not rerun the old public directory index");
            assertEquals("70", flyway.info().current().getVersion().getVersion());

            strategy.migrate(flyway);
            var repeated = new com.myshop.service.MediaLegacyIndexer(jdbc, storage,
                    new com.myshop.service.ManagedMediaReferences(jdbc, "/api/uploads", "https://mc.mc520.top"))
                    .repairAvatarReferences();
            assertEquals(new com.myshop.service.MediaLegacyIndexer.AvatarRepairStats(0, 0, 0), repeated);
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM media_references WHERE asset_id=? AND owner_type='AVATAR' AND field='avatar_url'", Integer.class, sharedAsset));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM media_references WHERE asset_id=? AND owner_type='PRODUCT'", Integer.class, sharedAsset));
        } finally {
            try (var files = Files.walk(root)) {
                for (var path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void insertRepairUser(JdbcTemplate jdbc, UUID id, long number, String path, boolean disabled) {
        jdbc.update("""
                INSERT INTO users(id,username,email,password_hash,role,user_no,user_level_id,avatar_url,is_deleted)
                VALUES (?,?,?,'fixture-password','USER',?,(SELECT id FROM user_levels WHERE is_default=true LIMIT 1),?,?)
                """, id, "avatar-repair-" + number, "avatar-repair-" + number + "@example.com", number,
                "/api/uploads/" + path, disabled ? 1 : 0);
    }

    private static MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("POST", "/api/admin/products"); request.setContextPath("/api"); return request;
    }
}
