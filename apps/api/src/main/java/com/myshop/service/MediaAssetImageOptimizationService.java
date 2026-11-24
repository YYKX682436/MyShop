package com.myshop.service;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Adds immutable display variants to one legacy shared image and rewrites its current references. */
@Service
public class MediaAssetImageOptimizationService {
    private final MediaAssetRegistry assets;
    private final ImageUploadService images;
    private final UploadStorage storage;

    public MediaAssetImageOptimizationService(MediaAssetRegistry assets, ImageUploadService images, UploadStorage storage) {
        this.assets = assets;
        this.images = images;
        this.storage = storage;
    }

    public OptimizationReport optimize(UUID assetId) {
        var asset = assets.findAvatarAsset(assetId).orElse(null);
        if (asset == null) return skipped(assetId, "not_found", "资源不存在");
        if (!"IMAGE".equals(asset.mediaType())) return skipped(assetId, "not_image", "仅支持图片资源");
        if (!"SHARED".equals(asset.scope())) return skipped(assetId, "unsupported_scope", "此操作只处理共享图片");
        if (asset.deleted()) return skipped(assetId, "deleted", "资源已删除");
        if (!"READY".equals(asset.status())) return skipped(assetId, "not_ready", "资源尚不可用");
        if (asset.thumbnailPath() != null) return skipped(assetId, "already_optimized", "图片已有展示版本，无需重复处理");
        if (asset.storagePath() == null || !asset.storagePath().equals(asset.displayPath()))
            return skipped(assetId, "unsupported_source", "原图路径状态不符合旧资源处理条件");
        String sourcePath = asset.storagePath();
        if (!storage.isSafePublicFile(sourcePath)) return skipped(assetId, "source_missing", "原图片文件不存在或不可读取");

        Path source = storage.resolveManagedPath(sourcePath);
        long originalBytes;
        try { originalBytes = Files.size(source); }
        catch (IOException failure) { return skipped(assetId, "source_missing", "原图片文件不存在或不可读取"); }

        ImageUploadService.ImageOptimizationResult result;
        try {
            result = images.optimizeExistingManagedImage(source, asset.originalFilename(), asset.id());
        } catch (RuntimeException failure) {
            return new OptimizationReport(assetId, "failed", "processing_failed", message(failure), originalBytes, 0, 0, null);
        }
        if (result.skipped())
            return skipped(assetId, result.skippedReason(), result.message());

        var uploaded = result.image();
        if (!asset.id().equals(uploaded.assetId()))
            return new OptimizationReport(assetId, "failed", "asset_changed", "优化结果未关联到原资源", originalBytes, 0, 0, null);
        long displayBytes = uploaded.storedSize();
        return new OptimizationReport(assetId, "optimized", null, "已生成展示版和缩略版；原图保留",
                originalBytes, displayBytes, Math.max(0, originalBytes - displayBytes), uploaded.url());
    }

    private static OptimizationReport skipped(UUID assetId, String reason, String message) {
        return new OptimizationReport(assetId, "skipped", reason, message, 0, 0, 0, null);
    }

    private static String message(Throwable failure) {
        String value = failure.getMessage();
        return value == null || value.isBlank() ? failure.getClass().getSimpleName() : value;
    }

    public record OptimizationReport(UUID assetId, String status, String reason, String message,
                                     long originalBytes, long displayBytes, long displayBytesSaved, String displayUrl) { }
}
