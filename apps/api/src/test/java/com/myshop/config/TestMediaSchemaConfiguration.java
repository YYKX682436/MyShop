package com.myshop.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/** H2 tests do not run Flyway; provide the two media tables used by JDBC. */
@AutoConfiguration
@ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "false")
public class TestMediaSchemaConfiguration {
    @Bean
    Object h2MediaSchema(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS media_assets (
                  id UUID PRIMARY KEY, storage_path VARCHAR UNIQUE, display_path VARCHAR, thumbnail_path VARCHAR, clear_path VARCHAR,
                  original_filename VARCHAR NOT NULL, media_type VARCHAR NOT NULL, mime_type VARCHAR, size_bytes BIGINT DEFAULT 0 NOT NULL,
                  original_size_bytes BIGINT, thumbnail_size_bytes BIGINT, clear_size_bytes BIGINT, width INTEGER, height INTEGER,
                  duration_ms BIGINT, content_hash VARCHAR, source_hash VARCHAR, scope VARCHAR DEFAULT 'SHARED' NOT NULL, uploader_id UUID, owner_user_id UUID,
                  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL, processing_status VARCHAR DEFAULT 'READY' NOT NULL,
                  upload_uri VARCHAR, declared_size_bytes BIGINT, processing_error VARCHAR, processing_reason VARCHAR, deleted_at TIMESTAMP, cleanup_error VARCHAR,
                  upload_cleanup_error VARCHAR, reused_asset_id UUID)
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS media_references(asset_id UUID NOT NULL, owner_type VARCHAR NOT NULL,
                  owner_id UUID NOT NULL, field VARCHAR NOT NULL, PRIMARY KEY(asset_id,owner_type,owner_id,field))
                """);
        return new Object();
    }
}
