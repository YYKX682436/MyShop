package com.myshop.service;

import com.myshop.context.RequestContext;
import com.myshop.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Runs the real tus protocol library against isolated files; the asset repository is stubbed. */
class TusVideoServiceTest {
    @TempDir Path directory;
    private TusVideoService service;

    @AfterEach void cleanup() {
        RequestContext.clear();
        if (service != null) service.stop();
    }

    @Test void resumesBoundSessionWithOffsetsAndEnforcesOwnerAndChunkLimit() throws Exception {
        UploadStorage publicStorage = new UploadStorage(directory.resolve("public").toString());
        publicStorage.initialize();
        PrivateUploadStorage privateStorage = new PrivateUploadStorage(directory.resolve("private").toString(), publicStorage);
        privateStorage.initialize();
        MediaAssetRegistry registry = mock(MediaAssetRegistry.class);
        VideoFileProcessor processor = mock(VideoFileProcessor.class);
        UUID owner = UUID.randomUUID(), asset = UUID.randomUUID();
        AtomicReference<String> uri = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1), cleaned = new CountDownLatch(1);
        when(registry.createVideo("lesson.mp4", owner, 8)).thenReturn(asset);
        when(registry.findVideo(eq(asset), eq(owner))).thenAnswer(invocation -> Optional.of(
                new MediaAssetRegistry.VideoSession(asset, owner, uri.get(), "UPLOADING", "lesson.mp4", 8, null, null)));
        doAnswer(invocation -> { uri.set(invocation.getArgument(2)); return null; })
                .when(registry).bindVideoUpload(eq(asset), eq(owner), anyString(), eq(8L));
        when(registry.findVideoByUploadUri(anyString(), any(UUID.class))).thenAnswer(invocation -> {
            if (!owner.equals(invocation.getArgument(1)) || !invocation.getArgument(0).equals(uri.get())) return Optional.empty();
            return Optional.of(new MediaAssetRegistry.VideoSession(asset, owner, uri.get(), "UPLOADING", "lesson.mp4", 8, null, null));
        });
        when(processor.process(any(Path.class), eq(asset))).thenAnswer(invocation -> {
            assertArrayEquals("abcdefgh".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(invocation.getArgument(0)));
            return new VideoFileProcessor.Result("videos/" + asset + ".mp4", 8, 1000, "a".repeat(64));
        });
        when(registry.markVideoReady(eq(asset), anyString(), anyString(), anyLong(), anyLong(), anyString(), anyString()))
                .thenAnswer(invocation -> { completed.countDown(); return new MediaAssetRegistry.VideoCompletion(asset, "videos/" + asset + ".mp4", false); });
        doAnswer(invocation -> { cleaned.countDown(); return null; })
                .when(registry).markReadyVideoCleanupComplete(asset, owner);
        service = new TusVideoService(privateStorage, registry, processor, 8, 4);
        RequestContext.set(new RequestContext.UserInfo(owner, "admin", "ADMIN"));
        assertEquals(asset, service.create("lesson.mp4", 8).id());
        assertThrows(BusinessException.class, () -> service.create("too-big.mp4", 9));

        MockHttpServletRequest post = request("POST", "/api/admin/media/tus");
        post.addHeader("Upload-Length", "8");
        post.addHeader("Upload-Metadata", "asset_id " + Base64.getEncoder().encodeToString(asset.toString().getBytes(StandardCharsets.UTF_8)));
        MockHttpServletResponse created = new MockHttpServletResponse();
        service.processTus(post, created);
        assertEquals(201, created.getStatus());
        assertNotNull(uri.get());
        assertEquals(uri.get(), created.getHeader("Location"));

        MockHttpServletRequest tooLarge = patch(uri.get(), 0, "abcde");
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        service.processTus(tooLarge, rejected);
        assertEquals(413, rejected.getStatus());

        MockHttpServletResponse first = new MockHttpServletResponse();
        service.processTus(patch(uri.get(), 0, "abcd"), first);
        assertEquals(204, first.getStatus());
        assertEquals("4", first.getHeader("Upload-Offset"));

        // A different signed-in administrator must not discover or resume the session.
        RequestContext.set(new RequestContext.UserInfo(UUID.randomUUID(), "other", "ADMIN"));
        assertThrows(BusinessException.class, () -> service.processTus(request("HEAD", uri.get()), new MockHttpServletResponse()));
        RequestContext.set(new RequestContext.UserInfo(owner, "admin", "ADMIN"));
        MockHttpServletResponse resumed = new MockHttpServletResponse();
        service.processTus(request("HEAD", uri.get()), resumed);
        assertEquals(204, resumed.getStatus());
        assertEquals("4", resumed.getHeader("Upload-Offset"));

