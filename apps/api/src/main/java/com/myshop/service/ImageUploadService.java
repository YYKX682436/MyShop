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
        return upload(file, true, false, ownerId);
    }

    private UploadedImage upload(MultipartFile file, boolean avatar, boolean favicon, UUID owner) {
        validate(file, avatar, favicon);
        Path input = null;
        try {
            input = Files.createTempFile(storage.directory(), ".image-input-", ".tmp");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0, limit = avatar || favicon ? ACCOUNT_LIMIT : ADMIN_LIMIT;
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
            if (!avatar) {
                var existing = assets.findReusableImage(hash);
                if (existing.isPresent()) return result(existing.get(), total, "reused");
            }
            // Shared assets always use the product display policy so a favicon
            // uploaded first cannot make a reused product image too small.
            var processed = vips.process(input, storage.directory().resolve(".image-work"),
                    avatar ? VipsImageProcessor.Purpose.AVATAR : VipsImageProcessor.Purpose.PRODUCT);
            try {
                var registered = assets.registerImage(hash, file.getOriginalFilename(), RequestContext.getUserId(), owner, processed);
                return result(registered, total, registered.reused() ? "reused" : processed.reason());
            } finally {
                try { processed.close(); } catch (IOException failure) { log.warn("Image work cleanup failed: {}", failure.getMessage()); }
            }
        } catch (IOException | NoSuchAlgorithmException failure) {
            log.warn("Image processing failed: {}", failure.getMessage());
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

    private void validate(MultipartFile file, boolean avatar, boolean favicon) {
        if (file == null || file.isEmpty()) throw invalid(avatar, "文件不能为空");
        long limit = avatar || favicon ? ACCOUNT_LIMIT : ADMIN_LIMIT;
        if (file.getSize() > limit) {
            if (avatar) throw new BusinessException(ErrorCode.AVATAR_SIZE_EXCEEDED, "头像大小不能超过 5MB");
            throw new BusinessException(ErrorCode.BAD_REQUEST, favicon ? "网站图标大小不能超过 5MB" : "图片大小不能超过 10MB");
        }
        String name = file.getOriginalFilename();
        int dot = name == null ? -1 : name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
        if (!(avatar || favicon ? ACCOUNT_FORMATS : FORMATS).contains(extension))
            throw invalid(avatar, "不支持的图片格式，仅支持 JPG/PNG/GIF/WebP/BMP/AVIF/HEIC/HEIF");
        String mime = file.getContentType();
        boolean generic = mime == null || mime.isBlank() || mime.equalsIgnoreCase("application/octet-stream");
        boolean png = (extension.equals(".png") || extension.equals(".apng"))
                && ("image/png".equalsIgnoreCase(mime) || "image/apng".equalsIgnoreCase(mime));
        if (!generic && !png && !expectedMime(extension).equalsIgnoreCase(mime)) throw invalid(avatar, "文件类型与扩展名不匹配");
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
            if (!valid) throw invalid(avatar, "文件内容与扩展名不匹配，疑似伪造文件");
        } catch (IOException exception) { throw invalid(avatar, "无法读取图片"); }
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
}
