package com.myshop.service;

import com.myshop.constant.ErrorCode;
import com.myshop.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Deletion locks one asset, then only the current owner rows which reference it. */
@Service
public class MediaMaintenanceService {
    private final UploadStorage storage;
    private final JdbcTemplate jdbc;
    private final ManagedMediaReferences paths;
    private final TransactionTemplate transactions;

    public MediaMaintenanceService(UploadStorage storage, JdbcTemplate jdbc, ManagedMediaReferences paths,
                                   org.springframework.transaction.PlatformTransactionManager manager) {
        this.storage = storage;
        this.jdbc = jdbc;
        this.paths = paths;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Inspection inspect(String identifier) {
        Asset asset = find(identifier, false);
        return new Inspection(asset.size(), format(asset.display() != null ? asset.display() : asset.storage()),
                asset.width(), asset.height(), references(asset.id()));
    }

    /** Rewrites only current owner fields while the shared asset row is already locked by its updater. */
    public void rewriteLegacyImageReferences(UUID assetId, List<String> oldPaths, String newDisplayPath) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Legacy image references require the asset transaction");
        if (assetId == null || oldPaths == null || oldPaths.isEmpty() || newDisplayPath == null)
            throw new IllegalArgumentException("Legacy image replacement is incomplete");
        String newUrl = paths.url(newDisplayPath);
        for (Reference ref : references(assetId)) replaceReference(assetId, ref, oldPaths, newUrl);
    }

    public void delete(String identifier) {
        Asset asset = transactions.execute(status -> {
            Asset locked = find(identifier, true);
            if ("VIDEO".equals(locked.type()) && !"READY".equals(locked.status()))
                throw new BusinessException(ErrorCode.BAD_REQUEST, "请先通过视频上传会话取消并清理临时文件", HttpStatus.CONFLICT);
            List<Reference> refs = references(locked.id());
            for (Reference ref : refs) clearReference(ref, locked.paths());
            jdbc.update("DELETE FROM media_references WHERE asset_id=?", locked.id());
            // Persist the pending cleanup before committing. A crash immediately
            // after the business transaction must leave a visible retry entry.
            jdbc.update("UPDATE media_assets SET deleted_at=coalesce(deleted_at,CURRENT_TIMESTAMP),cleanup_error='资源文件待清理' WHERE id=?", locked.id());
            return locked;
        });
        if (asset == null) throw new IllegalStateException("Media deletion transaction returned no asset");
        cleanup(asset);
    }

    /** Called after an avatar change commits; account avatars cannot be shared with product assets. */
    public void retireUnreferencedAvatar(String url, UUID owner) {
        String path = paths.managedPath(url);
        if (path == null || owner == null) return;
        Asset asset = transactions.execute(status -> {
            var rows = jdbc.query("""
                    SELECT id,storage_path,display_path,thumbnail_path,clear_path,size_bytes,width,height,media_type,processing_status FROM media_assets a
                    WHERE a.scope='AVATAR' AND a.deleted_at IS NULL
                      AND (a.storage_path=? OR a.display_path=? OR a.thumbnail_path=? OR a.clear_path=?) FOR UPDATE
                    """, (rs, n) -> new Asset(rs.getObject("id", UUID.class), rs.getString("storage_path"), rs.getString("display_path"),
                    rs.getString("thumbnail_path"), rs.getString("clear_path"), rs.getLong("size_bytes"),
                    rs.getObject("width", Integer.class), rs.getObject("height", Integer.class), rs.getString("media_type"),
                    rs.getString("processing_status")), path, path, path, path);
            if (rows.size() != 1 || Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM media_references WHERE asset_id=?)", Boolean.class, rows.getFirst().id()))) return null;
            Asset result = rows.getFirst();
            jdbc.update("UPDATE media_assets SET deleted_at=CURRENT_TIMESTAMP,cleanup_error='头像文件待清理' WHERE id=?", result.id());
            return result;
        });
        if (asset != null) cleanup(asset);
    }

