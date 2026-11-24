package com.myshop.service;

import com.myshop.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Per-account optimization of the current managed avatar, using the ordinary avatar publication path. */
@Service
public class AvatarMediaOptimizationService {
    private final MediaAssetRegistry assets;
    private final UserAvatarService users;
    private final ImageUploadService images;
    private final UploadStorage storage;
    private final ManagedMediaReferences paths;
    private final MediaMaintenanceService maintenance;

    public AvatarMediaOptimizationService(MediaAssetRegistry assets, UserAvatarService users,
                                          ImageUploadService images, UploadStorage storage,
                                          ManagedMediaReferences paths, MediaMaintenanceService maintenance) {
        this.assets = assets;
        this.users = users;
        this.images = images;
        this.storage = storage;
        this.paths = paths;
        this.maintenance = maintenance;
    }

    public OptimizationReport optimize(UUID assetId) {
        var asset = assets.findAvatarAsset(assetId)
                .orElseThrow(() -> new BusinessException(com.myshop.constant.ErrorCode.NOT_FOUND, "资源不存在", HttpStatus.NOT_FOUND));
        if (!"IMAGE".equals(asset.mediaType())) return skipped(assetId, "not_image", "仅支持图片资源");
        if (!"AVATAR".equals(asset.scope()) && !"SHARED".equals(asset.scope()))
            return skipped(assetId, "unsupported_scope", "资源用途不支持作为用户头像优化");
        if (asset.deleted()) return skipped(assetId, "deleted", "资源已删除");
        if (!"READY".equals(asset.status())) return skipped(assetId, "not_ready", "资源尚不可用");
        if (asset.thumbnailPath() != null) return skipped(assetId, "already_optimized", "新版头像已处理，无需再次优化");

        List<MediaAssetRegistry.AvatarTarget> targets = assets.findCurrentAvatarTargets(asset);
        if (targets.isEmpty()) return skipped(assetId, "not_in_use", "没有用户正在使用此资源作为头像");
        Map<String, Long> originalPaths = fileSizes(asset.paths());
        List<AvatarOutcome> outcomes = new ArrayList<>();
        long displayBytesSaved = 0;
        for (MediaAssetRegistry.AvatarTarget target : targets) {
            String relativePath = paths.managedPath(target.avatarUrl());
            if (relativePath == null || !asset.paths().contains(relativePath)) {
                outcomes.add(AvatarOutcome.skipped(target.userId(), "avatar_changed", "用户头像已变化"));
                continue;
            }
            Path source = storage.resolveManagedPath(relativePath);
            if (!storage.isSafePublicFile(relativePath)) {
                outcomes.add(AvatarOutcome.skipped(target.userId(), "source_missing", "原头像文件不存在或不可读取"));
                continue;
            }
            long oldBytes;
            try { oldBytes = Files.size(source); }
            catch (IOException failure) {
                outcomes.add(AvatarOutcome.skipped(target.userId(), "source_missing", "原头像文件不存在或不可读取"));
                continue;
            }

            ImageUploadService.AvatarOptimizationResult result;
            try { result = images.optimizeExistingAvatar(source, Path.of(relativePath).getFileName().toString(), target.userId()); }
            catch (RuntimeException failure) {
                outcomes.add(AvatarOutcome.failed(target.userId(), "processing_failed", message(failure)));
                continue;
            }
            if (result.skipped()) {
                outcomes.add(AvatarOutcome.skipped(target.userId(), result.skippedReason(), result.message()));
                continue;
            }

            var uploaded = result.image();
            try {
                boolean changed = users.applyOptimizedAvatar(target.userId(), asset.id(), target.avatarUrl(), uploaded);
                if (!changed) {
                    maintenance.retireUnreferencedAvatar(uploaded.url(), target.userId());
                    outcomes.add(AvatarOutcome.skipped(target.userId(), "avatar_changed", "客户已换头像或原资源已删除，本次未应用"));
                    continue;
                }
                long saved = Math.max(0, oldBytes - uploaded.storedSize());
                displayBytesSaved += saved;
                outcomes.add(AvatarOutcome.optimized(target.userId(), oldBytes, uploaded.storedSize(), uploaded.url()));
            } catch (RuntimeException failure) {
                try { maintenance.retireUnreferencedAvatar(uploaded.url(), target.userId()); }
                catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                outcomes.add(AvatarOutcome.failed(target.userId(), "save_failed", message(failure)));
            }
        }

        boolean sourceRetired = assets.findAvatarAsset(asset.id()).map(MediaAssetRegistry.AvatarAsset::deleted).orElse(false);
        boolean sourceGone = asset.paths().stream().noneMatch(storage::isSafePublicFile);
        long physicalBytesReleased = sourceRetired && sourceGone
                ? originalPaths.values().stream().mapToLong(Long::longValue).sum() : 0;
        return new OptimizationReport(assetId, outcomes.stream().filter(item -> item.status().equals("optimized")).count(),
                outcomes.stream().filter(item -> item.status().equals("skipped")).count(),
                outcomes.stream().filter(item -> item.status().equals("failed")).count(), displayBytesSaved,
                physicalBytesReleased, outcomes, null, null);
    }

    private Map<String, Long> fileSizes(List<String> paths) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String path : paths) if (storage.isSafePublicFile(path)) {
            try { result.put(path, Files.size(storage.resolveManagedPath(path))); }
            catch (IOException ignored) { }
        }
        return result;
    }

    private OptimizationReport skipped(UUID id, String reason, String explanation) {
        return new OptimizationReport(id, 0, 1, 0, 0, 0, List.of(), reason, explanation);
    }

    private static String message(Throwable failure) {
        String value = failure.getMessage();
        return value == null || value.isBlank() ? failure.getClass().getSimpleName() : value;
    }

    public record AvatarOutcome(UUID userId, String status, String reason, String message,
                                long beforeBytes, long afterBytes, String url) {
        static AvatarOutcome optimized(UUID user, long before, long after, String url) {
            return new AvatarOutcome(user, "optimized", null, null, before, after, url);
        }
        static AvatarOutcome skipped(UUID user, String reason, String message) {
            return new AvatarOutcome(user, "skipped", reason, message, 0, 0, null);
        }
        static AvatarOutcome failed(UUID user, String reason, String message) {
            return new AvatarOutcome(user, "failed", reason, message, 0, 0, null);
        }
    }

    public record OptimizationReport(UUID assetId, long optimized, long skipped, long failed,
                                     long displayBytesSaved, long physicalBytesReleased,
                                     List<AvatarOutcome> outcomes, String skippedReason, String message) { }
}
