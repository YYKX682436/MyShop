package com.myshop.service;

import com.myshop.constant.ErrorCode;
import com.myshop.context.RequestContext;
import com.myshop.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Validates uploads, invokes libvips on local work files and publishes immutable variants. */
@Slf4j
@Service
public class ImageUploadService {
    private static final long ADMIN_LIMIT = 10L * 1024 * 1024;
    private static final long ACCOUNT_LIMIT = 5L * 1024 * 1024;
    private static final long EXISTING_IMAGE_LIMIT = 10L * 1024 * 1024;
    private static final Set<String> FORMATS = Set.of(".jpg", ".jpeg", ".png", ".apng", ".gif", ".webp", ".bmp", ".avif", ".heic", ".heif");
    private static final Set<String> ACCOUNT_FORMATS = Set.of(".jpg", ".jpeg", ".png", ".apng", ".webp", ".avif", ".heic", ".heif");
    private final UploadStorage storage;
    private final VipsImageProcessor vips;
    private final MediaAssetRegistry assets;
    private final String prefix;

    public ImageUploadService(UploadStorage storage, VipsImageProcessor vips, MediaAssetRegistry assets,
                              @Value("${upload.url-prefix:/api/uploads}") String prefix) {
        this.storage = storage; this.vips = vips; this.assets = assets;
        this.prefix = StringUtils.hasText(prefix) ? prefix.trim().replaceAll("/+$", "") : "/api/uploads";
    }

    public UploadedImage uploadAdminImage(MultipartFile file) { return upload(file, false, false, null); }
    public UploadedImage uploadSiteFavicon(MultipartFile file) { return upload(file, false, true, null); }
    public UploadedImage uploadAvatar(MultipartFile file, UUID ownerId) {
        if (ownerId == null) throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户信息无效");
        return upload(file, true, false, ownerId, false);
    }

    public AvatarOptimizationResult optimizeExistingAvatar(Path source, String originalName, UUID ownerId) {
        if (ownerId == null) throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户信息无效");
        if (source == null || Files.isSymbolicLink(source) || !Files.isRegularFile(source))
            return AvatarOptimizationResult.skipped("source_missing", "原头像文件不存在");
        try {
            String name = StringUtils.hasText(originalName) ? originalName : source.getFileName().toString();
            return new AvatarOptimizationResult(upload(new PathMultipartFile(source, name), true, false, ownerId, true), null, null);
        } catch (ImageOptimizationSkipped skipped) {
            return AvatarOptimizationResult.skipped(skipped.reason, skipped.getMessage());
        }
    }

    public ImageOptimizationResult optimizeExistingManagedImage(Path source, String originalName, UUID assetId) {
        if (assetId == null) throw new IllegalArgumentException("assetId is required");
        if (source == null || Files.isSymbolicLink(source) || !Files.isRegularFile(source))
            return ImageOptimizationResult.skipped("source_missing", "原图片文件不存在");
        try {
            String name = StringUtils.hasText(originalName) ? originalName : source.getFileName().toString();
            return new ImageOptimizationResult(upload(new PathMultipartFile(source, name), false, false, null, true, assetId), null, null);
        } catch (ImageOptimizationSkipped skipped) {
            return ImageOptimizationResult.skipped(skipped.reason, skipped.getMessage());
        }
    }

    private UploadedImage upload(MultipartFile file, boolean avatar, boolean favicon, UUID owner) {
        return upload(file, avatar, favicon, owner, false, null);
    }

    private UploadedImage upload(MultipartFile file, boolean avatar, boolean favicon, UUID owner, boolean optimizeExisting) {
        return upload(file, avatar, favicon, owner, optimizeExisting, null);
    }