        MockHttpServletResponse second = new MockHttpServletResponse();
        service.processTus(patch(uri.get(), 4, "efgh"), second);
        assertEquals(204, second.getStatus());
        assertEquals("8", second.getHeader("Upload-Offset"));
        assertTrue(completed.await(5, TimeUnit.SECONDS), "completed upload should be processed asynchronously");
        assertTrue(cleaned.await(5, TimeUnit.SECONDS), "completed upload should remove its temporary files before teardown");
    }

    @Test void failedDatabaseBindingDoesNotCommitTheTusCreationResponse() throws Exception {
        UploadStorage publicStorage = new UploadStorage(directory.resolve("public").toString());
        publicStorage.initialize();
        PrivateUploadStorage privateStorage = new PrivateUploadStorage(directory.resolve("private").toString(), publicStorage);
        privateStorage.initialize();
        MediaAssetRegistry registry = mock(MediaAssetRegistry.class);
        UUID owner = UUID.randomUUID(), asset = UUID.randomUUID();
        when(registry.findVideo(asset, owner)).thenReturn(Optional.of(
                new MediaAssetRegistry.VideoSession(asset, owner, null, "UPLOADING", "lesson.mp4", 8, null)));
        doThrow(new IllegalStateException("database bind failed")).when(registry).bindVideoUpload(eq(asset), eq(owner), anyString(), eq(8L));
        service = new TusVideoService(privateStorage, registry, mock(VideoFileProcessor.class), 8, 4);
        RequestContext.set(new RequestContext.UserInfo(owner, "admin", "ADMIN"));
        MockHttpServletRequest post = request("POST", "/api/admin/media/tus");
        post.addHeader("Upload-Length", "8");
        post.addHeader("Upload-Metadata", "asset_id " + Base64.getEncoder().encodeToString(asset.toString().getBytes(StandardCharsets.UTF_8)));
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThrows(IllegalStateException.class, () -> service.processTus(post, response));
        assertFalse(response.isCommitted());
        assertNotEquals(201, response.getStatus());
        assertNull(response.getHeader("Location"));
    }

    @Test void cancellingWhileTheProcessingTransitionFailsStillCleansTheTusFile() throws Exception {
        UploadStorage publicStorage = new UploadStorage(directory.resolve("public").toString());
        publicStorage.initialize();
        PrivateUploadStorage privateStorage = new PrivateUploadStorage(directory.resolve("private").toString(), publicStorage);
        privateStorage.initialize();
        MediaAssetRegistry registry = mock(MediaAssetRegistry.class);
        UUID owner = UUID.randomUUID(), asset = UUID.randomUUID();
        AtomicReference<String> uri = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        CountDownLatch transitioning = new CountDownLatch(1), resume = new CountDownLatch(1), cleaned = new CountDownLatch(1);
        when(registry.findVideo(asset, owner)).thenAnswer(invocation -> Optional.of(
                new MediaAssetRegistry.VideoSession(asset, owner, uri.get(), "UPLOADING", "lesson.mp4", 1, null)));
        when(registry.findVideoIncludingDeleted(asset, owner)).thenAnswer(invocation -> Optional.of(
                new MediaAssetRegistry.VideoSession(asset, owner, uri.get(), "UPLOADING", "lesson.mp4", 1,
                        cancelled.get() ? "上传已取消" : null)));
        when(registry.findVideoByUploadUri(anyString(), eq(owner))).thenAnswer(invocation -> Optional.of(
                new MediaAssetRegistry.VideoSession(asset, owner, uri.get(), "UPLOADING", "lesson.mp4", 1, null)));
        doAnswer(invocation -> { uri.set(invocation.getArgument(2)); return null; })
                .when(registry).bindVideoUpload(eq(asset), eq(owner), anyString(), eq(1L));
        when(registry.cancelVideo(asset, owner)).thenAnswer(invocation -> { cancelled.set(true); return true; });
        doAnswer(invocation -> { transitioning.countDown(); assertTrue(resume.await(5, TimeUnit.SECONDS)); throw new IllegalStateException("cancelled"); })
                .when(registry).markVideoProcessing(asset);
        doAnswer(invocation -> { cleaned.countDown(); return null; }).when(registry).markVideoCleanupComplete(asset, owner);
        service = new TusVideoService(privateStorage, registry, mock(VideoFileProcessor.class), 1, 1);
        RequestContext.set(new RequestContext.UserInfo(owner, "admin", "ADMIN"));
        MockHttpServletRequest post = request("POST", "/api/admin/media/tus");
        post.addHeader("Upload-Length", "1");
        post.addHeader("Upload-Metadata", "asset_id " + Base64.getEncoder().encodeToString(asset.toString().getBytes(StandardCharsets.UTF_8)));
        service.processTus(post, new MockHttpServletResponse());
        CompletableFuture<Void> request = CompletableFuture.runAsync(() -> {
            RequestContext.set(new RequestContext.UserInfo(owner, "admin", "ADMIN"));
            try { service.processTus(patch(uri.get(), 0, "x"), new MockHttpServletResponse()); }
            catch (Exception failure) { throw new CompletionException(failure); }
            finally { RequestContext.clear(); }
        });
        assertTrue(transitioning.await(5, TimeUnit.SECONDS));
        service.cancel(asset);
        resume.countDown();
        assertThrows(CompletionException.class, request::join);
        assertTrue(cleaned.await(5, TimeUnit.SECONDS), "cancelled upload must be cleaned after the transition race");
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setContextPath("/api");
        request.setServletPath(uri.substring(4));
        request.addHeader("Tus-Resumable", "1.0.0");
        return request;
    }

    private static MockHttpServletRequest patch(String uri, long offset, String bytes) {
        MockHttpServletRequest request = request("PATCH", uri);
        request.setContentType("application/offset+octet-stream");
        request.addHeader("Upload-Offset", Long.toString(offset));
        request.setContent(bytes.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
