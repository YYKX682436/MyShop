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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Announcement bodies live in one table; channels only select published records. */
@Service
@RequiredArgsConstructor
public class AnnouncementService {
    private final JdbcTemplate jdbc;
    private final SubsiteRepository subsites;
    private MediaAssetRegistry mediaAssets;
    @org.springframework.beans.factory.annotation.Autowired
    public void setMediaAssets(MediaAssetRegistry mediaAssets) { this.mediaAssets = mediaAssets; }

    private static final String SITE_SCOPE = "coalesce(?::uuid, '00000000-0000-0000-0000-000000000000'::uuid)";

    public record Edit(String title, String body) {}
    public record Channel(UUID announcementId, boolean enabled) {}

    @Transactional(readOnly = true)
    public Map<String, Object> channels(UUID siteId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("announcement_enabled", false);
        result.put("popup_enabled", false);
        result.put("announcement_site_id", siteId == null ? "main" : siteId.toString());
        jdbc.query("""
                SELECT c.channel,a.id,a.title,a.body,a.publish_version
                FROM site_announcement_channels c JOIN site_announcements a
                  ON a.id=c.announcement_id AND a.site_scope=c.site_scope
                WHERE c.site_scope=""" + SITE_SCOPE + """
                  AND c.enabled AND a.status='PUBLISHED'
                """, rs -> {
            boolean banner = "BANNER".equals(rs.getString("channel"));
            String prefix = banner ? "announcement" : "popup";
            result.put(prefix + "_enabled", true);
            result.put(prefix + "_id", rs.getObject("id", UUID.class));
            result.put(prefix + "_title", rs.getString("title"));
            result.put(prefix + "_version", rs.getInt("publish_version"));
            result.put(banner ? "announcement" : "popup_content", rs.getString("body"));
        }, siteId);
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> publicList(UUID siteId, int page, int size, String token) {
        int boundedPage = Math.max(0, Math.min(page, 100_000));
        int boundedSize = Math.max(1, Math.min(size, 50));
        Identity identity = identity(token, false);
        String receiptCondition = identity == null ? "false" : identity.userId() != null
                ? "r.user_id=?" : "r.guest_token_hash=?";
        String sql = """
                SELECT a.id,a.title,a.body,a.status,a.published_at,a.publish_version,
                       (a.published_at IS NOT NULL AND coalesce(r.read_version,0)<a.publish_version) AS unread
                FROM site_announcements a LEFT JOIN site_announcement_reads r
                  ON r.announcement_id=a.id AND
                """ + receiptCondition + "\n" + """
                WHERE a.site_scope=""" + SITE_SCOPE + """
                  AND a.status='PUBLISHED'
                ORDER BY a.published_at DESC NULLS LAST,a.id DESC LIMIT ? OFFSET ?
                """;
        Object[] args = identity == null
                ? new Object[]{siteId, boundedSize, boundedPage * boundedSize}
                : new Object[]{identity.userId() != null ? identity.userId() : identity.guestHash(), siteId,
                               boundedSize, boundedPage * boundedSize};
        return jdbc.query(sql, (rs, n) -> announcement(rs), args);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary(UUID siteId, String token) {
        Identity identity = identity(token, false);
        String receipt = identity == null ? "false" : identity.userId() != null ? "r.user_id=?" : "r.guest_token_hash=?";
        String sql = "SELECT count(*) FROM site_announcements a LEFT JOIN site_announcement_reads r ON r.announcement_id=a.id AND "
                + receipt + " WHERE a.site_scope=" + SITE_SCOPE
                + " AND a.status='PUBLISHED' AND a.published_at IS NOT NULL AND coalesce(r.read_version,0)<a.publish_version";
        Object[] args = identity == null ? new Object[]{siteId}
                : new Object[]{identity.userId() != null ? identity.userId() : identity.guestHash(), siteId};
        Long unread = jdbc.queryForObject(sql, Long.class, args);
        var latest = jdbc.query("SELECT id,title,left(body,120) AS body,published_at FROM site_announcements WHERE site_scope="
                        + SITE_SCOPE + " AND status='PUBLISHED' ORDER BY published_at DESC NULLS LAST,id DESC LIMIT 1",
                (rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getObject("id", UUID.class)); row.put("title", rs.getString("title"));
                    row.put("body", rs.getString("body"));
                    var time = rs.getTimestamp("published_at");
                    row.put("published_at", time == null ? null : time.toLocalDateTime());
                    return row;
                }, siteId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("unread", unread); result.put("latest", latest.isEmpty() ? null : latest.getFirst());
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> publicDetail(UUID siteId, UUID id) {
        return jdbc.query("SELECT id,title,body,status,published_at,publish_version FROM site_announcements WHERE id=? AND site_scope="
                        + SITE_SCOPE + " AND status='PUBLISHED'",
                (rs, n) -> announcement(rs), id, siteId).stream().findFirst().orElseThrow(AnnouncementService::notFound);
    }

    @Transactional
    public void markRead(UUID siteId, UUID id, int version, String token) {
        Identity identity = identity(token, true);
        var rows = jdbc.query("SELECT publish_version FROM site_announcements WHERE id=? AND site_scope="
                        + SITE_SCOPE + " AND status='PUBLISHED'",
                (rs, n) -> rs.getInt(1), id, siteId);
        if (rows.isEmpty()) throw notFound();
        if (version < 1 || version > rows.getFirst()) throw bad("公告版本无效");
        if (identity.userId() != null) {
            jdbc.update("""
                    INSERT INTO site_announcement_reads(announcement_id,user_id,read_version) VALUES (?,?,?)
                    ON CONFLICT (announcement_id,user_id) WHERE user_id IS NOT NULL
                    DO UPDATE SET read_version=greatest(site_announcement_reads.read_version,excluded.read_version),
                      read_at=CASE WHEN excluded.read_version>site_announcement_reads.read_version
                        THEN CURRENT_TIMESTAMP ELSE site_announcement_reads.read_at END
                    """, id, identity.userId(), version);
        } else {
            jdbc.update("""
                    INSERT INTO site_announcement_reads(announcement_id,guest_token_hash,read_version) VALUES (?,?,?)
                    ON CONFLICT (announcement_id,guest_token_hash) WHERE guest_token_hash IS NOT NULL
                    DO UPDATE SET read_version=greatest(site_announcement_reads.read_version,excluded.read_version),
                      read_at=CASE WHEN excluded.read_version>site_announcement_reads.read_version
                        THEN CURRENT_TIMESTAMP ELSE site_announcement_reads.read_at END
                    """, id, identity.guestHash(), version);
        }
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
    public List<Map<String, Object>> managementList(UUID siteId) {
        return jdbc.query("SELECT id,title,body,status,published_at,publish_version FROM site_announcements WHERE site_scope="
                        + SITE_SCOPE + " ORDER BY created_at DESC,id DESC",
                (rs, n) -> announcement(rs), siteId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> managementChannels(UUID siteId) {
        return jdbc.queryForList("SELECT channel,announcement_id,enabled FROM site_announcement_channels WHERE site_scope=" + SITE_SCOPE, siteId);
    }

    @Transactional
    public UUID create(UUID siteId, Edit edit) {
        validate(edit);
        var references = mediaAssets == null ? null : mediaAssets.lockAnnouncement(edit.body());
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO site_announcements(id,site_id,title,body,status) VALUES (?,?,?,?,'DRAFT')",
                id, siteId, edit.title().trim(), edit.body());
        if (mediaAssets != null) mediaAssets.replaceReferences("ANNOUNCEMENT", id, references, "body");
        return id;
    }

    @Transactional
    public void edit(UUID siteId, UUID id, Edit edit) {
        validate(edit);
        var references = mediaAssets == null ? null : mediaAssets.lockAnnouncement(id, edit.body());
        if (references != null && !references.historicalAvatarPaths().isEmpty()) {
            var old = jdbc.query("SELECT body FROM site_announcements WHERE id=? AND site_scope=" + SITE_SCOPE + " FOR UPDATE",
                    (rs, n) -> rs.getString(1), id, siteId);
            if (old.isEmpty()) throw notFound();
            mediaAssets.verifyHistoricalAnnouncementAvatarReferences(old.getFirst(), references);
        }
        if (jdbc.update("UPDATE site_announcements SET title=?,body=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND site_scope=" + SITE_SCOPE,
                edit.title().trim(), edit.body(), id, siteId) != 1) throw notFound();
        if (mediaAssets != null) mediaAssets.replaceReferences("ANNOUNCEMENT", id, references, "body");
    }

    /** Editing does not increment this version; this explicit action does. */
    @Transactional
    public void publish(UUID siteId, UUID id) {
        if (jdbc.update("UPDATE site_announcements SET status='PUBLISHED',publish_version=publish_version+1,published_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=? AND site_scope=" + SITE_SCOPE,
                id, siteId) != 1) throw notFound();
    }

    @Transactional
    public void withdraw(UUID siteId, UUID id) {
        if (jdbc.update("UPDATE site_announcements SET status='WITHDRAWN',updated_at=CURRENT_TIMESTAMP WHERE id=? AND site_scope=" + SITE_SCOPE + " AND status='PUBLISHED'",
                id, siteId) != 1) throw notFound();
    }

    @Transactional
    public void setChannel(UUID siteId, String channel, Channel setting) {
        if (!Set.of("BANNER", "POPUP").contains(channel) || setting == null) throw bad("展示渠道无效");
        if (setting.enabled() && setting.announcementId() == null) throw bad("请选择已发布公告");
        if (setting.announcementId() != null) {
            var rows = jdbc.query("SELECT status FROM site_announcements WHERE id=? AND site_scope=" + SITE_SCOPE + " FOR UPDATE",
                    (rs, n) -> rs.getString(1), setting.announcementId(), siteId);
            if (rows.isEmpty()) throw notFound();
            if (setting.enabled() && !"PUBLISHED".equals(rows.getFirst())) throw bad("请选择已发布公告");
        }
        jdbc.update("""
                INSERT INTO site_announcement_channels(site_id,channel,announcement_id,enabled) VALUES (?,?,?,?)
                ON CONFLICT (site_scope,channel) DO UPDATE SET announcement_id=excluded.announcement_id,enabled=excluded.enabled
                """, siteId, channel, setting.announcementId(), setting.enabled());
    }

    private static Map<String, Object> announcement(ResultSet rs) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", rs.getObject("id", UUID.class));
        result.put("title", rs.getString("title"));
        result.put("body", rs.getString("body"));
        result.put("status", rs.getString("status"));
        var published = rs.getTimestamp("published_at");
        result.put("published_at", published == null ? null : published.toLocalDateTime());
        result.put("publish_version", rs.getInt("publish_version"));
        try { result.put("unread", rs.getBoolean("unread")); } catch (SQLException ignored) { /* Detail/management do not request it. */ }
        return result;
    }

    private static void validate(Edit edit) {
        if (edit == null || edit.title() == null || edit.title().isBlank() || edit.title().trim().length() > 200
                || edit.body() == null || edit.body().isBlank() || edit.body().length() > 100_000) throw bad("公告标题或正文无效");
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

    private record Identity(UUID userId, byte[] guestHash) {}
    private static BusinessException bad(String message) { return new BusinessException(ErrorCode.BAD_REQUEST, message); }
    private static BusinessException forbidden() { return new BusinessException(ErrorCode.FORBIDDEN, "无权管理或访问此公告"); }
    private static BusinessException notFound() { return new BusinessException(ErrorCode.NOT_FOUND, "公告不存在"); }
}
