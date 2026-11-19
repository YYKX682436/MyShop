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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        service = new ImageUploadService(uploadStorage, new ImageProcessor(), "/api/uploads");
    }

    @Test
    void avatarUploadUsesGeneratedAvatarPathAndPreservesTransparency() throws IOException {
        byte[] bytes = pngBytes();
        ImageUploadService.UploadedImage uploaded = service.uploadAvatar(
                new MockMultipartFile("file", "portrait.png", "image/png", bytes));

        assertTrue(uploaded.url().startsWith("/api/uploads/avatars/avatar-"));
        assertTrue(uploaded.relativePath().matches("avatars/avatar-[0-9a-f-]+\\.png"));
        var original = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        var saved = javax.imageio.ImageIO.read(uploadStorage.resolve(uploaded.relativePath()).toFile());
        assertEquals(original.getRGB(0, 0), saved.getRGB(0, 0));
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
    void mediaNameFailureKeepsPublishedUrlAndUsesFilenameFallbackWithoutChangingBytes() throws IOException {
        byte[] png = pngBytes();
        UploadStorage metadataFailureStorage = new UploadStorage(
                tempDirectory.resolve("metadata-failure-uploads").toString()) {
            @Override
            public void rememberMediaName(String identifier, String name) throws IOException {
                throw new IOException("simulated metadata write failure");
            }
        };
        metadataFailureStorage.initialize();
        ImageUploadService metadataFailureService = new ImageUploadService(
                metadataFailureStorage, new ImageProcessor(), "/api/uploads");

        ImageUploadService.UploadedImage uploaded = metadataFailureService.uploadAdminImage(
                new MockMultipartFile("file", "photo.png", "image/png", png));
        Path published = metadataFailureStorage.resolve(uploaded.relativePath());
        byte[] publishedBytes = Files.readAllBytes(published);

        ImageUploadService.UploadedImage duplicate = metadataFailureService.uploadAdminImage(
                new MockMultipartFile("file", "renamed.png", "image/png", png));
        var listed = new MediaLibraryService(metadataFailureStorage, "/api/uploads")
                .list(1, 24, "", "desc", false);

        assertEquals(uploaded.url(), duplicate.url());
        assertArrayEquals(publishedBytes, Files.readAllBytes(published));
        assertEquals(published.getFileName().toString(), listed.items().getFirst().name());
        assertEquals(uploaded.url(), listed.items().getFirst().url());
        assertNotNull(javax.imageio.ImageIO.read(published.toFile()));
    }

    @Test
    void publishFailureStillReturnsUploadFailureAndLeavesNoImage() throws IOException {
        UploadStorage publishFailureStorage = new UploadStorage(
                tempDirectory.resolve("publish-failure-uploads").toString()) {
            @Override
            public void publish(String relativePath, byte[] bytes, boolean allowExisting) throws IOException {
                throw new IOException("simulated publish failure");
            }
        };
        publishFailureStorage.initialize();
        ImageUploadService publishFailureService = new ImageUploadService(
                publishFailureStorage, new ImageProcessor(), "/api/uploads");

        BusinessException exception = assertThrows(BusinessException.class, () -> publishFailureService.uploadAdminImage(
                new MockMultipartFile("file", "photo.png", "image/png", pngBytes())));

        assertEquals(ErrorCode.SERVER_ERROR, exception.getCode());
        try (var files = Files.list(publishFailureStorage.directory())) {
            assertFalse(files.findAny().isPresent());
        }
    }

    @Test
    void webpRequiresBothRiffAndWebpMagicBytes() {
        byte[] validWebp = java.util.Base64.getDecoder().decode("UklGRjwAAABXRUJQVlA4IDAAAAAwAgCdASoCAAIAAUAmJaACdLoB+AH4AAYeAAD+8U2v/Vn00Iv7SP/1v/qCA/SAAAA=");
        assertTrue(service.uploadAvatar(new MockMultipartFile(
                "file", "portrait.webp", "image/webp", validWebp)).url().contains(".webp"));

        BusinessException forged = assertThrows(BusinessException.class, () -> service.uploadAvatar(
                new MockMultipartFile("file", "portrait.webp", "image/webp",
                        new byte[]{0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x4E, 0x4F, 0x50, 0x45})));
        assertEquals(ErrorCode.AVATAR_FORMAT_INVALID, forged.getCode());
    }

    private static byte[] pngBytes() {
        return java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAACXBIWXMAAAPoAAAD6AG1e1JrAAAAEUlEQVR4nGP4z8DQAMIMMAYAOOgF/REzMMkAAAAASUVORK5CYII=");
    }
}
