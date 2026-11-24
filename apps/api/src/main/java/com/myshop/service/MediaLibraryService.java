package com.myshop.service;

import com.myshop.constant.ErrorCode;
import com.myshop.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Indexed public media, paginated and filtered in the database. */
@Service
public class MediaLibraryService {
    private final JdbcTemplate jdbc;
    private final String prefix;

    public MediaLibraryService(JdbcTemplate jdbc, @Value("${upload.url-prefix:/api/uploads}") String prefix) {
        this.jdbc = jdbc;
        this.prefix = prefix.replaceAll("/+$", "");
    }

    public MediaPage list(int page, int pageSize, String query, String order) {
        return list(page, pageSize, query, order, "time", "", "", false, false, null);
    }

    public MediaPage list(int page, int pageSize, String query, String order, String sort,
                          String type, String usage, boolean unused, boolean excludeAvatars, UUID assetId) {
        query = query == null ? "" : query.trim();
        order = order == null ? "desc" : order.toLowerCase(Locale.ROOT);
        sort = sort == null ? "time" : sort.toLowerCase(Locale.ROOT);
        type = type == null ? "" : type.toLowerCase(Locale.ROOT);
        usage = usage == null ? "" : usage.toLowerCase(Locale.ROOT);
        if (page < 1 || page > 100_000 || pageSize < 1 || pageSize > 100 || query.length() > 120
                || !Set.of("asc", "desc").contains(order) || !Set.of("time", "size").contains(sort)
                || !Set.of("", "image", "video").contains(type)
                || !Set.of("", "product", "avatar", "site", "other").contains(usage))
            throw new BusinessException(ErrorCode.BAD_REQUEST, "资源查询参数无效");
        StringBuilder where = new StringBuilder(" WHERE (a.deleted_at IS NULL OR a.cleanup_error IS NOT NULL OR a.upload_cleanup_error IS NOT NULL)");
        List<Object> args = new ArrayList<>();
        if (assetId != null) { where.append(" AND a.id=?"); args.add(assetId); }
        if (!query.isEmpty()) {
            where.append(" AND lower(a.original_filename) LIKE ? ESCAPE '!'");
            args.add("%" + query.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        }
        if (!type.isEmpty()) { where.append(" AND a.media_type=?"); args.add(type.toUpperCase(Locale.ROOT)); }
        if (excludeAvatars) where.append(" AND a.scope<>'AVATAR'");
        if (unused || usage.equals("other")) where.append(" AND NOT EXISTS (SELECT 1 FROM media_references r WHERE r.asset_id=a.id)");
        else if (!usage.isEmpty()) {
            where.append(" AND EXISTS (SELECT 1 FROM media_references r WHERE r.asset_id=a.id AND r.owner_type IN (");
            where.append(switch (usage) { case "product" -> "'PRODUCT'"; case "avatar" -> "'AVATAR'"; case "site" -> "'SITE_CONFIG','SUBSITE','ANNOUNCEMENT'"; default -> throw new IllegalStateException(); });
            where.append("))");
        }
        long total = jdbc.queryForObject("SELECT count(*) FROM media_assets a" + where, Long.class, args.toArray());
        String column = sort.equals("size") ? "a.size_bytes" : "a.created_at";
        String sql = """
                SELECT a.id,a.original_filename,a.storage_path,a.display_path,a.thumbnail_path,a.clear_path,a.mime_type,
                  a.media_type,a.size_bytes,a.original_size_bytes,a.thumbnail_size_bytes,a.clear_size_bytes,a.width,a.height,
                  a.duration_ms,a.created_at,a.processing_status,a.deleted_at,a.cleanup_error,a.upload_cleanup_error,a.processing_error,a.processing_reason,a.scope,a.reused_asset_id,
                  (SELECT string_agg(DISTINCT r.owner_type,',') FROM media_references r WHERE r.asset_id=a.id) AS uses
                FROM media_assets a
                """ + where + " ORDER BY " + column + " " + order + ",a.id " + order + " LIMIT ? OFFSET ?";
        args.add(pageSize); args.add((long) (page - 1) * pageSize);
        List<MediaItem> items = jdbc.query(sql, (rs, n) -> {
            String display = rs.getString("display_path");
            String fallback = rs.getString("storage_path");
            String path = display == null ? fallback : display;
            String rawUses = rs.getString("uses");
            List<String> uses = new ArrayList<>();
            if (rawUses != null) for (String owner : rawUses.split(",")) {
                String mapped = switch (owner) { case "PRODUCT" -> "product"; case "AVATAR" -> "avatar"; default -> "site"; };
                if (!uses.contains(mapped)) uses.add(mapped);
            }
            if (uses.isEmpty()) uses.add("other");
            String mime = rs.getString("mime_type");
            String format = path == null || !path.contains(".") ? (mime == null ? "" : mime.substring(mime.indexOf('/') + 1))
                    : path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            return new MediaItem(rs.getObject("id", UUID.class), rs.getString("original_filename"), url(path),
                    rs.getLong("size_bytes"), format, rs.getTimestamp("created_at").getTime(), rs.getString("media_type").toLowerCase(Locale.ROOT),
                    uses, rs.getTimestamp("deleted_at") != null ? "deleted" : rs.getString("processing_status").toLowerCase(Locale.ROOT),
                    url(rs.getString("thumbnail_path")), url(rs.getString("clear_path")), rs.getObject("original_size_bytes", Long.class),
                    rs.getObject("thumbnail_size_bytes", Long.class), rs.getObject("clear_size_bytes", Long.class),
                    rs.getObject("width", Integer.class), rs.getObject("height", Integer.class), rs.getObject("duration_ms", Long.class),
                    rs.getString("cleanup_error"), rs.getString("upload_cleanup_error"), rs.getString("processing_error"), rs.getString("processing_reason"), rs.getString("scope").toLowerCase(Locale.ROOT),
                    rs.getObject("reused_asset_id", UUID.class));
        }, args.toArray());
        return new MediaPage(items, total, page, pageSize);
    }

    private String url(String path) { return path == null ? null : prefix + "/" + path; }
    public record MediaItem(UUID id, String name, String url, long size, String format, long uploadedAt,
                            String type, List<String> usage, String status, String thumbnailUrl, String clearUrl,
                            Long originalSize, Long thumbnailSize, Long clearSize, Integer width, Integer height,
                            Long durationMs, String cleanupError, String uploadCleanupError, String processingError, String processingReason, String scope, UUID reusedAssetId) { }
    public record MediaPage(List<MediaItem> items, long total, int page, int pageSize) { }
}
