package com.myshop.service;

import com.myshop.constant.ErrorCode;
import com.myshop.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Shared image validation and persistence boundary for administrator and
 * account-owned uploads. Controllers only choose the policy; they never
 * duplicate MIME, extension, or magic-byte checks.
 */
@Slf4j
@Service
public class ImageUploadService {

    private static final long MAX_ADMIN_FILE_SIZE_BYTES = 10L * 1024 * 1024;
    private static final long MAX_AVATAR_FILE_SIZE_BYTES = 5L * 1024 * 1024;

    private static final UploadPolicy ADMIN_POLICY = new UploadPolicy(
            MAX_ADMIN_FILE_SIZE_BYTES,
            Set.of(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp"),
            Set.of("image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp"),
            "",
            "");
    private static final UploadPolicy AVATAR_POLICY = new UploadPolicy(
            MAX_AVATAR_FILE_SIZE_BYTES,
            Set.of(".jpg", ".jpeg", ".png", ".webp"),
            Set.of("image/jpeg", "image/png", "image/webp"),
            "avatars/",
            "avatar-");
    private static final UploadPolicy FAVICON_POLICY = new UploadPolicy(
            MAX_AVATAR_FILE_SIZE_BYTES,
            Set.of(".jpg", ".jpeg", ".png", ".webp"),
            Set.of("image/jpeg", "image/png", "image/webp"),
            "", "");

    private static final Map<String, byte[][]> MAGIC_BYTES = Map.of(
            ".jpg", new byte[][]{{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}},
            ".jpeg", new byte[][]{{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}},
            ".png", new byte[][]{{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}},
            ".gif", new byte[][]{{0x47, 0x49, 0x46, 0x38}},
            ".webp", new byte[][]{
                    {(byte) 0x52, (byte) 0x49, (byte) 0x46, (byte) 0x46},
                    {0x57, 0x45, 0x42, 0x50}
            },
            ".bmp", new byte[][]{{0x42, 0x4D}}
    );

    private final UploadStorage uploadStorage;
    private final ImageProcessor imageProcessor;
    private final String urlPrefix;
    private final MediaFileGuard mediaFileGuard;

    @Autowired
    public ImageUploadService(UploadStorage uploadStorage, ImageProcessor imageProcessor,
                              @Value("${upload.url-prefix:/api/uploads}") String urlPrefix,
                              MediaFileGuard mediaFileGuard) {
        this.uploadStorage = uploadStorage;
        this.imageProcessor = imageProcessor;
        this.urlPrefix = normalizeUrlPrefix(urlPrefix);
        this.mediaFileGuard = mediaFileGuard;
    }

    // Directly constructed unit tests do not use the production database.
    public ImageUploadService(UploadStorage uploadStorage, ImageProcessor imageProcessor, String urlPrefix) {
        this(uploadStorage, imageProcessor, urlPrefix, null);
    }

    public UploadedImage uploadAdminImage(MultipartFile file) {
        return upload(file, ADMIN_POLICY, false);
    }

    public UploadedImage uploadAvatar(MultipartFile file) {
        return upload(file, AVATAR_POLICY, true);
    }

    public UploadedImage uploadSiteFavicon(MultipartFile file) {
        return upload(file, FAVICON_POLICY, false);
    }

    /**
     * Deletes only a generated avatar URL from the configured UploadStorage.
     * External URLs, traversal strings, and non-avatar files are ignored.
     */
    public boolean deleteManagedAvatar(String avatarUrl) {
        String relativePath = extractManagedAvatarPath(avatarUrl);
        return relativePath != null && uploadStorage.deleteManagedFile(relativePath);
    }

    public String extractManagedAvatarPath(String avatarUrl) {
        if (!StringUtils.hasText(avatarUrl)) return null;
        String prefix = urlPrefix + "/";
        if (!avatarUrl.startsWith(prefix)) return null;

        String relativePath = avatarUrl.substring(prefix.length());
        if (!relativePath.matches("avatars/avatar-[0-9a-fA-F-]+\\.(jpg|jpeg|png|webp)")) {
            return null;
        }
        return relativePath;
    }

    private UploadedImage upload(MultipartFile file, UploadPolicy policy, boolean avatar) {
        validate(file, policy, avatar);
        String extension = extensionOf(file.getOriginalFilename());
        final byte[] processed;
        final String canonicalExtension;
        String reason = "compressed";
        try {
            if (avatar) {
                processed = imageProcessor.process(file, extension);
                canonicalExtension = extension.equals(".jpeg") ? ".jpg" : extension;
            } else {
                var result = imageProcessor.processForStorage(file.getBytes(), extension);
                processed = result.bytes();
                canonicalExtension = result.extension();
                reason = result.reason();
            }
        } catch (IOException exception) {
            throw invalidFormat(avatar, "图片损坏或尺寸超过限制");
        }
        String identifier = UploadStorage.identifier(processed);
        String relativePath = avatar ? policy.directoryPrefix + policy.filenamePrefix + UUID.randomUUID() + extension
                : identifier + canonicalExtension;
        String displayName = file.getOriginalFilename();
        if (!avatar && !canonicalExtension.equals(extension) && displayName != null && displayName.lastIndexOf('.') >= 0)
            displayName = displayName.substring(0, displayName.lastIndexOf('.')) + canonicalExtension;
        final String outputName = displayName;
        try {
            if (!avatar && mediaFileGuard != null) {
                mediaFileGuard.exclusive(false, connection -> {
                    uploadStorage.publish(relativePath, processed, true);
                    MediaFileGuard.restorePublishedFile(connection, relativePath);
                    rememberName(identifier, outputName, relativePath);
                    return null;
                });
            } else {
                uploadStorage.publish(relativePath, processed, !avatar);
                if (!avatar) rememberName(identifier, outputName, relativePath);
            }
        } catch (IOException | RuntimeException exception) {
            log.error("Image upload failed: {}", exception.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.SERVER_ERROR, "文件上传失败");
        }
        return new UploadedImage(urlPrefix + "/" + relativePath, relativePath, file.getSize(), processed.length, reason);
    }

    private void rememberName(String identifier, String originalFilename, String relativePath) {
        try {
            uploadStorage.rememberMediaName(identifier, safeName(originalFilename, relativePath));
        } catch (IOException | RuntimeException exception) {
            log.warn("Image uploaded, but its resource name metadata could not be saved; using the filename fallback");
        }
    }

    private static String safeName(String name, String fallback) {
        if (!StringUtils.hasText(name)) return fallback;
        String clean = name.replace('\\', '/');
        clean = clean.substring(clean.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        if (clean.isEmpty()) return fallback;
        return clean.length() > 120 ? clean.substring(0, 120) : clean;
    }

    private void validate(MultipartFile file, UploadPolicy policy, boolean avatar) {
        if (file == null || file.isEmpty()) {
            throw invalidFormat(avatar, "文件不能为空");
        }
        if (file.getSize() > policy.maxSizeBytes) {
            if (avatar) {
                throw new BusinessException(ErrorCode.AVATAR_SIZE_EXCEEDED, "头像大小不能超过 5MB");
            }
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    policy == FAVICON_POLICY ? "网站图标大小不能超过 5MB" : "图片大小不能超过 10MB");
        }

        String contentType = file.getContentType();
        if (contentType == null || !policy.contentTypes.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw invalidFormat(avatar, avatar
                    ? "头像格式无效"
                    : policy == FAVICON_POLICY ? "网站图标仅支持 JPG/PNG/WebP" : "不支持的图片格式，仅支持 JPG/PNG/GIF/WebP/BMP");
        }

        String extension = extensionOf(file.getOriginalFilename());
        if (!policy.extensions.contains(extension)) {
            throw invalidFormat(avatar, avatar
                    ? "头像格式无效"
                    : policy == FAVICON_POLICY ? "网站图标仅支持 jpg/png/webp" : "不支持的文件扩展名，仅支持 jpg/png/gif/webp/bmp");
        }
        if (!contentType.equalsIgnoreCase(contentTypeFor(extension))) {
            throw invalidFormat(avatar, "文件类型与扩展名不匹配");
        }
        if (!verifyMagicBytes(file, extension)) {
            throw invalidFormat(avatar, "文件内容与扩展名不匹配，疑似伪造文件");
        }
    }

    private BusinessException invalidFormat(boolean avatar, String detail) {
        if (avatar) {
            return new BusinessException(ErrorCode.AVATAR_FORMAT_INVALID, "头像格式无效");
        }
        return new BusinessException(ErrorCode.BAD_REQUEST, detail);
    }

    private boolean verifyMagicBytes(MultipartFile file, String extension) {
        byte[][] expected = MAGIC_BYTES.get(extension);
        if (expected == null) return false;

        try (InputStream input = file.getInputStream()) {
            byte[] header = input.readNBytes(16);
            if (extension.equals(".webp")) {
                return startsWith(header, expected[0], 0) && startsWith(header, expected[1], 8);
            }
            for (byte[] magic : expected) {
                if (startsWith(header, magic, 0)) return true;
            }
            return false;
        } catch (IOException exception) {
            log.warn("Failed to read image header for validation");
            return false;
        }
    }

    private static String contentTypeFor(String extension) {
        return switch (extension) {
            case ".jpg", ".jpeg" -> "image/jpeg";
            case ".png" -> "image/png";
            case ".gif" -> "image/gif";
            case ".webp" -> "image/webp";
            case ".bmp" -> "image/bmp";
            default -> "";
        };
    }

    private static boolean startsWith(byte[] data, byte[] prefix, int offset) {
        if (offset < 0 || data.length < offset + prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if (data[offset + index] != prefix[index]) return false;
        }
        return true;
    }

    private static String extensionOf(String originalFilename) {
        if (!StringUtils.hasText(originalFilename)) return "";
        int dot = originalFilename.lastIndexOf('.');
        if (dot < 0 || dot == originalFilename.length() - 1) return "";
        return originalFilename.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static String normalizeUrlPrefix(String prefix) {
        if (!StringUtils.hasText(prefix)) return "/api/uploads";
        String normalized = prefix.trim();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized.isEmpty() ? "/api/uploads" : normalized;
    }

    private record UploadPolicy(long maxSizeBytes,
                                Set<String> extensions,
                                Set<String> contentTypes,
                                String directoryPrefix,
                                String filenamePrefix) {
    }

    public record UploadedImage(String url, String relativePath, long originalSize, long storedSize, String reason) {
        public UploadedImage(String url, String relativePath) { this(url, relativePath, 0, 0, ""); }
    }
}