    private UploadedImage upload(MultipartFile file, boolean avatar, boolean favicon, UUID owner,
                                 boolean optimizeExisting, UUID upgradeAssetId) {
        validate(file, avatar, favicon, optimizeExisting);
        Path input = null;
        try {
            input = Files.createTempFile(storage.directory(), ".image-input-", ".tmp");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0, limit = optimizeExisting ? EXISTING_IMAGE_LIMIT : avatar || favicon ? ACCOUNT_LIMIT : ADMIN_LIMIT;
            try (var in = file.getInputStream(); var out = Files.newOutputStream(input, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[8192];
                for (int n; (n = in.read(buffer)) != -1;) {
                    total += n;
                    if (total > limit) throw invalid(avatar, "图片大小超过限制");
                    digest.update(buffer, 0, n); out.write(buffer, 0, n);
                }
            }
            if (total == 0) throw invalid(avatar, "文件不能为空");
            String hash = HexFormat.of().formatHex(digest.digest());
            if (!avatar && !optimizeExisting) {
                var existing = assets.findReusableImage(hash);
                if (existing.isPresent()) return result(existing.get(), total, "reused");
            }
            // Shared assets always use the product display policy so a favicon
            // uploaded first cannot make a reused product image too small.
            var processed = vips.process(input, storage.directory().resolve(".image-work"),
                    avatar ? VipsImageProcessor.Purpose.AVATAR : VipsImageProcessor.Purpose.PRODUCT);
            try {
                if (optimizeExisting && processed.animated()) throw new ImageOptimizationSkipped("animated", avatar ? "动画头像保持原样" : "动画图片保持原样");
                if (optimizeExisting && processed.display().size() >= total)
                    throw new ImageOptimizationSkipped("no_savings", "处理后展示文件没有变小");
                var registered = upgradeAssetId == null
                        ? assets.registerImage(hash, file.getOriginalFilename(), RequestContext.getUserId(), owner, processed)
                        : assets.upgradeLegacyImage(upgradeAssetId, hash, file.getOriginalFilename(), RequestContext.getUserId(), processed);
                return result(registered, total, registered.reused() ? "reused" : processed.reason());
            } finally {
                try { processed.close(); } catch (IOException failure) { log.warn("Image work cleanup failed: {}", failure.getMessage()); }
            }
        } catch (IOException | NoSuchAlgorithmException failure) {
            log.warn("Image processing failed: {}", failure.getMessage());
            if (optimizeExisting)
                throw new BusinessException(ErrorCode.BAD_REQUEST, (avatar ? "头像" : "图片") + "优化失败：" + failure.getMessage());
            throw invalid(avatar, "图片无法解码或处理失败：" + failure.getMessage());
        } finally {
            if (input != null) try { Files.deleteIfExists(input); } catch (IOException failure) { log.warn("Image input cleanup failed"); }
        }
    }

    private UploadedImage result(MediaAssetRegistry.ImageRegistration value, long inputSize, String reason) {
        if ("color_unspecified".equals(value.processingReason())) reason = "color_unspecified";
        return new UploadedImage(prefix + "/" + value.displayPath(), value.displayPath(), inputSize, value.displaySize(),
                reason, value.id(), value.reused(), url(value.thumbnailPath()), url(value.clearPath()));
    }
    private String url(String path) { return path == null ? null : prefix + "/" + path; }

    private void validate(MultipartFile file, boolean avatar, boolean favicon, boolean optimizeExisting) {
        if (file == null || file.isEmpty()) throw validationFailure(avatar, optimizeExisting, "source_missing", "原头像文件不存在");
        long limit = optimizeExisting ? EXISTING_IMAGE_LIMIT : avatar || favicon ? ACCOUNT_LIMIT : ADMIN_LIMIT;
        if (file.getSize() > limit) {
            if (avatar && !optimizeExisting) throw new BusinessException(ErrorCode.AVATAR_SIZE_EXCEEDED, "头像大小不能超过 5MB");
            if (optimizeExisting) throw new ImageOptimizationSkipped("source_too_large", avatar ? "原头像超过图片处理上限" : "原图片超过处理上限");
            throw new BusinessException(ErrorCode.BAD_REQUEST, favicon ? "网站图标大小不能超过 5MB" : "图片大小不能超过 10MB");
        }
        String name = file.getOriginalFilename();
        int dot = name == null ? -1 : name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
        if (!(optimizeExisting ? FORMATS : avatar || favicon ? ACCOUNT_FORMATS : FORMATS).contains(extension))
            throw validationFailure(avatar, optimizeExisting, "unsupported_format", optimizeExisting
                    ? (avatar ? "原头像格式不支持优化" : "原图片格式不支持优化") : "不支持的图片格式，仅支持 JPG/PNG/GIF/WebP/BMP/AVIF/HEIC/HEIF");
        String mime = file.getContentType();
        boolean generic = mime == null || mime.isBlank() || mime.equalsIgnoreCase("application/octet-stream");
        boolean png = (extension.equals(".png") || extension.equals(".apng"))
                && ("image/png".equalsIgnoreCase(mime) || "image/apng".equalsIgnoreCase(mime));
        if (!generic && !png && !expectedMime(extension).equalsIgnoreCase(mime))
            throw validationFailure(avatar, optimizeExisting, "unsupported_or_invalid", avatar ? "原头像文件类型与扩展名不匹配" : "原图片文件类型与扩展名不匹配");
        try (var stream = file.getInputStream()) {
            byte[] header = stream.readNBytes(16);
            boolean valid = switch (extension) {
                case ".jpg", ".jpeg" -> starts(header, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff}, 0);
                case ".png", ".apng" -> starts(header, new byte[]{(byte) 137,80,78,71,13,10,26,10}, 0);
                case ".gif" -> starts(header, new byte[]{'G','I','F','8'}, 0);
                case ".webp" -> starts(header, new byte[]{'R','I','F','F'}, 0) && starts(header, new byte[]{'W','E','B','P'}, 8);
                case ".bmp" -> starts(header, new byte[]{'B','M'}, 0);
                case ".avif", ".heic", ".heif" -> starts(header, new byte[]{'f','t','y','p'}, 4);
                default -> false;
            };
            if (!valid) throw validationFailure(avatar, optimizeExisting, "unsupported_or_invalid", avatar ? "原头像文件内容与扩展名不匹配" : "原图片文件内容与扩展名不匹配");
        } catch (IOException exception) { throw validationFailure(avatar, optimizeExisting, "source_missing", avatar ? "原头像文件无法读取" : "原图片文件无法读取"); }
    }
    private static RuntimeException validationFailure(boolean avatar, boolean optimizeExisting, String reason, String detail) {
        return optimizeExisting ? new ImageOptimizationSkipped(reason, detail) : invalid(avatar, detail);
    }
    private static String expectedMime(String extension) {
        return switch (extension) {
            case ".jpg", ".jpeg" -> "image/jpeg"; case ".png" -> "image/png"; case ".apng" -> "image/apng";
            case ".gif" -> "image/gif"; case ".webp" -> "image/webp"; case ".bmp" -> "image/bmp";
            case ".avif" -> "image/avif"; case ".heic" -> "image/heic"; case ".heif" -> "image/heif"; default -> "";
        };
    }
    private static boolean starts(byte[] data, byte[] prefix, int offset) {
        if (data.length < offset + prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (data[offset + i] != prefix[i]) return false;
        return true;
    }
    private static BusinessException invalid(boolean avatar, String detail) {
        return avatar ? new BusinessException(ErrorCode.AVATAR_FORMAT_INVALID, "头像格式无效") : new BusinessException(ErrorCode.BAD_REQUEST, detail);
    }

    public record UploadedImage(String url, String relativePath, long originalSize, long storedSize, String reason,
                                UUID assetId, boolean reused, String thumbnailUrl, String clearUrl) {
        public UploadedImage(String url, String relativePath, long originalSize, long storedSize, String reason) {
            this(url, relativePath, originalSize, storedSize, reason, null, false, null, null);
        }
        public UploadedImage(String url, String relativePath) { this(url, relativePath, 0, 0, ""); }
    }

    public record AvatarOptimizationResult(UploadedImage image, String skippedReason, String message) {
        static AvatarOptimizationResult skipped(String reason, String message) { return new AvatarOptimizationResult(null, reason, message); }
        public boolean skipped() { return image == null; }
    }

    public record ImageOptimizationResult(UploadedImage image, String skippedReason, String message) {
        static ImageOptimizationResult skipped(String reason, String message) { return new ImageOptimizationResult(null, reason, message); }
        public boolean skipped() { return image == null; }
    }

    private static final class ImageOptimizationSkipped extends RuntimeException {
        private final String reason;
        private ImageOptimizationSkipped(String reason, String message) { super(message); this.reason = reason; }
    }

    private static final class PathMultipartFile implements MultipartFile {
        private final Path path;
        private final String name;
        private PathMultipartFile(Path path, String name) { this.path = path; this.name = name; }
        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return name; }
        @Override public String getContentType() { return expectedMime(extension(name)); }
        @Override public boolean isEmpty() { try { return Files.size(path) == 0; } catch (IOException ignored) { return true; } }
        @Override public long getSize() { try { return Files.size(path); } catch (IOException failure) { return 0; } }
        @Override public byte[] getBytes() throws IOException { return Files.readAllBytes(path); }
        @Override public java.io.InputStream getInputStream() throws IOException { return Files.newInputStream(path); }
        @Override public void transferTo(File destination) throws IOException {
            Files.copy(path, destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String extension(String filename) {
        int dot = filename == null ? -1 : filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot).toLowerCase(Locale.ROOT);
    }
}
