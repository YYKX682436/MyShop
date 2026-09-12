package com.myshop.service;

import com.myshop.constant.ErrorCode;
import com.myshop.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
    private final String urlPrefix;

    public ImageUploadService(UploadStorage uploadStorage,
                              @Value("${upload.url-prefix:/api/uploads}") String urlPrefix) {
        this.uploadStorage = uploadStorage;
        this.urlPrefix = normalizeUrlPrefix(urlPrefix);
    }

    public UploadedImage uploadAdminImage(MultipartFile file) {
        return upload(file, ADMIN_POLICY, false);
    }

    public UploadedImage uploadAvatar(MultipartFile file) {
        return upload(file, AVATAR_POLICY, true);
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
        String relativePath = policy.directoryPrefix + policy.filenamePrefix
                + UUID.randomUUID() + extension;
        Path target = uploadStorage.prepareManagedFile(relativePath);

        try {
            try (InputStream input = file.getInputStream();
                 OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW,
                         StandardOpenOption.WRITE)) {
                input.transferTo(output);
            }
            return new UploadedImage(urlPrefix + "/" + relativePath, relativePath);
        } catch (IOException | RuntimeException exception) {
            uploadStorage.deleteManagedFile(relativePath);
            log.error("Image upload failed: {}", exception.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.SERVER_ERROR, "文件上传失败");
        }
    }

    private void validate(MultipartFile file, UploadPolicy policy, boolean avatar) {
        if (file == null || file.isEmpty()) {
            throw invalidFormat(avatar, "文件不能为空");
        }
        if (file.getSize() > policy.maxSizeBytes) {
            if (avatar) {
                throw new BusinessException(ErrorCode.AVATAR_SIZE_EXCEEDED, "头像大小不能超过 5MB");
            }
            throw new BusinessException(ErrorCode.BAD_REQUEST, "图片大小不能超过 10MB");
        }

        String contentType = file.getContentType();
        if (contentType == null || !policy.contentTypes.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw invalidFormat(avatar, avatar
                    ? "头像格式无效"
                    : "不支持的图片格式，仅支持 JPG/PNG/GIF/WebP/BMP");
        }

        String extension = extensionOf(file.getOriginalFilename());
        if (!policy.extensions.contains(extension)) {
            throw invalidFormat(avatar, avatar
                    ? "头像格式无效"
                    : "不支持的文件扩展名，仅支持 jpg/png/gif/webp/bmp");
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

    public record UploadedImage(String url, String relativePath) {
    }
}
