package com.myshop.config;

import com.myshop.service.ManagedMediaReferences;
import com.myshop.service.MediaLegacyIndexer;
import com.myshop.service.UploadStorage;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "true", matchIfMissing = true)
public class MediaMigrationConfiguration {
    @Bean
    FlywayMigrationStrategy stagedMediaMigration(DataSource dataSource, ObjectProvider<UploadStorage> uploadStorage,
                                                ObjectProvider<MediaIndexGate> gate,
                                                @Value("${upload.url-prefix:/api/uploads}") String prefix,
                                                @Value("${app.public-site-url:http://localhost:3000}") String publicSite) {
        return flyway -> {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            var current = flyway.info().current();
            boolean beforeCutover = current == null || current.getVersion().compareTo(MigrationVersion.fromVersion("64")) < 0;
            if (beforeCutover) {
                Flyway.configure().configuration(flyway.getConfiguration()).target("63").load().migrate();
                var indexer = new MediaLegacyIndexer(jdbc, uploadStorage.getObject(), new ManagedMediaReferences(jdbc, prefix, publicSite));
                indexer.indexBeforeCutover();
                flyway.migrate();
                indexer.clearOldDeletedReferences();
            } else {
                flyway.migrate();
                new MediaLegacyIndexer(jdbc, uploadStorage.getObject(), new ManagedMediaReferences(jdbc, prefix, publicSite))
                        .clearOldDeletedReferences();
            }
            String marker = jdbc.queryForObject("SELECT obj_description('public.media_references'::regclass,'pg_class')", String.class);
            Integer oldTriggers = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
                    JOIN pg_namespace n ON n.oid=c.relnamespace
                    WHERE n.nspname='public' AND t.tgname='myshop_media_guard' AND NOT t.tgisinternal
                      AND t.tgfoid=to_regprocedure('public.myshop_media_guard_write()')
                    """, Integer.class);
            if (!"myshop-media-index-complete-v1".equals(marker) || oldTriggers == null || oldTriggers != 0)
                throw new IllegalStateException("Media index or legacy trigger cutover is incomplete");
            gate.getObject().markReady();
        };
    }
}
