package com.myshop.service;

import com.myshop.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class MediaLibraryServiceTest {
    @Test
    void databasePaginationFiltersCurrentReferencesAndAvatarScope() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:media_library;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE media_assets(id UUID PRIMARY KEY,original_filename VARCHAR,storage_path VARCHAR,display_path VARCHAR,
                  thumbnail_path VARCHAR,clear_path VARCHAR,mime_type VARCHAR,media_type VARCHAR,size_bytes BIGINT,original_size_bytes BIGINT,
                  thumbnail_size_bytes BIGINT,clear_size_bytes BIGINT,width INT,height INT,duration_ms BIGINT,created_at TIMESTAMP,
                  processing_status VARCHAR,deleted_at TIMESTAMP,cleanup_error VARCHAR,upload_cleanup_error VARCHAR,processing_error VARCHAR,processing_reason VARCHAR,scope VARCHAR,reused_asset_id UUID)
                """);
        jdbc.execute("CREATE TABLE media_references(asset_id UUID,owner_type VARCHAR,owner_id UUID,field VARCHAR)");
        UUID image = UUID.randomUUID(), avatar = UUID.randomUUID(), video = UUID.randomUUID();
        insert(jdbc, image, "商品封面.jpg", "images/cover.webp", "IMAGE", "SHARED", 1000, "2024-01-01 00:00:00");
        insert(jdbc, avatar, "头像.png", "avatars/avatar.png", "IMAGE", "AVATAR", 200, "2024-02-01 00:00:00");
        insert(jdbc, video, "教程.mp4", "videos/tutorial.mp4", "VIDEO", "SHARED", 3000, "2024-03-01 00:00:00");
        jdbc.update("INSERT INTO media_references VALUES (?,'PRODUCT',?,'cover_url')", image, UUID.randomUUID());
        jdbc.update("INSERT INTO media_references VALUES (?,'AVATAR',?,'avatar_url')", avatar, UUID.randomUUID());
        jdbc.update("INSERT INTO media_references VALUES (?,'SITE_CONFIG',?,'favicon_url')", image, UUID.randomUUID());
        var media = new MediaLibraryService(jdbc, "/api/uploads");
        assertEquals(3, media.list(1, 24, "", "desc").total());
        assertEquals("商品封面.jpg", media.list(1, 24, "封面", "desc").items().getFirst().name());
        assertEquals(1, media.list(1, 24, "", "desc", "time", "", "product", false, false, null).total());
        assertEquals(1, media.list(1, 24, "", "desc", "time", "", "site", false, false, null).total());
        assertEquals(1, media.list(1, 24, "", "desc", "time", "video", "other", true, false, null).total());
        assertEquals(2, media.list(1, 24, "", "desc", "time", "", "", false, true, null).total());
        assertEquals(video, media.list(1, 1, "", "desc", "size", "", "", false, false, null).items().getFirst().id());
        assertEquals(1, media.list(1, 24, "", "desc", "time", "", "", false, false, avatar).total());
        assertThrows(BusinessException.class, () -> media.list(1, 1000, "", "desc"));
    }

    private static void insert(JdbcTemplate jdbc, UUID id, String name, String path, String type, String scope, long size, String created) {
        jdbc.update("""
                INSERT INTO media_assets(id,original_filename,storage_path,display_path,mime_type,media_type,size_bytes,created_at,processing_status,scope)
                VALUES (?,?,?,?,?,?,?,CAST(? AS TIMESTAMP),'READY',?)
                """, id, name, path, path, type.equals("IMAGE") ? "image/webp" : "video/mp4", type, size, created, scope);
    }
}
