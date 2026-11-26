package com.myshop.service;

import com.myshop.constant.ErrorCode;
import com.myshop.context.RequestContext;
import com.myshop.context.SiteRequestContext;
import com.myshop.exception.BusinessException;
import com.myshop.repository.SubsiteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Published, audience-scoped notification content and its real read receipts. */
@Service
@RequiredArgsConstructor
public class AnnouncementService {
    private static final String SITE_SCOPE = "coalesce(?::uuid, '00000000-0000-0000-0000-000000000000'::uuid)";

    private final JdbcTemplate jdbc;
    private final SubsiteRepository subsites;
    private MediaAssetRegistry mediaAssets;
    private ManagedMediaReferences managedMedia;

    @org.springframework.beans.factory.annotation.Autowired
    public void setMediaAssets(MediaAssetRegistry mediaAssets) { this.mediaAssets = mediaAssets; }

    @org.springframework.beans.factory.annotation.Autowired
    public void setManagedMedia(ManagedMediaReferences managedMedia) { this.managedMedia = managedMedia; }

    public record Edit(UUID requestKey, String title, String body, String audienceType,
                       List<UUID> audienceLevelIds, boolean publish) {
        public Edit(String title, String body) {
            this(UUID.randomUUID(), title, body, "ALL", List.of(), false);
        }
    }
    private record Identity(UUID userId, byte[] guestHash) {}
    private record Cursor(LocalDateTime publishedAt, UUID id) {}

