package com.myshop.service;

import com.myshop.constant.ErrorCode;
import com.myshop.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageUploadServiceTest {

    @TempDir
    Path tempDirectory;

    private UploadStorage uploadStorage;
    private ImageUploadService service;

    @BeforeEach
    void setUp() {
        uploadStorage = new UploadStorage(tempDirectory.resolve("uploads").toString());
        uploadStorage.initialize();
        service = new ImageUploadService(uploadStorage, "/api/uploads");
    }

    @Test
    void avatarUploadUsesGeneratedAvatarPathAndPreservesBytes() throws IOException {
        byte[] bytes = pngBytes();
        ImageUploadService.UploadedImage uploaded = service.uploadAvatar(
                new MockMultipartFile("file", "portrait.png", "image/png", bytes));

        assertTrue(uploaded.url().startsWith("/api/uploads/avatars/avatar-"));
        assertTrue(uploaded.relativePath().matches("avatars/avatar-[0-9a-f-]+\\.png"));
        assertArrayEquals(bytes, Files.readAllBytes(uploadStorage.resolve(uploaded.relativePath())));
    }

    @Test
    void avatarRejectsUnsupportedTypeSizeAndForgedMagicBytes() {
        BusinessException unsupported = assertThrows(BusinessException.class, () -> service.uploadAvatar(
                new MockMultipartFile("file", "portrait.gif", "image/gif", new byte[]{0x47, 0x49, 0x46, 0x38})));
        assertEquals(ErrorCode.AVATAR_FORMAT_INVALID, unsupported.getCode());

        BusinessException oversized = assertThrows(BusinessException.class, () -> service.uploadAvatar(
                new MockMultipartFile("file", "portrait.png", "image/png", new byte[5 * 1024 * 1024 + 1])));
        assertEquals(ErrorCode.AVATAR_SIZE_EXCEEDED, oversized.getCode());

        BusinessException forged = assertThrows(BusinessException.class, () -> service.uploadAvatar(
                new MockMultipartFile("file", "portrait.jpg", "image/jpeg", "not-an-image".getBytes())));
        assertEquals(ErrorCode.AVATAR_FORMAT_INVALID, forged.getCode());

        BusinessException mismatched = assertThrows(BusinessException.class, () -> service.uploadAvatar(
                new MockMultipartFile("file", "portrait.png", "image/jpeg", pngBytes())));
        assertEquals(ErrorCode.AVATAR_FORMAT_INVALID, mismatched.getCode());
    }

    @Test
    void managedAvatarDeletionRejectsExternalAndTraversalUrls() throws IOException {
        String filename = "avatar-" + UUID.randomUUID() + ".png";
        Path managed = uploadStorage.resolve("avatars/" + filename);
        Files.createDirectories(managed.getParent());
        Files.write(managed, pngBytes());

        assertTrue(service.deleteManagedAvatar("/api/uploads/avatars/" + filename));
        assertFalse(Files.exists(managed));

        Path outside = tempDirectory.resolve("outside.txt");
        Files.writeString(outside, "must remain");
        assertFalse(service.deleteManagedAvatar("/api/uploads/avatars/../../outside.txt"));
        assertFalse(service.deleteManagedAvatar("https://cdn.example/avatar.png"));
        assertEquals("must remain", Files.readString(outside));
        assertNull(service.extractManagedAvatarPath("/api/uploads/avatars/avatar-../../outside.txt"));
    }

    @Test
    void webpRequiresBothRiffAndWebpMagicBytes() {
        byte[] validWebp = new byte[]{
                0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00,
                0x57, 0x45, 0x42, 0x50
        };
        assertTrue(service.uploadAvatar(new MockMultipartFile(
                "file", "portrait.webp", "image/webp", validWebp)).url().contains(".webp"));

        BusinessException forged = assertThrows(BusinessException.class, () -> service.uploadAvatar(
                new MockMultipartFile("file", "portrait.webp", "image/webp",
                        new byte[]{0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x4E, 0x4F, 0x50, 0x45})));
        assertEquals(ErrorCode.AVATAR_FORMAT_INVALID, forged.getCode());
    }

    private static byte[] pngBytes() {
        return new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00};
    }
}
