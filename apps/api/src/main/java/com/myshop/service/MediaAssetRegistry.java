package com.myshop.service;

import com.myshop.constant.ErrorCode;
import com.myshop.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;
import java.io.IOException;

/** Persistent identity and processing state for managed public media. */
@Service
public class MediaAssetRegistry {
    private final JdbcTemplate jdbc;
    private final UploadStorage storage;
    private final ManagedMediaReferences paths;
    private MediaMaintenanceService maintenance;

    public MediaAssetRegistry(JdbcTemplate jdbc, UploadStorage storage, ManagedMediaReferences paths) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.paths = paths;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setMaintenance(MediaMaintenanceService maintenance) { this.maintenance = maintenance; }

    public record VideoSession(UUID id, UUID uploaderId, String uploadUri, String status,
                               String originalName, long declaredSize, String processingError, UUID reusedAssetId) {
        public VideoSession(UUID id, UUID uploaderId, String uploadUri, String status,
                            String originalName, long declaredSize, String processingError) {
            this(id, uploaderId, uploadUri, status, originalName, declaredSize, processingError, null);
        }
    }
    public record VideoReuse(UUID assetId, String storagePath) { }
    public record VideoCompletion(UUID assetId, String storagePath, boolean reused) { }

    @Transactional(readOnly = true)
    public Optional<VideoReuse> findReadyVideoForReuse(UUID assetId) {
        if (assetId == null) return Optional.empty();
        return jdbc.query("""
                SELECT id,storage_path FROM media_assets WHERE id=? AND media_type='VIDEO' AND scope='SHARED'
                  AND processing_status='READY' AND deleted_at IS NULL
                """, (rs, n) -> new VideoReuse(rs.getObject(1, UUID.class), rs.getString(2)), assetId).stream()
                .filter(value -> value.storagePath() != null && storage.isSafePublicFile(value.storagePath())).findFirst();
    }

    /** Asset rows are locked in UUID order before the caller locks a business row. */
    public record LockedReferences(Map<String, Set<UUID>> fields, Map<String, Map<String, Integer>> historicalAvatarPaths) {
        public LockedReferences(Map<String, Set<UUID>> fields) { this(fields, Map.of()); }
    }

    public record ImageRegistration(UUID id, String displayPath, String thumbnailPath, String clearPath,
                                    long originalSize, long displaySize, boolean reused, String processingReason) {
        public ImageRegistration(UUID id, String displayPath, String thumbnailPath, String clearPath,
                                 long originalSize, long displaySize, boolean reused) {
            this(id, displayPath, thumbnailPath, clearPath, originalSize, displaySize, reused, null);
        }
    }
    private record ExistingImage(UUID id, String storagePath, String displayPath, String thumbnailPath, String clearPath,
                                 long originalSize, long displaySize, String processingReason) {
        ImageRegistration registration() { return new ImageRegistration(id, displayPath, thumbnailPath, clearPath, originalSize, displaySize, true, processingReason); }
    }