    @Transactional(readOnly = true)
    public Map<String, Object> publicList(UUID siteId, String before, int size, String token) {
        return publicList(siteId, before, size, token, false);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> publicList(UUID siteId, String before, int size, String token, boolean includeUnreadAnchor) {
        int boundedSize = Math.max(1, Math.min(size, 50));
        Identity identity = identity(token, false);
        List<Object> args = new ArrayList<>();
        String readCondition;
        if (identity == null) readCondition = "false";
        else if (identity.userId() != null) {
            readCondition = "r.user_id=?";
            args.add(identity.userId());
        } else {
            readCondition = "r.guest_token_hash=?";
            args.add(identity.guestHash());
        }
        args.add(siteId);
        String visibility = visibilityClause(identity, args);
        String cursorCondition = cursorClause(before, args);
        args.add(boundedSize + 1);
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT a.id,a.title,a.body,a.status,a.published_at,a.publish_version,
                       (a.published_at IS NOT NULL AND coalesce(r.read_version,0)<a.publish_version) AS unread
                FROM site_announcements a LEFT JOIN site_announcement_reads r
                  ON r.announcement_id=a.id AND """ + " " + readCondition + "\n" + """
                WHERE a.site_scope=""" + SITE_SCOPE + " AND a.status='PUBLISHED' AND " + visibility + cursorCondition + "\n"
                + "ORDER BY a.published_at DESC NULLS LAST,a.id DESC LIMIT ?",
                (rs, n) -> {
                    Map<String, Object> row = announcement(rs);
                    row.put("cursor", encodeCursor(timestamp(rs, "published_at"), rs.getObject("id", UUID.class)));
                    return row;
                }, args.toArray());
        boolean more = rows.size() > boundedSize;
        if (more) rows.removeLast();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", rows);
        result.put("next_cursor", more && !rows.isEmpty() ? rows.getLast().get("cursor") : null);
        if (includeUnreadAnchor && (before == null || before.isBlank())) {
            List<Object> anchorArgs = new ArrayList<>();
            String anchorReceipt = receiptJoin(identity, anchorArgs);
            anchorArgs.add(siteId);
            String anchorVisibility = visibilityClause(identity, anchorArgs);
            List<Map<String, Object>> unreadAnchor = jdbc.query("SELECT a.id,a.published_at FROM site_announcements a "
                            + "LEFT JOIN site_announcement_reads r ON r.announcement_id=a.id AND " + anchorReceipt
                            + " WHERE a.site_scope=" + SITE_SCOPE + " AND a.status='PUBLISHED' AND a.published_at IS NOT NULL "
                            + "AND coalesce(r.read_version,0)<a.publish_version AND " + anchorVisibility
                            + " ORDER BY a.published_at,a.id LIMIT 1",
                    (rs, n) -> Map.<String, Object>of("id", rs.getObject("id", UUID.class),
                            "cursor", encodeCursor(timestamp(rs, "published_at"), rs.getObject("id", UUID.class))),
                    anchorArgs.toArray());
            result.put("first_unread_id", unreadAnchor.isEmpty() ? null : unreadAnchor.getFirst().get("id"));
            result.put("first_unread_cursor", unreadAnchor.isEmpty() ? null : unreadAnchor.getFirst().get("cursor"));
        }
        return result;
    }

    /** Compatibility helper for internal callers; the HTTP API uses keyset cursors. */
    public List<Map<String, Object>> publicList(UUID siteId, int page, int size, String token) {
        int boundedPage = Math.max(0, Math.min(page, 100_000));
        Map<String, Object> result = publicList(siteId, null, size, token);
        for (int index = 0; index < boundedPage; index++) {
            Object cursor = result.get("next_cursor");
            if (!(cursor instanceof String value)) return List.of();
            result = publicList(siteId, value, size, token);
        }
        @SuppressWarnings("unchecked") List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("items");
        return rows;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary(UUID siteId, String token) {
        Identity identity = identity(token, false);
        List<Object> args = new ArrayList<>();
        String receipt = receiptJoin(identity, args);
        args.add(siteId);
        String visibility = visibilityClause(identity, args);
        Long unread = jdbc.queryForObject("SELECT count(*) FROM site_announcements a LEFT JOIN site_announcement_reads r "
                + "ON r.announcement_id=a.id AND " + receipt + " WHERE a.site_scope=" + SITE_SCOPE
                + " AND a.status='PUBLISHED' AND a.published_at IS NOT NULL AND coalesce(r.read_version,0)<a.publish_version AND " + visibility,
                Long.class, args.toArray());

        List<Object> latestArgs = new ArrayList<>();
        latestArgs.add(siteId);
        String latestVisibility = visibilityClause(identity, latestArgs);
        var latest = jdbc.query("SELECT a.id,a.title,left(a.body,160) AS body,a.published_at "
                        + "FROM site_announcements a WHERE a.site_scope=" + SITE_SCOPE
                        + " AND a.status='PUBLISHED' AND " + latestVisibility
                        + " ORDER BY a.published_at DESC NULLS LAST,a.id DESC LIMIT 1",
                (rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getObject("id", UUID.class)); row.put("title", rs.getString("title"));
                    row.put("body", rs.getString("body")); row.put("published_at", timestamp(rs, "published_at"));
                    return row;
                }, latestArgs.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("unread", unread == null ? 0L : unread);
        result.put("latest", latest.isEmpty() ? null : latest.getFirst());
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> publicDetail(UUID siteId, UUID id, String token) {
        Identity identity = identity(token, false);
        List<Object> args = new ArrayList<>();
        String receipt = receiptJoin(identity, args);
        args.add(id); args.add(siteId);
        String visibility = visibilityClause(identity, args);
        return jdbc.query("SELECT a.id,a.title,a.body,a.status,a.published_at,a.publish_version,"
                        + "(a.published_at IS NOT NULL AND coalesce(r.read_version,0)<a.publish_version) AS unread "
                        + "FROM site_announcements a LEFT JOIN site_announcement_reads r "
                        + "ON r.announcement_id=a.id AND " + receipt + " WHERE a.id=? AND a.site_scope=" + SITE_SCOPE
                        + " AND a.status='PUBLISHED' AND " + visibility,
                (rs, n) -> {
                    Map<String, Object> row = announcement(rs);
                    row.put("cursor", encodeCursor(timestamp(rs, "published_at"), rs.getObject("id", UUID.class)));
                    return row;
                }, args.toArray()).stream().findFirst()
                .orElseThrow(AnnouncementService::notFound);
    }

    public Map<String, Object> publicDetail(UUID siteId, UUID id) { return publicDetail(siteId, id, null); }

    /** Recheck a bounded set of already loaded notices against current publication and audience rules. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> refreshVisible(UUID siteId, List<UUID> requestedIds, String token) {
        List<UUID> ids = requestedIds == null ? List.of() : requestedIds.stream().distinct().toList();
        if (ids.size() > 100) throw bad("单次通知刷新不能超过 100 条");
        if (ids.isEmpty()) return List.of();
        Identity identity = identity(token, false);
        List<Object> args = new ArrayList<>();
        String receipt = receiptJoin(identity, args);
        args.add(arrayValue(ids)); args.add(siteId);
        String visibility = visibilityClause(identity, args);
        return jdbc.query("SELECT a.id,a.title,a.body,a.status,a.published_at,a.publish_version,"
                        + "(a.published_at IS NOT NULL AND coalesce(r.read_version,0)<a.publish_version) AS unread "
                        + "FROM site_announcements a LEFT JOIN site_announcement_reads r "
                        + "ON r.announcement_id=a.id AND " + receipt
                        + " WHERE a.id=ANY(?::uuid[]) AND a.site_scope=" + SITE_SCOPE
                        + " AND a.status='PUBLISHED' AND " + visibility
                        + " ORDER BY a.published_at DESC NULLS LAST,a.id DESC",
                (rs, n) -> {
                    Map<String, Object> row = announcement(rs);
                    row.put("cursor", encodeCursor(timestamp(rs, "published_at"), rs.getObject("id", UUID.class)));
                    return row;
                }, args.toArray());
    }

    @Transactional
    public void markRead(UUID siteId, UUID id, int version, String token) {
        Identity identity = identity(token, true);
        List<Object> args = new ArrayList<>(); args.add(id); args.add(siteId);
        String visibility = visibilityClause(identity, args);
        var rows = jdbc.query("SELECT a.publish_version FROM site_announcements a WHERE a.id=? AND a.site_scope="
                        + SITE_SCOPE + " AND a.status='PUBLISHED' AND " + visibility,
                (rs, n) -> rs.getInt(1), args.toArray());
        if (rows.isEmpty()) throw notFound();
        if (version < 1 || version > rows.getFirst()) throw bad("通知版本无效");
        upsertRead(identity, id, version);
    }

    /** Mark only the visible, published rows present in this statement snapshot. */
    @Transactional
    public List<Map<String, Object>> markAllVisibleRead(UUID siteId, String token) {
        Identity identity = identity(token, true);
        List<Object> args = new ArrayList<>();
        String ownerColumn;
        if (identity.userId() != null) { ownerColumn = "user_id"; args.add(identity.userId()); }
        else { ownerColumn = "guest_token_hash"; args.add(identity.guestHash()); }
        args.add(siteId);
        String visibility = visibilityClause(identity, args);
        return jdbc.query("""
                INSERT INTO site_announcement_reads(announcement_id, OWNER_COLUMN, read_version)
                SELECT a.id, ?, a.publish_version FROM site_announcements a
                WHERE a.site_scope=COALESCE(?::uuid,'00000000-0000-0000-0000-000000000000'::uuid)
                  AND a.status='PUBLISHED' AND """.replace("OWNER_COLUMN", ownerColumn) + visibility + "\n"
                + "ON CONFLICT (announcement_id," + ownerColumn + ") "
                + (identity.userId() != null ? "WHERE user_id IS NOT NULL " : "WHERE guest_token_hash IS NOT NULL ")
                + "DO UPDATE SET read_version=greatest(site_announcement_reads.read_version,excluded.read_version), "
                + "read_at=CASE WHEN excluded.read_version>site_announcement_reads.read_version THEN CURRENT_TIMESTAMP "
                + "ELSE site_announcement_reads.read_at END RETURNING announcement_id,read_version",
                (rs, n) -> Map.<String, Object>of("id", rs.getObject("announcement_id", UUID.class),
                        "version", rs.getInt("read_version")), args.toArray());
    }

    public UUID managementSite(boolean main) {
        var current = RequestContext.get();
        if (current == null || current.getUserId() == null) throw forbidden();
        if (main) {
            if (SiteRequestContext.getSiteId() != null || !Set.of("ADMIN", "SUPER_ADMIN").contains(current.getRole())) throw forbidden();
            return null;
        }
        return subsites.findByOwnerId(current.getUserId()).orElseThrow(AnnouncementService::forbidden).getId();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> managementData(UUID siteId) {
        List<Map<String, Object>> announcements = jdbc.query("SELECT id,title,body,status,published_at,publish_version,audience_type,audience_level_ids "
                        + "FROM site_announcements WHERE site_scope=" + SITE_SCOPE + " ORDER BY created_at DESC,id DESC",
                (rs, n) -> announcement(rs), siteId);
        List<Map<String, Object>> levels = jdbc.query("SELECT level.id,level.name FROM user_levels level "
                        + "WHERE level.enabled AND (?::uuid IS NULL OR EXISTS "
                        + "(SELECT 1 FROM subsite_allowed_levels allowed WHERE allowed.level_id=level.id)) "
                        + "ORDER BY level.sort_order,level.id",
                (rs, n) -> Map.of("id", rs.getObject("id", UUID.class), "name", rs.getString("name")), siteId);
        return Map.of("announcements", announcements, "levels", levels);
    }

    @Transactional
    public UUID save(UUID siteId, UUID id, Edit edit) {
        Normalized normalized = validate(edit);
        validateAudienceLevels(siteId, normalized.audienceType(), normalized.audienceLevelIds());
        byte[] fingerprint = fingerprint(normalized);
        if (id == null) {
            if (edit.requestKey() == null) throw bad("缺少通知请求标识");
            UUID newId = UUID.randomUUID();
            var references = mediaAssets == null ? null : mediaAssets.lockAnnouncement(normalized.body());
            validateAudienceMedia(normalized, references);
            int inserted = jdbc.update("""
                    INSERT INTO site_announcements(id,site_id,title,body,status,audience_type,audience_level_ids,create_key,create_fingerprint)
                    VALUES (?,?,?,?,'DRAFT',?,?::uuid[],?,?) ON CONFLICT DO NOTHING
                    """, newId, siteId, normalized.title(), normalized.body(), normalized.audienceType(),
                    arrayValue(normalized.audienceLevelIds()), edit.requestKey(), fingerprint);
            if (inserted == 0) {
                var prior = jdbc.query("SELECT id,create_fingerprint FROM site_announcements WHERE site_scope=" + SITE_SCOPE + " AND create_key=?",
                        (rs, n) -> Map.entry(rs.getObject("id", UUID.class), rs.getBytes("create_fingerprint")), siteId, edit.requestKey());
                if (prior.isEmpty() || !MessageDigest.isEqual(prior.getFirst().getValue(), fingerprint))
                    throw bad("请求标识已用于另一条通知");
                return prior.getFirst().getKey();
            }
            if (mediaAssets != null) mediaAssets.replaceReferences("ANNOUNCEMENT", newId, references, "body");
            if (edit.publish()) publishDraft(siteId, newId);
            return newId;
        }

        var references = mediaAssets == null ? null : mediaAssets.lockAnnouncement(id, normalized.body());
        validateAudienceMedia(normalized, references);
        if (references != null && !references.historicalAvatarPaths().isEmpty()) {
            var old = jdbc.query("SELECT body FROM site_announcements WHERE id=? AND site_scope=" + SITE_SCOPE + " FOR UPDATE",
                    (rs, n) -> rs.getString(1), id, siteId);
            if (old.isEmpty()) throw notFound();
            mediaAssets.verifyHistoricalAnnouncementAvatarReferences(old.getFirst(), references);
        }
        if (jdbc.update("""
                UPDATE site_announcements SET title=?,body=?,audience_type=?,audience_level_ids=?::uuid[],updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND site_scope=
                """ + SITE_SCOPE, normalized.title(), normalized.body(), normalized.audienceType(),
                arrayValue(normalized.audienceLevelIds()), id, siteId) != 1)
            throw notFound();
        if (mediaAssets != null) mediaAssets.replaceReferences("ANNOUNCEMENT", id, references, "body");
        if (edit.publish()) publishDraft(siteId, id);
        return id;
    }

    @Transactional
    public UUID create(UUID siteId, Edit edit) { return save(siteId, null, edit); }
    @Transactional
    public void edit(UUID siteId, UUID id, Edit edit) { save(siteId, id, edit); }
    @Transactional
    public void publish(UUID siteId, UUID id) { publishDraft(siteId, id); }

    public List<Map<String, Object>> managementList(UUID siteId) {
        @SuppressWarnings("unchecked") List<Map<String, Object>> result =
                (List<Map<String, Object>>) managementData(siteId).get("announcements");
        return result;
    }

    @Transactional
    public void withdraw(UUID siteId, UUID id) {
        int changed = jdbc.update("UPDATE site_announcements SET status='WITHDRAWN',updated_at=CURRENT_TIMESTAMP "
                + "WHERE id=? AND site_scope=" + SITE_SCOPE + " AND status='PUBLISHED'", id, siteId);
        if (changed == 0) {
            var rows = jdbc.query("SELECT status FROM site_announcements WHERE id=? AND site_scope=" + SITE_SCOPE,
                    (rs, n) -> rs.getString(1), id, siteId);
            if (rows.isEmpty()) throw notFound();
            if (!"WITHDRAWN".equals(rows.getFirst())) throw bad("只有已发布通知可以撤下");
        }
    }

    private void publishDraft(UUID siteId, UUID id) {
        var rows = jdbc.query("SELECT status FROM site_announcements WHERE id=? AND site_scope=" + SITE_SCOPE + " FOR UPDATE",
                (rs, n) -> rs.getString(1), id, siteId);
        if (rows.isEmpty()) throw notFound();
        String status = rows.getFirst();
        if ("PUBLISHED".equals(status)) return;
        if (!"DRAFT".equals(status)) throw bad("已撤下通知请新建后发布");
        jdbc.update("UPDATE site_announcements SET status='PUBLISHED',publish_version=publish_version+1,"
                + "published_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=? AND site_scope=" + SITE_SCOPE, id, siteId);
    }

    private void validateAudienceLevels(UUID siteId, String type, List<UUID> ids) {
        if (!"LEVELS".equals(type)) return;
        Long valid = jdbc.queryForObject("SELECT count(*) FROM user_levels level WHERE level.enabled AND level.id=ANY(?::uuid[]) "
                        + "AND (?::uuid IS NULL OR EXISTS (SELECT 1 FROM subsite_allowed_levels allowed WHERE allowed.level_id=level.id))",
                Long.class, arrayValue(ids), siteId);
        if (valid == null || valid != ids.size()) throw bad("所选通知等级无效或已停用");
    }

    private void validateAudienceMedia(Normalized normalized, MediaAssetRegistry.LockedReferences references) {
        if (!"LEVELS".equals(normalized.audienceType())) return;
        if (managedMedia == null) {
            if (normalized.body().contains("/api/uploads/")) throw bad("等级通知不能引用公开上传图片");
            return;
        }
        Set<String> paths = managedMedia.markdown(normalized.body());
        if (paths.isEmpty()) return;
        if (references == null) throw bad("等级通知不能引用公开上传图片");
        for (String path : paths) {
            Boolean isPublicProductAsset = jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM media_assets asset JOIN media_references product_ref
                      ON product_ref.asset_id=asset.id AND product_ref.owner_type='PRODUCT'
                    WHERE ? IN (asset.storage_path,asset.display_path,asset.thumbnail_path,asset.clear_path)
                      AND asset.processing_status='READY' AND asset.deleted_at IS NULL)
                    """, Boolean.class, path);
            if (!Boolean.TRUE.equals(isPublicProductAsset))
                throw bad("等级通知的图片必须来自商品素材；新上传图片是公开资源，请改用站外图片链接");
        }
    }

    private static String visibilityClause(Identity identity, List<Object> args) {
        if (identity == null || identity.userId() == null) return "a.audience_type='ALL'";
        args.add(identity.userId());
        return "(a.audience_type='ALL' OR (a.audience_type='LEVELS' AND EXISTS ("
                + "SELECT 1 FROM users audience_user JOIN user_levels audience_level ON audience_level.id=audience_user.user_level_id "
                + "WHERE audience_user.id=? AND audience_level.enabled AND audience_level.id=ANY(a.audience_level_ids))))";
    }

    private static String receiptJoin(Identity identity, List<Object> args) {
        if (identity == null) return "false";
        if (identity.userId() != null) { args.add(identity.userId()); return "r.user_id=?"; }
        args.add(identity.guestHash()); return "r.guest_token_hash=?";
    }

    private static String cursorClause(String encoded, List<Object> args) {
        if (encoded == null || encoded.isBlank()) return "";
        Cursor cursor = decodeCursor(encoded);
        if (cursor.publishedAt() == null) {
            args.add(cursor.id());
            return " AND a.published_at IS NULL AND a.id<? ";
        }
        args.add(cursor.publishedAt()); args.add(cursor.publishedAt()); args.add(cursor.id());
        return " AND (a.published_at<? OR (a.published_at=? AND a.id<?) OR a.published_at IS NULL) ";
    }

    private void upsertRead(Identity identity, UUID id, int version) {
        if (identity.userId() != null) {
            jdbc.update("""
                    INSERT INTO site_announcement_reads(announcement_id,user_id,read_version) VALUES (?,?,?)
                    ON CONFLICT (announcement_id,user_id) WHERE user_id IS NOT NULL
                    DO UPDATE SET read_version=greatest(site_announcement_reads.read_version,excluded.read_version),
                      read_at=CASE WHEN excluded.read_version>site_announcement_reads.read_version THEN CURRENT_TIMESTAMP ELSE site_announcement_reads.read_at END
                    """, id, identity.userId(), version);
        } else {
            jdbc.update("""
                    INSERT INTO site_announcement_reads(announcement_id,guest_token_hash,read_version) VALUES (?,?,?)
                    ON CONFLICT (announcement_id,guest_token_hash) WHERE guest_token_hash IS NOT NULL
                    DO UPDATE SET read_version=greatest(site_announcement_reads.read_version,excluded.read_version),
                      read_at=CASE WHEN excluded.read_version>site_announcement_reads.read_version THEN CURRENT_TIMESTAMP ELSE site_announcement_reads.read_at END
                    """, id, identity.guestHash(), version);
        }
    }

    private static Map<String, Object> announcement(ResultSet rs) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", rs.getObject("id", UUID.class)); result.put("title", rs.getString("title"));
        result.put("body", rs.getString("body")); result.put("status", rs.getString("status"));
        result.put("published_at", timestamp(rs, "published_at")); result.put("publish_version", rs.getInt("publish_version"));
        try { result.put("unread", rs.getBoolean("unread")); } catch (SQLException ignored) { /* Not requested for management/detail. */ }
        try {
            result.put("audience_type", rs.getString("audience_type"));
            Array value = rs.getArray("audience_level_ids");
            List<UUID> ids = new ArrayList<>();
            if (value != null) for (Object raw : (Object[]) value.getArray()) ids.add(raw instanceof UUID uuid ? uuid : UUID.fromString(raw.toString()));
            result.put("audience_level_ids", ids);
        } catch (SQLException ignored) { /* Public responses intentionally omit audience membership. */ }
        return result;
    }

    private static LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private record Normalized(UUID requestKey, String title, String body, String audienceType,
                              List<UUID> audienceLevelIds, boolean publish) {}

    private static Normalized validate(Edit edit) {
        if (edit == null || edit.body() == null || edit.body().isBlank() || edit.body().length() > 100_000)
            throw bad("通知正文无效");
        String title = edit.title() == null || edit.title().isBlank() ? null : edit.title().trim();
        if (title != null && title.length() > 200) throw bad("通知标题不能超过 200 个字符");
        String type = edit.audienceType() == null ? "ALL" : edit.audienceType().trim().toUpperCase();
        List<UUID> ids = edit.audienceLevelIds() == null ? List.of() : new ArrayList<>(edit.audienceLevelIds());
        if (!Set.of("ALL", "LEVELS").contains(type)) throw bad("通知范围无效");
        if (ids.stream().anyMatch(java.util.Objects::isNull) || new HashSet<>(ids).size() != ids.size()) throw bad("通知等级无效");
        if (("ALL".equals(type) && !ids.isEmpty()) || ("LEVELS".equals(type) && ids.isEmpty())) throw bad("指定等级通知不能为空");
        ids = ids.stream().sorted(Comparator.comparing(UUID::toString)).toList();
        return new Normalized(edit.requestKey(), title, edit.body(), type, ids, edit.publish());
    }

    private static byte[] fingerprint(Normalized edit) {
        try {
            String value = String.join("\u0000", edit.title() == null ? "" : edit.title(), edit.body(), edit.audienceType(),
                    String.join(",", edit.audienceLevelIds().stream().map(UUID::toString).toList()), Boolean.toString(edit.publish()));
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String arrayValue(List<UUID> ids) { return "{" + String.join(",", ids.stream().map(UUID::toString).toList()) + "}"; }

    private static String encodeCursor(LocalDateTime publishedAt, UUID id) {
        String value = (publishedAt == null ? "-" : publishedAt.toString()) + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decodeCursor(String encoded) {
        try {
            String[] fields = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8).split("\\|", -1);
            if (fields.length != 2) throw new IllegalArgumentException();
            return new Cursor("-".equals(fields[0]) ? null : LocalDateTime.parse(fields[0]), UUID.fromString(fields[1]));
        } catch (RuntimeException error) { throw bad("通知游标无效"); }
    }

    private static Identity identity(String token, boolean required) {
        UUID user = RequestContext.getUserId();
        if (user != null) return new Identity(user, null);
        String value = token == null ? "" : token.trim();
        if (value.isEmpty() || value.length() > 255) {
            if (required) throw forbidden();
            return null;
        }
        try { return new Identity(null, MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static BusinessException bad(String message) { return new BusinessException(ErrorCode.BAD_REQUEST, message); }
    private static BusinessException forbidden() { return new BusinessException(ErrorCode.FORBIDDEN, "无权管理或访问此通知"); }
    private static BusinessException notFound() { return new BusinessException(ErrorCode.NOT_FOUND, "通知不存在或当前身份不可见"); }
}