    private void cleanup(Asset asset) {
        List<String> failures = new ArrayList<>();
        for (String path : asset.paths()) {
            try {
                var file = storage.resolveManagedPath(path);
                if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !storage.deleteManagedFile(path)) failures.add(path);
            } catch (RuntimeException exception) { failures.add(path); }
        }
        if (!failures.isEmpty()) {
            transactions.executeWithoutResult(status -> jdbc.update("UPDATE media_assets SET cleanup_error=? WHERE id=?",
                    "文件清理失败，请重试：" + String.join(", ", failures), asset.id()));
            throw new BusinessException(ErrorCode.SERVER_ERROR, "资源引用已清理，但文件删除失败；请重试清理", HttpStatus.CONFLICT);
        }
        transactions.executeWithoutResult(status -> jdbc.update("UPDATE media_assets SET cleanup_error=NULL WHERE id=?", asset.id()));
    }

    private Asset find(String identifier, boolean lock) {
        if (identifier == null || !identifier.matches("[a-zA-Z0-9._/-]{1,512}") || identifier.contains(".."))
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的资源名称");
        String suffix = "%/" + identifier;
        List<Asset> found = jdbc.query("""
                SELECT id,storage_path,display_path,thumbnail_path,clear_path,size_bytes,width,height,media_type,processing_status FROM media_assets
                WHERE id::text=? OR storage_path=? OR display_path=? OR thumbnail_path=? OR clear_path=?
                   OR storage_path LIKE ? OR display_path LIKE ? OR thumbnail_path LIKE ? OR clear_path LIKE ?
                ORDER BY id
                """ + (lock ? " FOR UPDATE" : ""), (rs, n) -> new Asset(rs.getObject("id", UUID.class), rs.getString("storage_path"),
                rs.getString("display_path"), rs.getString("thumbnail_path"), rs.getString("clear_path"), rs.getLong("size_bytes"),
                rs.getObject("width", Integer.class), rs.getObject("height", Integer.class), rs.getString("media_type"),
                rs.getString("processing_status")),
                identifier, identifier, identifier, identifier, identifier, suffix, suffix, suffix, suffix);
        if (found.size() != 1) throw new BusinessException(ErrorCode.NOT_FOUND, "资源不存在或名称不唯一", HttpStatus.NOT_FOUND);
        return found.getFirst();
    }

    private List<Reference> references(UUID assetId) {
        List<Reference> result = jdbc.query("""
                SELECT owner_type,owner_id,field FROM media_references WHERE asset_id=?
                """, (rs, n) -> {
            String type = rs.getString(1);
            return new Reference(table(type), label(type), 1, rs.getObject(2, UUID.class), rs.getString(3), type);
        }, assetId);
        // Checkout locks subsite -> product -> user; supplier transactions
        // sort product UUIDs with Java's UUID ordering. Match both here so a
        // multi-owner deletion cannot invert unrelated business row locks.
        result.sort(java.util.Comparator.comparingInt((Reference ref) -> ownerOrder(ref.ownerType()))
                .thenComparing(Reference::ownerId).thenComparing(Reference::field));
        return result;
    }

    private static int ownerOrder(String type) {
        return switch (type) { case "SUBSITE" -> 0; case "PRODUCT" -> 1; case "AVATAR" -> 2;
            case "SITE_CONFIG" -> 3; case "ANNOUNCEMENT" -> 4; default -> throw new IllegalStateException("Unexpected media owner"); };
    }

    private void clearReference(Reference ref, List<String> aliases) {
        String table = table(ref.ownerType());
        String column = switch (ref.ownerType() + ":" + ref.field()) {
            case "PRODUCT:cover_url", "PRODUCT:detail_md", "AVATAR:avatar_url", "SUBSITE:favicon_url", "ANNOUNCEMENT:body" -> ref.field();
            case "SITE_CONFIG:favicon_url" -> "config_value";
            default -> throw new IllegalStateException("Unexpected media reference field");
        };
        List<String> rows = jdbc.query("SELECT " + column + " FROM " + table + " WHERE id=? FOR UPDATE",
                (rs, n) -> rs.getString(1), ref.ownerId());
        if (rows.isEmpty() || rows.getFirst() == null) return;
        String before = rows.getFirst(), after = before;
        for (String alias : aliases) {
            if (ref.field().equals("body") || ref.field().equals("detail_md"))
                after = MarkdownMediaCleaner.removeManaged(after, alias, paths);
            else if (paths.direct(after).contains(alias)) after = ref.ownerType().equals("SITE_CONFIG") ? "" : null;
            if (after == null) break;
        }
        if (ref.ownerType().equals("ANNOUNCEMENT") && (after == null || after.isBlank())) after = "（资源已删除）";
        if (!java.util.Objects.equals(before, after)) {
            jdbc.update("UPDATE " + table + " SET " + column + "=? WHERE id=?", after, ref.ownerId());
            if (ref.field().equals("body") || ref.field().equals("detail_md")) {
                List<String> removedAvatars = paths.pruneRemovedMarkdownReferences(ref.ownerType(), ref.ownerId(), ref.field(), after)
                        .stream().filter(path -> !aliases.contains(path)).toList();
                if (!removedAvatars.isEmpty()) org.springframework.transaction.support.TransactionSynchronizationManager
                        .registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override public void afterCommit() {
                                for (String path : removedAvatars) try { retireUnreferencedAvatar(paths.url(path), ref.ownerId()); }
                                catch (RuntimeException failure) { org.slf4j.LoggerFactory.getLogger(MediaMaintenanceService.class)
                                        .warn("Historical avatar cleanup needs retry: {}", failure.getMessage()); }
                            }
                        });
            }
        }
    }

    private void replaceReference(UUID assetId, Reference ref, List<String> aliases, String newUrl) {
        String table = table(ref.ownerType());
        String column = switch (ref.ownerType() + ":" + ref.field()) {
            case "PRODUCT:cover_url", "PRODUCT:detail_md", "AVATAR:avatar_url", "SUBSITE:favicon_url", "ANNOUNCEMENT:body" -> ref.field();
            case "SITE_CONFIG:favicon_url" -> "config_value";
            default -> throw new IllegalStateException("Unexpected media reference field");
        };
        List<String> rows = jdbc.query("SELECT " + column + " FROM " + table + " WHERE id=? FOR UPDATE",
                (rs, n) -> rs.getString(1), ref.ownerId());
        if (rows.isEmpty()) {
            jdbc.update("DELETE FROM media_references WHERE asset_id=? AND owner_type=? AND owner_id=? AND field=?",
                    assetId, ref.ownerType(), ref.ownerId(), ref.field());
            return;
        }
        String before = rows.getFirst();
        if (before == null) {
            jdbc.update("DELETE FROM media_references WHERE asset_id=? AND owner_type=? AND owner_id=? AND field=?",
                    assetId, ref.ownerType(), ref.ownerId(), ref.field());
            return;
        }
        String after;
        if (ref.field().equals("body") || ref.field().equals("detail_md")) {
            after = MarkdownMediaCleaner.replaceManaged(before, aliases, newUrl, paths);
        } else {
            String currentPath = paths.managedPath(before);
            after = aliases.contains(currentPath) ? newUrl : before;
        }
        if (java.util.Objects.equals(before, after))
            throw new IllegalStateException("Current media reference does not match its asset path");
        jdbc.update("UPDATE " + table + " SET " + column + "=? WHERE id=?", after, ref.ownerId());
    }

    private static String table(String type) {
        return switch (type) { case "PRODUCT" -> "products"; case "AVATAR" -> "users"; case "SITE_CONFIG" -> "site_configs";
            case "SUBSITE" -> "subsites"; case "ANNOUNCEMENT" -> "site_announcements"; default -> throw new IllegalStateException("Unexpected media owner"); };
    }
    private static String label(String type) {
        return switch (type) { case "PRODUCT" -> "商品"; case "AVATAR" -> "用户头像"; case "SITE_CONFIG" -> "主站图标";
            case "SUBSITE" -> "分站图标"; case "ANNOUNCEMENT" -> "公告"; default -> "其他"; };
    }
    private static String format(String path) {
        return path == null || !path.contains(".") ? "" : path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
    private record Asset(UUID id, String storage, String display, String thumbnail, String clear, long size, Integer width, Integer height,
                         String type, String status) {
        List<String> paths() {
            var result = new LinkedHashSet<String>();
            for (String path : new String[]{storage, display, thumbnail, clear}) if (path != null) result.add(path);
            return List.copyOf(result);
        }
    }
    public record Reference(String table, String source, long count, UUID ownerId, String field, String ownerType) { }
    public record Inspection(long size, String format, Integer width, Integer height, List<Reference> references) { }
}
