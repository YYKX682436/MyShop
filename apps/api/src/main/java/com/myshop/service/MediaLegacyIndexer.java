package com.myshop.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** One-time, resumable file and explicit-reference indexing outside Flyway transactions. */
@Slf4j
@Component
public class MediaLegacyIndexer {
    private static final Pattern ROOT_IMAGE = Pattern.compile("(?i)(?:[0-9a-f]{64}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.(jpg|jpeg|png|gif|webp|bmp|avif|heic|heif)");
    private static final Pattern AVATAR = Pattern.compile("(?i)avatar-[0-9a-f-]+\\.(jpg|jpeg|png|gif|webp|avif)");
    private static final int BATCH = 250;
    private final JdbcTemplate jdbc;
    private final UploadStorage storage;
    private final ManagedMediaReferences paths;

    public MediaLegacyIndexer(JdbcTemplate jdbc, UploadStorage storage, ManagedMediaReferences paths) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.paths = paths;
    }

    public void indexBeforeCutover() {
        indexDirectory(storage.directory(), false);
        indexDirectory(storage.directory().resolve("avatars"), true);
        page("SELECT id,cover_url,detail_md FROM products WHERE is_deleted=0", row -> {
            indexField("PRODUCT", row.id(), "cover_url", row.first(), false);
            indexField("PRODUCT", row.id(), "detail_md", row.second(), true);
        });
        page("SELECT id,avatar_url,NULL AS other FROM users WHERE is_deleted=0", row -> {
            indexField("AVATAR", row.id(), "avatar_url", row.first(), false);
            String path = paths.managedPath(row.first());
            if (path != null) jdbc.update("UPDATE media_assets SET owner_user_id=coalesce(owner_user_id,?) WHERE storage_path=? AND scope='AVATAR'", row.id(), path);
        });
        page("SELECT id,config_value,NULL AS other FROM site_configs WHERE config_key='favicon_url'", row ->
                indexField("SITE_CONFIG", row.id(), "favicon_url", row.first(), false));
        page("SELECT id,favicon_url,NULL AS other FROM subsites", row ->
                indexField("SUBSITE", row.id(), "favicon_url", row.first(), false));
        page("SELECT id,body,NULL AS other FROM site_announcements", row ->
                indexField("ANNOUNCEMENT", row.id(), "body", row.first(), true));
        // V64 refuses to remove the legacy guard until this durable marker is
        // written. All preceding work used short independent transactions.
        jdbc.execute("COMMENT ON TABLE media_references IS 'myshop-media-index-complete-v1'");
        log.info("Legacy public media and current references were indexed");
    }

    /** Removes only references to files deleted before migration, never other historical data. */
    public void clearOldDeletedReferences() {
        List<DeletedReference> refs = jdbc.query("""
                SELECT r.asset_id,r.owner_type,r.owner_id,r.field,a.storage_path,a.display_path,a.thumbnail_path,a.clear_path
                FROM media_references r JOIN media_assets a ON a.id=r.asset_id
                WHERE a.deleted_at IS NOT NULL ORDER BY r.asset_id,r.owner_type,r.owner_id,r.field
                """, (rs, n) -> new DeletedReference(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)));
        for (DeletedReference ref : refs) {
            String table = switch (ref.ownerType()) {
                case "PRODUCT" -> "products";
                case "AVATAR" -> "users";
                case "SITE_CONFIG" -> "site_configs";
                case "SUBSITE" -> "subsites";
                case "ANNOUNCEMENT" -> "site_announcements";
                default -> throw new IllegalStateException("Unexpected media reference type");
            };
            String column = switch (ref.ownerType() + ":" + ref.field()) {
                case "PRODUCT:cover_url", "PRODUCT:detail_md", "AVATAR:avatar_url", "SUBSITE:favicon_url", "ANNOUNCEMENT:body" -> ref.field();
                case "SITE_CONFIG:favicon_url" -> "config_value";
                default -> throw new IllegalStateException("Unexpected media reference field");
            };
            List<String> values = jdbc.query("SELECT " + column + " FROM " + table + " WHERE id=?",
                    (rs, n) -> rs.getString(1), ref.ownerId());
            if (!values.isEmpty() && values.getFirst() != null) {
                String before = values.getFirst(), after = before;
                for (String path : ref.paths()) {
                    if (ref.field().equals("body") || ref.field().equals("detail_md"))
                        after = MarkdownMediaCleaner.removeLegacyTombstone(after, path, paths);
                    else if (paths.direct(after).stream().anyMatch(value -> paths.equivalent(value, path)))
                        after = ref.ownerType().equals("SITE_CONFIG") ? "" : null;
                    if (after == null) break;
                }
                if (ref.ownerType().equals("ANNOUNCEMENT") && (after == null || after.isBlank())) after = "（资源已删除）";
                if (!java.util.Objects.equals(before, after)) {
                    jdbc.update("UPDATE " + table + " SET " + column + "=? WHERE id=?", after, ref.ownerId());
                    if (ref.field().equals("body") || ref.field().equals("detail_md"))
                        paths.pruneRemovedMarkdownReferences(ref.ownerType(), ref.ownerId(), ref.field(), after);
                }
            }
            jdbc.update("DELETE FROM media_references WHERE asset_id=? AND owner_type=? AND owner_id=? AND field=?",
                    ref.assetId(), ref.ownerType(), ref.ownerId(), ref.field());
        }
    }

    private void indexDirectory(Path directory, boolean avatar) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) return;
        try (var files = Files.list(directory)) {
            files.filter(path -> (avatar ? AVATAR : ROOT_IMAGE).matcher(path.getFileName().toString()).matches())
                    .forEach(path -> registerFile(path, avatar));
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot enumerate legacy public media", exception);
        }
    }

    private void registerFile(Path path, boolean avatar) {
        String relative = storage.directory().relativize(path).toString().replace('\\', '/');
        if (!storage.isSafePublicFile(relative)) return;
        if (!relative.contains("/") && Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM media_assets WHERE deleted_at IS NOT NULL AND position('/' in storage_path)=0
                  AND lower(storage_path)=lower(?))
                """, Boolean.class, relative))) return;
        try {
            long size = Files.size(path);
            String filename = path.getFileName().toString();
            String identifier = filename.substring(0, filename.lastIndexOf('.'));
            String name = avatar ? filename : storage.mediaName(identifier, filename);
            int[] dimensions = dimensions(path);
            String hash = hash(path);
            jdbc.update("""
                    INSERT INTO media_assets(storage_path,display_path,clear_path,original_filename,media_type,mime_type,
                      size_bytes,clear_size_bytes,width,height,content_hash,scope,created_at,processing_status)
                    VALUES (?,?,?,?,'IMAGE',?,?,?,?,?,?,?,?,'READY') ON CONFLICT(storage_path) DO NOTHING
                    """, relative, relative, relative, truncate(name), mime(filename), size, size,
                    dimensions == null ? null : dimensions[0], dimensions == null ? null : dimensions[1], hash,
                    avatar ? "AVATAR" : "SHARED", Timestamp.from(Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant()));
        } catch (IOException | SecurityException exception) {
            // A single corrupt/inaccessible file must not prevent healthy legacy media from loading.
            log.warn("Could not index legacy public media {}: {}", relative, exception.getClass().getSimpleName());
        }
    }

    private void indexField(String ownerType, UUID ownerId, String field, String value, boolean markdown) {
        Set<String> references = markdown ? paths.markdown(value) : paths.direct(value);
        jdbc.update("DELETE FROM media_references WHERE owner_type=? AND owner_id=? AND field=?", ownerType, ownerId, field);
        for (String path : references) {
            List<UUID> ids = jdbc.query("""
                    SELECT id FROM media_assets WHERE storage_path=? OR display_path=? OR thumbnail_path=? OR clear_path=?
                      OR (deleted_at IS NOT NULL AND position('/' in storage_path)=0 AND position('/' in ?)=0
                          AND lower(storage_path)=lower(?)) ORDER BY id
                    """, (rs, n) -> rs.getObject(1, UUID.class), path, path, path, path, path, path);
            if (ids.isEmpty()) {
                ensureMissingAsset(path);
                ids = jdbc.query("SELECT id FROM media_assets WHERE storage_path=?", (rs, n) -> rs.getObject(1, UUID.class), path);
            }
            if (ids.isEmpty()) throw new IllegalStateException("Could not index referenced managed media: " + path);
            for (UUID assetId : ids)
                jdbc.update("INSERT INTO media_references(asset_id,owner_type,owner_id,field) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
                        assetId, ownerType, ownerId, field);
        }
    }

    private void ensureMissingAsset(String relative) {
        Path file;
        try { file = storage.resolveManagedPath(relative); }
        catch (IllegalArgumentException exception) { return; }
        if (storage.isSafePublicFile(relative)) {
            registerFile(file, relative.startsWith("avatars/"));
            if (!jdbc.queryForList("SELECT id FROM media_assets WHERE storage_path=?", UUID.class, relative).isEmpty()) return;
        }
        String name = file.getFileName().toString();
        String kind = relative.startsWith("videos/") ? "VIDEO" : "IMAGE";
        jdbc.update("""
                INSERT INTO media_assets(storage_path,original_filename,media_type,mime_type,scope,processing_status,processing_error)
                VALUES (?,?,?,?,?,'FAILED','历史文件缺失') ON CONFLICT(storage_path) DO NOTHING
                """, relative, truncate(name), kind, mime(name), relative.startsWith("avatars/") ? "AVATAR" : "SHARED");
        log.warn("Referenced legacy public media is missing: {}", relative);
    }

    private void page(String select, java.util.function.Consumer<Row> consume) {
        UUID cursor = null;
        while (true) {
            String sql = select + (select.toLowerCase(Locale.ROOT).contains(" where ") ? " AND " : " WHERE ")
                    + (cursor == null ? "true" : "id > ?") + " ORDER BY id LIMIT " + BATCH;
            List<Row> rows = cursor == null
                    ? jdbc.query(sql, (rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)))
                    : jdbc.query(sql, (rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)), cursor);
            if (rows.isEmpty()) return;
            rows.forEach(consume);
            cursor = rows.getLast().id();
        }
    }

    private static String truncate(String value) { return value.length() <= 512 ? value : value.substring(0, 512); }
    private static String mime(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".avif")) return "image/avif";
        if (lower.endsWith(".heic")) return "image/heic";
        if (lower.endsWith(".heif")) return "image/heif";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mov")) return "video/quicktime";
        return "application/octet-stream";
    }
    private static String hash(Path file) throws IOException {
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0;) if (read > 0) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static int[] dimensions(Path file) {
        try (var input = ImageIO.createImageInputStream(file.toFile())) {
            if (input == null) return null;
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try { reader.setInput(input, true, true); return new int[]{reader.getWidth(0), reader.getHeight(0)}; }
            finally { reader.dispose(); }
        } catch (IOException | RuntimeException ignored) { return null; }
    }
    private record Row(UUID id, String first, String second) { }
    private record DeletedReference(UUID assetId, String ownerType, UUID ownerId, String field,
                                    String storage, String display, String thumbnail, String clear) {
        List<String> paths() {
            List<String> result = new ArrayList<>();
            for (String path : new String[]{storage, display, thumbnail, clear}) if (path != null && !result.contains(path)) result.add(path);
            return result;
        }
    }
}