    @Transactional
    public Optional<ImageRegistration> findReusableImage(String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) return Optional.empty();
        return lockedImages(hash).stream().filter(this::completeImage).map(ExistingImage::registration).findFirst();
    }

    private List<ExistingImage> lockedImages(String hash) {
        return jdbc.query("""
                SELECT id,storage_path,display_path,thumbnail_path,clear_path,original_size_bytes,size_bytes,processing_reason FROM media_assets
                WHERE media_type='IMAGE' AND scope='SHARED' AND content_hash=? AND processing_status='READY' AND deleted_at IS NULL
                ORDER BY id FOR UPDATE
                """, (rs, n) -> new ExistingImage(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getLong(6), rs.getLong(7), rs.getString(8)), hash);
    }

    private boolean completeImage(ExistingImage image) {
        return image.displayPath() != null && storage.isSafePublicFile(image.displayPath())
                && image.thumbnailPath() != null && storage.isSafePublicFile(image.thumbnailPath())
                && image.clearPath() != null && storage.isSafePublicFile(image.clearPath());
    }

    @Transactional(rollbackFor = Exception.class)
    public ImageRegistration registerImage(String hash, String originalName, UUID uploader, UUID avatarOwner,
                                            VipsImageProcessor.Processed image) throws IOException {
        if (hash == null || !hash.matches("[0-9a-f]{64}") || image == null) throw conflict("图片信息无效");
        if (!image.display().mime().equals(image.thumbnail().mime()) || !image.display().mime().equals(image.clear().mime()))
            throw conflict("图片版本格式不一致");
        UUID legacyId = null;
        if (avatarOwner == null) {
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", rs -> { }, "myshop-image:" + hash);
            var existing = lockedImages(hash);
            var complete = existing.stream().filter(this::completeImage).findFirst();
            if (complete.isPresent()) return complete.get().registration();
            legacyId = existing.stream().filter(item -> item.thumbnailPath() == null && item.storagePath() != null
                            && item.storagePath().equals(item.displayPath()) && storage.isSafePublicFile(item.storagePath()))
                    .map(ExistingImage::id).findFirst().orElse(null);
        }
        UUID id = legacyId == null ? UUID.randomUUID() : legacyId;
        // Variant names are independent of asset identity so a crash before
        // commit can never make a later retry overwrite an immutable URL.
        String stem = avatarOwner == null ? "images/" + UUID.randomUUID() : "avatars/managed-" + UUID.randomUUID();
        String display = stem + "-display" + extension(image.display().mime());
        String thumb = stem + "-thumb" + extension(image.thumbnail().mime());
        String clear = stem + "-clear" + extension(image.clear().mime());
        List<String> published = new ArrayList<>();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status == STATUS_UNKNOWN) {
                    // A lost commit acknowledgement does not prove rollback.
                    // Do not remove files which a committed row may reference.
                    org.slf4j.LoggerFactory.getLogger(MediaAssetRegistry.class).warn("Image transaction outcome is unknown; retaining published files");
                    return;
                }
                if (status == STATUS_ROLLED_BACK) {
                    boolean failed = false;
                    for (String path : published) {
                        if (java.nio.file.Files.exists(storage.resolveManagedPath(path), java.nio.file.LinkOption.NOFOLLOW_LINKS)
                                && !storage.deleteManagedFile(path)) failed = true;
                    }
                    if (failed) recordRolledBackImageCleanup(display, thumb, clear, originalName, uploader, avatarOwner,
                            image.display().mime(), image.display().size());
                }
            }
        });
        storage.publishFile(display, image.display().path()); published.add(display);
        storage.publishFile(thumb, image.thumbnail().path()); published.add(thumb);
        storage.publishFile(clear, image.clear().path()); published.add(clear);
        if (legacyId != null) {
            int updated = jdbc.update("""
                    UPDATE media_assets SET display_path=?,thumbnail_path=?,clear_path=?,mime_type=?,size_bytes=?,
                      original_size_bytes=?,thumbnail_size_bytes=?,clear_size_bytes=?,width=?,height=?,processing_reason=?
                    WHERE id=? AND processing_status='READY' AND deleted_at IS NULL AND thumbnail_path IS NULL
                    """, display, thumb, clear, image.display().mime(), image.display().size(), image.originalSize(),
                    image.thumbnail().size(), image.clear().size(), image.display().width(), image.display().height(), image.reason(), id);
            if (updated != 1) throw conflict("旧资源状态已变化，请重试上传");
            return new ImageRegistration(id, display, thumb, clear, image.originalSize(), image.display().size(), true, image.reason());
        }
        jdbc.update("""
                INSERT INTO media_assets(id,storage_path,display_path,thumbnail_path,clear_path,original_filename,media_type,
                  mime_type,size_bytes,original_size_bytes,thumbnail_size_bytes,clear_size_bytes,width,height,content_hash,
                  scope,uploader_id,owner_user_id,processing_reason,processing_status)
                VALUES (?,?,?,?,?,?,'IMAGE',?,?,?,?,?,?,?,?,?,?,?,?,'READY')
                """, id, display, display, thumb, clear, safeName(originalName), image.display().mime(), image.display().size(),
                image.originalSize(), image.thumbnail().size(), image.clear().size(), image.display().width(), image.display().height(),
                hash, avatarOwner == null ? "SHARED" : "AVATAR", uploader, avatarOwner, image.reason());
        return new ImageRegistration(id, display, thumb, clear, image.originalSize(), image.display().size(), false, image.reason());
    }

    private static String extension(String mime) {
        return switch (mime.toLowerCase(Locale.ROOT)) {
            case "image/jpeg" -> ".jpg"; case "image/png" -> ".png"; case "image/gif" -> ".gif";
            case "image/webp" -> ".webp"; case "image/avif" -> ".avif"; case "image/bmp" -> ".bmp";
            default -> throw conflict("图片处理输出格式无效");
        };
    }

    /** Preserve a retryable tombstone even though the original asset transaction rolled back. */
    private void recordRolledBackImageCleanup(String display, String thumb, String clear, String name,
                                               UUID uploader, UUID owner, String mime, long size) {
        try (var connection = java.util.Objects.requireNonNull(jdbc.getDataSource()).getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO media_assets(id,storage_path,display_path,thumbnail_path,clear_path,original_filename,
                       media_type,mime_type,size_bytes,scope,uploader_id,owner_user_id,processing_status,deleted_at,cleanup_error)
                     VALUES (?,?,?,?,?,?,'IMAGE',?,?,?,?,?,'FAILED',CURRENT_TIMESTAMP,'上传回滚后的文件清理失败，请重试')
                     """)) {
            connection.setAutoCommit(true);
            statement.setObject(1, UUID.randomUUID()); statement.setString(2, display); statement.setString(3, display);
            statement.setString(4, thumb); statement.setString(5, clear); statement.setString(6, safeName(name));
            statement.setString(7, mime); statement.setLong(8, size); statement.setString(9, owner == null ? "SHARED" : "AVATAR");
            statement.setObject(10, uploader); statement.setObject(11, owner);
            statement.executeUpdate();
        } catch (java.sql.SQLException | RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(MediaAssetRegistry.class).error("Could not persist rolled-back image cleanup state", failure);
        }
    }

    public LockedReferences lockProduct(String coverUrl, String detailMarkdown) {
        return lockProduct(null, coverUrl, detailMarkdown);
    }

    public LockedReferences lockProduct(UUID existingProductId, String coverUrl, String detailMarkdown) {
        Map<String, Integer> detailCounts = paths.markdownCounts(detailMarkdown);
        Map<String, Integer> coverCounts = new LinkedHashMap<>();
        paths.direct(coverUrl).forEach(path -> coverCounts.put(path, 1));
        return lockFields(Map.of("cover_url", coverCounts.keySet(), "detail_md", detailCounts.keySet()), false, null,
                existingProductId != null, Map.of("cover_url", coverCounts, "detail_md", detailCounts));
    }

    public LockedReferences lockAnnouncement(String markdown) {
        return lockAnnouncement(null, markdown);
    }

    public LockedReferences lockAnnouncement(UUID existingAnnouncementId, String markdown) {
        Map<String, Integer> counts = paths.markdownCounts(markdown);
        return lockFields(Map.of("body", counts.keySet()), false, null, existingAnnouncementId != null, Map.of("body", counts));
    }

    public LockedReferences lockSiteIcon(String url) {
        return lockFields(Map.of("favicon_url", paths.direct(url)), false, null, false, Map.of());
    }

    public LockedReferences lockExistingSiteIcon(String url) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        paths.direct(url).forEach(path -> counts.put(path, 1));
        return lockFields(Map.of("favicon_url", counts.keySet()), false, null, true, Map.of("favicon_url", counts));
    }

    public LockedReferences lockAvatar(String url, UUID userId) {
        return lockFields(Map.of("avatar_url", paths.direct(url)), true, userId, false, Map.of());
    }

    public record AvatarAsset(UUID id, String storagePath, String displayPath, String thumbnailPath, String clearPath,
                              String originalFilename, String scope, String mediaType, String status, boolean deleted) {
        List<String> paths() {
            var result = new LinkedHashSet<String>();
            for (String path : new String[]{storagePath, displayPath, thumbnailPath, clearPath}) if (path != null) result.add(path);
            return List.copyOf(result);
        }
    }
    public record AvatarTarget(UUID userId, String avatarUrl) { }
    public record AvatarOptimizationLocks(boolean sourceUsable) { }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Optional<AvatarAsset> findAvatarAsset(UUID id) {
        if (id == null) return Optional.empty();
        return jdbc.query("""
                SELECT id,storage_path,display_path,thumbnail_path,clear_path,original_filename,scope,media_type,
                       processing_status,deleted_at
                FROM media_assets WHERE id=?
                """, (rs, n) -> new AvatarAsset(rs.getObject("id", UUID.class), rs.getString("storage_path"),
                rs.getString("display_path"), rs.getString("thumbnail_path"), rs.getString("clear_path"),
                rs.getString("original_filename"), rs.getString("scope"), rs.getString("media_type"),
                rs.getString("processing_status"), rs.getTimestamp("deleted_at") != null), id).stream().findFirst();
    }

    /** Current user fields are checked against the asset paths; owner_user_id is not used to infer a shared avatar. */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<AvatarTarget> findCurrentAvatarTargets(AvatarAsset asset) {
        if (asset == null) return List.of();
        List<AvatarTarget> linked = jdbc.query("""
                SELECT u.id,u.avatar_url FROM media_references r JOIN users u ON u.id=r.owner_id
                WHERE r.asset_id=? AND r.owner_type='AVATAR' AND r.field='avatar_url'
                  AND u.avatar_url IS NOT NULL AND btrim(u.avatar_url)<>''
                ORDER BY u.id
                """, (rs, n) -> new AvatarTarget(rs.getObject(1, UUID.class), rs.getString(2)), asset.id());
        return linked.stream().filter(target -> {
            String path = paths.managedPath(target.avatarUrl());
            return path != null && asset.paths().contains(path);
        }).toList();
    }

    /** Asset UUID order is shared with media deletion before the owner row is locked. */
    public AvatarOptimizationLocks lockAvatarOptimization(UUID sourceId, String expectedOldUrl,
                                                            ImageUploadService.UploadedImage uploaded, UUID ownerId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || sourceId == null || ownerId == null
                || uploaded == null || uploaded.assetId() == null || sourceId.equals(uploaded.assetId()))
            throw new IllegalStateException("Avatar optimization requires two assets and an active transaction");
        List<AvatarAsset> rows = jdbc.query("""
                SELECT id,storage_path,display_path,thumbnail_path,clear_path,original_filename,scope,media_type,
                       processing_status,deleted_at
                FROM media_assets WHERE id IN (?,?) ORDER BY id FOR UPDATE
                """, (rs, n) -> new AvatarAsset(rs.getObject("id", UUID.class), rs.getString("storage_path"),
                rs.getString("display_path"), rs.getString("thumbnail_path"), rs.getString("clear_path"),
                rs.getString("original_filename"), rs.getString("scope"), rs.getString("media_type"),
                rs.getString("processing_status"), rs.getTimestamp("deleted_at") != null), sourceId, uploaded.assetId());
        AvatarAsset source = rows.stream().filter(asset -> asset.id().equals(sourceId)).findFirst().orElse(null);
        AvatarAsset target = rows.stream().filter(asset -> asset.id().equals(uploaded.assetId())).findFirst().orElse(null);
        if (target == null || target.deleted() || !"IMAGE".equals(target.mediaType()) || !"READY".equals(target.status())
                || !"AVATAR".equals(target.scope()) || target.thumbnailPath() == null
                || !ownerId.equals(jdbc.queryForObject("SELECT owner_user_id FROM media_assets WHERE id=?", UUID.class, target.id()))
                || !paths.direct(uploaded.url()).contains(target.displayPath()))
            throw conflict("新头像资源未完成登记");
        boolean sourceUsable = source != null && !source.deleted() && "IMAGE".equals(source.mediaType())
                && "READY".equals(source.status()) && paths.direct(expectedOldUrl).stream().anyMatch(source.paths()::contains);
        return new AvatarOptimizationLocks(sourceUsable);
    }

    private LockedReferences lockFields(Map<String, Set<String>> fields, boolean avatar, UUID owner, boolean allowHistoricalAvatar,
                                        Map<String, Map<String, Integer>> counts) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Media reference saves require a transaction");
        Set<String> requested = new HashSet<>();
        fields.values().forEach(requested::addAll);
        if (requested.isEmpty()) return new LockedReferences(Map.of());
        String placeholders = String.join(",", java.util.Collections.nCopies(requested.size(), "?"));
        List<Object> args = new ArrayList<>();
        for (int i = 0; i < 4; i++) args.addAll(requested);
        for (String path : requested) args.add(path.toLowerCase(Locale.ROOT));
        Map<String, UUID> found = new HashMap<>();
        Set<String> historicalAvatarPaths = new HashSet<>();
        jdbc.query("SELECT id,storage_path,display_path,thumbnail_path,clear_path,scope,owner_user_id,processing_status,deleted_at "
                        + "FROM media_assets WHERE storage_path IN (" + placeholders + ") OR display_path IN (" + placeholders
                        + ") OR thumbnail_path IN (" + placeholders + ") OR clear_path IN (" + placeholders
                        + ") OR (deleted_at IS NOT NULL AND position('/' in storage_path)=0 AND lower(storage_path) IN ("
                        + placeholders + ")) ORDER BY id FOR UPDATE",
                rs -> {
                    UUID id = rs.getObject("id", UUID.class);
                    String scope = rs.getString("scope");
                    UUID assetOwner = rs.getObject("owner_user_id", UUID.class);
                    if (rs.getTimestamp("deleted_at") != null || !"READY".equals(rs.getString("processing_status")))
                        throw conflict("所选资源已删除或尚不可用，请刷新后重新选择");
                    if ("AVATAR".equals(scope) && avatar && (owner == null || !owner.equals(assetOwner)))
                        throw conflict("头像不属于当前账号");
                    if ("AVATAR".equals(scope) && !avatar && !allowHistoricalAvatar)
                        throw conflict("头像不能作为共享商品资源使用");
                    for (String column : List.of("storage_path", "display_path", "thumbnail_path", "clear_path")) {
                        String path = rs.getString(column);
                        if (path != null && requested.contains(path)) {
                            if (!storage.isSafePublicFile(path)) throw conflict("所选资源文件不可用，请重新上传");
                            found.put(path, id);
                            if ("AVATAR".equals(scope) && !avatar) historicalAvatarPaths.add(path);
                        }
                    }
                }, args.toArray());
        if (!found.keySet().containsAll(requested)) throw conflict("所选受管资源不存在或已删除，请刷新后重新选择");
        Map<String, Set<UUID>> result = new LinkedHashMap<>();
        fields.forEach((field, values) -> {
            Set<UUID> ids = new HashSet<>();
            values.forEach(path -> ids.add(found.get(path)));
            result.put(field, ids);
        });
        Map<String, Map<String, Integer>> historical = new LinkedHashMap<>();
        counts.forEach((field, values) -> {
            Map<String, Integer> selected = new LinkedHashMap<>();
            values.forEach((path, count) -> { if (historicalAvatarPaths.contains(path)) selected.put(path, count); });
            if (!selected.isEmpty()) historical.put(field, selected);
        });
        return new LockedReferences(result, historical);
    }

    /** Invoke after locking the product row: existing avatar uses may remain but cannot be added. */
    public void verifyHistoricalProductAvatarReferences(UUID productId, LockedReferences locked) {
        if (locked.historicalAvatarPaths().isEmpty()) return;
        List<String[]> rows = jdbc.query("SELECT cover_url,detail_md FROM products WHERE id=? AND is_deleted=0",
                (rs, n) -> new String[]{rs.getString(1), rs.getString(2)}, productId);
        if (rows.size() != 1) throw conflict("商品已变化，请刷新后重试");
        var values = rows.getFirst();
        for (var entry : locked.historicalAvatarPaths().entrySet()) {
            Map<String, Integer> oldCounts = new LinkedHashMap<>();
            if (entry.getKey().equals("cover_url")) paths.direct(values[0]).forEach(path -> oldCounts.put(path, 1));
            else if (entry.getKey().equals("detail_md")) oldCounts.putAll(paths.markdownCounts(values[1]));
            else throw new IllegalStateException("Unexpected product media field");
            verifyHistoricalCounts(entry.getValue(), oldCounts);
        }
    }

    /** Caller holds the existing announcement row before checking its old content. */
    public void verifyHistoricalAnnouncementAvatarReferences(String oldMarkdown, LockedReferences locked) {
        verifyHistoricalCounts(locked.historicalAvatarPaths().getOrDefault("body", Map.of()), paths.markdownCounts(oldMarkdown));
    }

    /** Caller holds the existing site row; a previous icon may remain, but cannot be newly borrowed. */
    public void verifyHistoricalSiteIconReferences(String oldUrl, LockedReferences locked) {
        Map<String, Integer> oldCounts = new LinkedHashMap<>();
        paths.direct(oldUrl).forEach(path -> oldCounts.put(path, 1));
        verifyHistoricalCounts(locked.historicalAvatarPaths().getOrDefault("favicon_url", Map.of()), oldCounts);
    }

    private static void verifyHistoricalCounts(Map<String, Integer> desired, Map<String, Integer> previous) {
        for (var entry : desired.entrySet())
            if (previous.getOrDefault(entry.getKey(), 0) < entry.getValue())
                throw conflict("头像不能新增为共享资源，请先上传独立图片");
    }

    /** Called after the owner row is saved, in the same transaction as lockFields. */
    public void replaceReferences(String ownerType, UUID ownerId, LockedReferences references, String... fields) {
        if (ownerId == null || !TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Media reference saves require an owner and transaction");
        List<String> priorAvatarPaths = !"AVATAR".equals(ownerType) && maintenance != null
                ? jdbc.query("""
                    SELECT DISTINCT a.storage_path FROM media_references r JOIN media_assets a ON a.id=r.asset_id
                    WHERE r.owner_type=? AND r.owner_id=? AND a.scope='AVATAR' AND a.deleted_at IS NULL
                      AND a.storage_path IS NOT NULL
                    """, (rs, n) -> rs.getString(1), ownerType, ownerId) : List.of();
        for (String field : fields) {
            jdbc.update("DELETE FROM media_references WHERE owner_type=? AND owner_id=? AND field=?", ownerType, ownerId, field);
            for (UUID assetId : references.fields().getOrDefault(field, Set.of()))
                jdbc.update("INSERT INTO media_references(asset_id,owner_type,owner_id,field) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
                        assetId, ownerType, ownerId, field);
        }
        if (!priorAvatarPaths.isEmpty()) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                for (String path : priorAvatarPaths) try { maintenance.retireUnreferencedAvatar(paths.url(path), ownerId); }
                catch (RuntimeException failure) { org.slf4j.LoggerFactory.getLogger(MediaAssetRegistry.class)
                        .warn("Historical avatar cleanup needs retry: {}", failure.getMessage()); }
            }
        });
    }

    @Transactional
    public UUID createVideo(String originalName, UUID uploaderId, long uploadBytes) {
        if (uploaderId == null || uploadBytes <= 0) throw conflict("视频上传信息无效");
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO media_assets(id,original_filename,media_type,scope,uploader_id,
                  original_size_bytes,declared_size_bytes,processing_status)
                VALUES (?,?,'VIDEO','SHARED',?,?,?,'UPLOADING')
                """, id, safeName(originalName), uploaderId, uploadBytes, uploadBytes);
        return id;
    }

    @Transactional
    public void bindVideoUpload(UUID assetId, UUID uploaderId, String uploadUri, long declaredSize) {
        if (assetId == null || uploaderId == null || uploadUri == null || uploadUri.isBlank()
                || uploadUri.length() > 2048 || uploadUri.chars().anyMatch(Character::isISOControl))
            throw conflict("视频上传会话无效");
        int changed = jdbc.update("""
                UPDATE media_assets SET upload_uri=? WHERE id=? AND uploader_id=? AND media_type='VIDEO'
                  AND processing_status='UPLOADING' AND deleted_at IS NULL AND upload_uri IS NULL AND declared_size_bytes=?
                """, uploadUri, assetId, uploaderId, declaredSize);
        if (changed != 1) throw conflict("视频上传会话已绑定、不可用或无权访问");
    }

    @Transactional(readOnly = true)
    public Optional<VideoSession> findVideoByUploadUri(String uploadUri, UUID uploaderId) {
        if (uploadUri == null || uploaderId == null) return Optional.empty();
        return jdbc.query("""
                SELECT id,uploader_id,upload_uri,processing_status,original_filename,declared_size_bytes,processing_error,reused_asset_id
                FROM media_assets WHERE upload_uri=? AND uploader_id=? AND media_type='VIDEO' AND deleted_at IS NULL
                """, (rs, n) -> session(rs), uploadUri, uploaderId).stream().findFirst();
    }

    /** Cancellation retries may find a tombstoned session solely to remove its temp files. */
    @Transactional(readOnly = true)
    public Optional<VideoSession> findVideoByUploadUriIncludingDeleted(String uploadUri, UUID uploaderId) {
        if (uploadUri == null || uploaderId == null) return Optional.empty();
        return jdbc.query("""
                SELECT id,uploader_id,upload_uri,processing_status,original_filename,declared_size_bytes,processing_error,reused_asset_id
                FROM media_assets WHERE upload_uri=? AND uploader_id=? AND media_type='VIDEO'
                """, (rs, n) -> session(rs), uploadUri, uploaderId).stream().findFirst();
    }

    @Transactional(readOnly = true)
    public Optional<VideoSession> findVideo(UUID assetId, UUID uploaderId) {
        if (assetId == null || uploaderId == null) return Optional.empty();
        return jdbc.query("""
                SELECT id,uploader_id,upload_uri,processing_status,original_filename,declared_size_bytes,processing_error,reused_asset_id
                FROM media_assets WHERE id=? AND uploader_id=? AND media_type='VIDEO' AND deleted_at IS NULL
                """, (rs, n) -> session(rs), assetId, uploaderId).stream().findFirst();
    }

    /** Used only to retry cleanup of a cancelled upload, never to authorize content delivery. */
    @Transactional(readOnly = true)
    public Optional<VideoSession> findVideoIncludingDeleted(UUID assetId, UUID uploaderId) {
        if (assetId == null || uploaderId == null) return Optional.empty();
        return jdbc.query("""
                SELECT id,uploader_id,upload_uri,processing_status,original_filename,declared_size_bytes,processing_error,reused_asset_id
                FROM media_assets WHERE id=? AND uploader_id=? AND media_type='VIDEO'
                """, (rs, n) -> session(rs), assetId, uploaderId).stream().findFirst();
    }

    @Transactional(readOnly = true)
    public List<VideoSession> findRecoverableVideos() {
        return jdbc.query("""
                SELECT id,uploader_id,upload_uri,processing_status,original_filename,declared_size_bytes,processing_error,reused_asset_id
                FROM media_assets WHERE media_type='VIDEO' AND deleted_at IS NULL
                  AND processing_status IN ('UPLOADING','PROCESSING','FAILED') AND upload_uri IS NOT NULL ORDER BY created_at,id
                """, (rs, n) -> session(rs));
    }

    /** A session created but never bound to a tus URI cannot resume. */
    @Transactional
    public int expireUnboundVideoSessions() {
        return jdbc.update("""
                UPDATE media_assets SET processing_status='FAILED',processing_error='上传会话未完成或已过期，请重新上传'
                WHERE media_type='VIDEO' AND processing_status='UPLOADING' AND deleted_at IS NULL AND upload_uri IS NULL
                  AND created_at < CURRENT_TIMESTAMP - INTERVAL '7 days'
                """);
    }

    @Transactional(readOnly = true)
    public List<VideoSession> findCancelledVideosNeedingCleanup() {
        return jdbc.query("""
                SELECT id,uploader_id,upload_uri,processing_status,original_filename,declared_size_bytes,processing_error,reused_asset_id
                FROM media_assets WHERE media_type='VIDEO' AND deleted_at IS NOT NULL AND upload_cleanup_error IS NOT NULL
                  AND (processing_error='上传已取消' OR processing_status='REUSED') ORDER BY created_at,id
                """, (rs, n) -> session(rs));
    }

    @Transactional(readOnly = true)
    public List<VideoSession> findReadyVideosNeedingUploadCleanup() {
        return jdbc.query("""
                SELECT id,uploader_id,upload_uri,processing_status,original_filename,declared_size_bytes,processing_error,reused_asset_id
                FROM media_assets WHERE media_type='VIDEO' AND processing_status='READY'
                  AND upload_cleanup_error IS NOT NULL AND upload_uri IS NOT NULL ORDER BY created_at,id
                """, (rs, n) -> session(rs));
    }

    @Transactional
    public void markReadyVideoCleanupComplete(UUID assetId, UUID uploaderId) {
        int changed = jdbc.update("""
                UPDATE media_assets SET upload_cleanup_error=NULL WHERE id=? AND uploader_id=? AND media_type='VIDEO'
                  AND processing_status='READY' AND upload_uri IS NOT NULL
                """, assetId, uploaderId);
        if (changed != 1) throw conflict("视频清理状态已变化");
    }

    @Transactional
    public void markReadyVideoCleanupFailed(UUID assetId, UUID uploaderId, String reason) {
        String message = reason == null ? "视频上传临时文件清理失败" : reason.replaceAll("[\\p{Cntrl}]", " ");
        if (message.length() > 1000) message = message.substring(0, 1000);
        int changed = jdbc.update("""
                UPDATE media_assets SET upload_cleanup_error=? WHERE id=? AND uploader_id=? AND media_type='VIDEO'
                  AND processing_status='READY' AND upload_uri IS NOT NULL
                """, message, assetId, uploaderId);
        if (changed != 1) throw conflict("视频清理状态已变化");
    }

    @Transactional
    public void markVideoProcessing(UUID assetId) {
        int changed = jdbc.update("""
                UPDATE media_assets SET processing_status='PROCESSING',processing_error=NULL
                WHERE id=? AND media_type='VIDEO' AND deleted_at IS NULL AND upload_uri IS NOT NULL
                  AND processing_status IN ('UPLOADING','PROCESSING','FAILED')
                """, assetId);
        if (changed != 1) throw conflict("视频当前不能进入处理状态");
    }

    /** Call once the source has been streamed and hashed, before expensive conversion. */
    @Transactional
    public Optional<VideoReuse> reuseVideoIfExists(UUID assetId, String sourceHash) {
        if (assetId == null || sourceHash == null || !sourceHash.matches("[0-9a-fA-F]{64}")) throw conflict("视频源文件信息无效");
        String normalized = sourceHash.toLowerCase(Locale.ROOT);
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", rs -> { }, "myshop-video:" + normalized);
        var candidates = jdbc.query("""
                SELECT id,storage_path FROM media_assets WHERE media_type='VIDEO' AND scope='SHARED'
                  AND source_hash=? AND id<>? AND processing_status='READY' AND deleted_at IS NULL
                ORDER BY id FOR UPDATE
                """, (rs, n) -> new VideoReuse(rs.getObject(1, UUID.class), rs.getString(2)), normalized, assetId);
        Optional<VideoReuse> effective = candidates.stream().filter(candidate -> candidate.storagePath() != null
                && storage.isSafePublicFile(candidate.storagePath())).findFirst();
        if (effective.isEmpty()) {
            int updated = jdbc.update("""
                    UPDATE media_assets SET source_hash=? WHERE id=? AND media_type='VIDEO' AND deleted_at IS NULL
                      AND upload_uri IS NOT NULL AND processing_status IN ('UPLOADING','PROCESSING','FAILED')
                    """, normalized, assetId);
            if (updated != 1) throw conflict("视频上传会话已取消或不可用");
            return Optional.empty();
        }
        VideoReuse reuse = effective.get();
        int updated = jdbc.update("""
                UPDATE media_assets SET source_hash=?,reused_asset_id=?,processing_status='REUSED',deleted_at=CURRENT_TIMESTAMP,
                  processing_error=NULL,upload_cleanup_error='复用后的临时文件待清理'
                WHERE id=? AND media_type='VIDEO' AND deleted_at IS NULL AND upload_uri IS NOT NULL
                  AND processing_status IN ('UPLOADING','PROCESSING','FAILED')
                """, normalized, reuse.assetId(), assetId);
        if (updated != 1) throw conflict("视频上传会话已取消或不可用");
        return Optional.of(reuse);
    }

    /** Recheck reuse while committing READY to serialize two concurrent identical uploads. */
    @Transactional
    public VideoCompletion markVideoReady(UUID assetId, String storagePath, String mime, long sizeBytes, Long durationMs,
                                          String sha256, String sourceHash) {
        if (assetId == null || storagePath == null || !storage.isSafePublicFile(storagePath)
                || mime == null || !mime.startsWith("video/") || sizeBytes <= 0 || durationMs == null || durationMs < 0
                || sha256 == null || !sha256.matches("[0-9a-fA-F]{64}")) throw conflict("视频成品信息无效");
        Optional<VideoReuse> reused = reuseVideoIfExists(assetId, sourceHash);
        if (reused.isPresent()) return new VideoCompletion(reused.get().assetId(), reused.get().storagePath(), true);
        int changed = jdbc.update("""
                UPDATE media_assets SET storage_path=?,display_path=?,clear_path=?,mime_type=?,size_bytes=?,
                  clear_size_bytes=?,duration_ms=?,content_hash=?,processing_status='READY',processing_error=NULL,
                  upload_cleanup_error='视频上传临时文件待清理'
                WHERE id=? AND media_type='VIDEO' AND deleted_at IS NULL AND processing_status='PROCESSING'
                """, storagePath, storagePath, storagePath, mime.toLowerCase(Locale.ROOT), sizeBytes, sizeBytes,
                durationMs, sha256.toLowerCase(Locale.ROOT), assetId);
        if (changed != 1) throw conflict("视频处理状态已变化");
        return new VideoCompletion(assetId, storagePath, false);
    }

    @Transactional
    public void markVideoFailed(UUID assetId, String reason) {
        String message = reason == null ? "视频处理失败" : reason.replaceAll("[\\p{Cntrl}]", " ");
        if (message.length() > 1000) message = message.substring(0, 1000);
        int changed = jdbc.update("""
                UPDATE media_assets SET processing_status='FAILED',processing_error=?
                WHERE id=? AND media_type='VIDEO' AND deleted_at IS NULL AND processing_status IN ('UPLOADING','PROCESSING','FAILED')
                """, message, assetId);
        if (changed != 1) throw conflict("视频处理状态已变化");
    }

    @Transactional
    public boolean cancelVideo(UUID assetId, UUID uploaderId) {
        if (assetId == null || uploaderId == null) throw conflict("视频上传会话无效");
        int changed = jdbc.update("""
                UPDATE media_assets SET deleted_at=coalesce(deleted_at,CURRENT_TIMESTAMP),processing_error='上传已取消',
                  upload_cleanup_error=coalesce(upload_cleanup_error,'上传临时文件待清理')
                WHERE id=? AND uploader_id=? AND media_type='VIDEO'
                  AND processing_status IN ('UPLOADING','PROCESSING','FAILED')
                """, assetId, uploaderId);
        if (changed != 1) throw conflict("视频上传会话已完成、不可用或无权访问");
        return true;
    }

    @Transactional
    public void markVideoCleanupComplete(UUID assetId, UUID uploaderId) {
        int changed = jdbc.update("""
                UPDATE media_assets SET upload_cleanup_error=NULL WHERE id=? AND uploader_id=? AND media_type='VIDEO'
                  AND deleted_at IS NOT NULL AND (processing_error='上传已取消' OR processing_status='REUSED')
                  AND processing_status IN ('UPLOADING','PROCESSING','FAILED','REUSED')
                """, assetId, uploaderId);
        if (changed != 1) throw conflict("视频清理状态已变化");
    }

    @Transactional
    public void markVideoCleanupFailed(UUID assetId, UUID uploaderId, String reason) {
        String message = reason == null ? "视频临时文件清理失败" : reason.replaceAll("[\\p{Cntrl}]", " ");
        if (message.length() > 1000) message = message.substring(0, 1000);
        int changed = jdbc.update("""
                UPDATE media_assets SET upload_cleanup_error=? WHERE id=? AND uploader_id=? AND media_type='VIDEO'
                  AND deleted_at IS NOT NULL AND (processing_error='上传已取消' OR processing_status='REUSED')
                  AND processing_status IN ('UPLOADING','PROCESSING','FAILED','REUSED')
                """, message, assetId, uploaderId);
        if (changed != 1) throw conflict("视频清理状态已变化");
    }

    private static VideoSession session(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new VideoSession(rs.getObject("id", UUID.class), rs.getObject("uploader_id", UUID.class),
                rs.getString("upload_uri"), rs.getString("processing_status"), rs.getString("original_filename"),
                rs.getLong("declared_size_bytes"), rs.getString("processing_error"), rs.getObject("reused_asset_id", UUID.class));
    }

    private static String safeName(String value) {
        String name = value == null ? "video" : value.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.isEmpty()) name = "video";
        return name.length() <= 512 ? name : name.substring(0, 512);
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, HttpStatus.CONFLICT);
    }
}
