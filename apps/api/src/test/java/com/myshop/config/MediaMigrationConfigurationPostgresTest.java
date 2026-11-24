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
            assertEquals("64", flyway.info().current().getVersion().getVersion());
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

    private static MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("POST", "/api/admin/products"); request.setContextPath("/api"); return request;
    }
}
