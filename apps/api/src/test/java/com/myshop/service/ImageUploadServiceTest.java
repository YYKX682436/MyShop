package com.myshop.service;

import com.myshop.constant.ErrorCode;
import com.myshop.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImageUploadServiceTest {
    @TempDir Path directory;
    UploadStorage storage;
    VipsImageProcessor vips;
    MediaAssetRegistry assets;
    ImageUploadService service;

    @BeforeEach void setup() {
        storage = new UploadStorage(directory.resolve("uploads").toString()); storage.initialize();
        vips = mock(VipsImageProcessor.class); assets = mock(MediaAssetRegistry.class);
        service = new ImageUploadService(storage, vips, assets, "/api/uploads");
    }

    @Test void invalidTypeSizeAndForgedSignatureAreRejectedBeforeProcessing() {
        UUID owner = UUID.randomUUID();
        assertEquals(ErrorCode.AVATAR_FORMAT_INVALID, assertThrows(BusinessException.class, () -> service.uploadAvatar(
                new MockMultipartFile("file", "avatar.gif", "image/gif", new byte[]{'G','I','F','8'}), owner)).getCode());
        assertEquals(ErrorCode.AVATAR_SIZE_EXCEEDED, assertThrows(BusinessException.class, () -> service.uploadAvatar(
                new MockMultipartFile("file", "avatar.png", "image/png", new byte[5 * 1024 * 1024 + 1]), owner)).getCode());
        assertThrows(BusinessException.class, () -> service.uploadAdminImage(new MockMultipartFile("file", "bad.jpg", "image/jpeg", "fake".getBytes())));
        assertThrows(BusinessException.class, () -> service.uploadSiteFavicon(new MockMultipartFile("file", "bad.webp", "image/webp", "RIFF0000NOPE".getBytes())));
        assertThrows(BusinessException.class, () -> service.uploadAvatar(new MockMultipartFile("file", "bad.png", "image/jpeg", png()), owner));
        verifyNoInteractions(vips, assets);
    }

    @Test void accountImageRegistersWithItsOwnerAndCleansInputAndProcessorFiles() throws Exception {
        UUID owner = UUID.randomUUID(), id = UUID.randomUUID();
        var processed = mock(VipsImageProcessor.Processed.class);
        when(processed.reason()).thenReturn("compressed");
        when(vips.process(any(Path.class), any(Path.class), eq(VipsImageProcessor.Purpose.AVATAR))).thenReturn(processed);
        when(assets.registerImage(anyString(), eq("portrait.png"), isNull(), eq(owner), eq(processed)))
                .thenReturn(new MediaAssetRegistry.ImageRegistration(id, "avatars/managed-display.webp", "avatars/managed-thumb.webp", "avatars/managed-clear.webp", 8, 5, false));
        var uploaded = service.uploadAvatar(new MockMultipartFile("file", "portrait.png", "image/png", png()), owner);
        assertEquals(id, uploaded.assetId()); assertFalse(uploaded.reused());
        assertEquals("/api/uploads/avatars/managed-display.webp", uploaded.url());
        assertEquals("/api/uploads/avatars/managed-clear.webp", uploaded.clearUrl());
        verify(processed).close();
        try (var files = Files.list(storage.directory())) { assertFalse(files.anyMatch(p -> p.getFileName().toString().startsWith(".image-input-"))); }
    }

    @Test void oldAvatarOptimizationUsesAvatarPurposeAndPublishesAnIndependentOwnedAsset() throws Exception {
        UUID owner = UUID.randomUUID(), id = UUID.randomUUID();
        Path old = directory.resolve("uploads/avatars/avatar-old.png");
        Files.createDirectories(old.getParent()); Files.write(old, png());
        var processed = mock(VipsImageProcessor.Processed.class);
        when(processed.animated()).thenReturn(false);
        when(processed.display()).thenReturn(new VipsImageProcessor.Variant(Path.of("unused-display.webp"), "image/webp", 1, 1, 4));
        when(processed.reason()).thenReturn("compressed");
        when(vips.process(any(Path.class), any(Path.class), eq(VipsImageProcessor.Purpose.AVATAR))).thenReturn(processed);
        when(assets.registerImage(anyString(), eq("avatar-old.png"), isNull(), eq(owner), eq(processed)))
                .thenReturn(new MediaAssetRegistry.ImageRegistration(id, "avatars/managed-display.webp", "avatars/managed-thumb.webp",
                        "avatars/managed-clear.webp", 8, 4, false, "compressed"));

        var result = service.optimizeExistingAvatar(old, "avatar-old.png", owner);

        assertFalse(result.skipped()); assertEquals(id, result.image().assetId());
        assertEquals("/api/uploads/avatars/managed-display.webp", result.image().url());
        verify(vips).process(any(Path.class), any(Path.class), eq(VipsImageProcessor.Purpose.AVATAR));
        verify(processed).close(); assertTrue(Files.exists(old));
    }

    @Test void oldAvatarOptimizationSkipsAnimationAndNoSavingsBeforeRegistering() throws Exception {
        UUID owner = UUID.randomUUID();
        Path old = directory.resolve("uploads/avatars/avatar-old.png");
        Files.createDirectories(old.getParent()); Files.write(old, png());
        var noSavings = mock(VipsImageProcessor.Processed.class);
        when(noSavings.animated()).thenReturn(false);
        when(noSavings.display()).thenReturn(new VipsImageProcessor.Variant(Path.of("unused.webp"), "image/webp", 1, 1, 8));
        when(vips.process(any(Path.class), any(Path.class), eq(VipsImageProcessor.Purpose.AVATAR))).thenReturn(noSavings);
        var skippedSize = service.optimizeExistingAvatar(old, "avatar-old.png", owner);
        assertTrue(skippedSize.skipped()); assertEquals("no_savings", skippedSize.skippedReason());
        verify(noSavings).close(); verify(assets, never()).registerImage(anyString(), anyString(), any(), any(), any());

        Path gif = old.resolveSibling("avatar-old.gif");
        byte[] animatedGif = new byte[]{'G','I','F','8','9','a',1,0,1,0,0,0,0,0,0,0};
        Files.write(gif, animatedGif);
        var animated = mock(VipsImageProcessor.Processed.class);
        when(animated.animated()).thenReturn(true);
        when(animated.display()).thenReturn(new VipsImageProcessor.Variant(Path.of("unused.gif"), "image/gif", 1, 1, 2));
        when(vips.process(any(Path.class), any(Path.class), eq(VipsImageProcessor.Purpose.AVATAR))).thenReturn(animated);
        var skippedAnimation = service.optimizeExistingAvatar(gif, "avatar-old.gif", owner);
        assertTrue(skippedAnimation.skipped()); assertEquals("animated", skippedAnimation.skippedReason());
        verify(animated).close(); verify(assets, never()).registerImage(anyString(), anyString(), any(), any(), any());
        assertTrue(Files.exists(old)); assertTrue(Files.exists(gif));
    }

    @Test void oldAvatarProcessingFailureKeepsTheSourceAndDoesNotRegisterAReplacement() throws Exception {
        UUID owner = UUID.randomUUID();
        Path old = directory.resolve("uploads/avatars/avatar-old.png");
        Files.createDirectories(old.getParent()); Files.write(old, png());
        when(vips.process(any(Path.class), any(Path.class), eq(VipsImageProcessor.Purpose.AVATAR)))
                .thenThrow(new java.io.IOException("fixture decoder failure"));
        BusinessException failure = assertThrows(BusinessException.class,
                () -> service.optimizeExistingAvatar(old, "avatar-old.png", owner));
        assertTrue(failure.getMessage().contains("fixture decoder failure"));
        assertTrue(Files.exists(old)); verify(assets, never()).registerImage(anyString(), anyString(), any(), any(), any());
    }

    @Test void previouslyRegisteredSharedBytesAreReusedWithoutReprocessing() {
        UUID id = UUID.randomUUID();
        when(assets.findReusableImage(anyString())).thenReturn(Optional.of(new MediaAssetRegistry.ImageRegistration(
                id, "images/existing-display.webp", "images/existing-thumb.webp", "images/existing-clear.webp", 8, 5, true)));
        var uploaded = service.uploadAdminImage(new MockMultipartFile("file", "again.png", "image/png", png()));
        assertTrue(uploaded.reused()); assertEquals(id, uploaded.assetId()); assertEquals("reused", uploaded.reason());
        verifyNoInteractions(vips);
    }

    @Test void reusedImageKeepsItsColorUnspecifiedWarning() {
        UUID id = UUID.randomUUID();
        when(assets.findReusableImage(anyString())).thenReturn(Optional.of(new MediaAssetRegistry.ImageRegistration(
                id, "images/existing-display.webp", "images/existing-thumb.webp", "images/existing-clear.webp", 8, 5, true, "color_unspecified")));
        var uploaded = service.uploadAdminImage(new MockMultipartFile("file", "again.png", "image/png", png()));
        assertTrue(uploaded.reused()); assertEquals("color_unspecified", uploaded.reason());
        verifyNoInteractions(vips);
    }

    @Test void processorFailureIsReportedAndTemporaryInputIsRemoved() throws Exception {
        when(assets.findReusableImage(anyString())).thenReturn(Optional.empty());
        when(vips.process(any(Path.class), any(Path.class), eq(VipsImageProcessor.Purpose.PRODUCT))).thenThrow(new java.io.IOException("解码失败"));
        BusinessException failure = assertThrows(BusinessException.class, () -> service.uploadSiteFavicon(
                new MockMultipartFile("file", "site.png", "image/png", png())));
        assertTrue(failure.getMessage().contains("解码失败"));
        try (var files = Files.list(storage.directory())) { assertFalse(files.anyMatch(p -> p.getFileName().toString().startsWith(".image-input-"))); }
    }

    @Test void heicAvifAndApngReachTheDecoderAfterSignatureCheck() throws Exception {
        when(assets.findReusableImage(anyString())).thenReturn(Optional.empty());
        when(vips.process(any(Path.class), any(Path.class), eq(VipsImageProcessor.Purpose.PRODUCT))).thenThrow(new java.io.IOException("fixture decoder"));
        byte[] box = new byte[]{0,0,0,16,'f','t','y','p','a','v','i','f',0,0,0,0};
        for (String format : new String[]{"avif", "heic", "heif"}) {
            var e = assertThrows(BusinessException.class, () -> service.uploadAdminImage(new MockMultipartFile("file", "sample." + format, "image/" + format, box)));
            assertTrue(e.getMessage().contains("fixture decoder"));
        }
        var apng = assertThrows(BusinessException.class, () -> service.uploadAdminImage(new MockMultipartFile("file", "motion.apng", "image/apng", png())));
        assertTrue(apng.getMessage().contains("fixture decoder"));
        verify(vips, times(4)).process(any(Path.class), any(Path.class), eq(VipsImageProcessor.Purpose.PRODUCT));
    }
    private static byte[] png() { return new byte[]{(byte) 137,80,78,71,13,10,26,10}; }
}
