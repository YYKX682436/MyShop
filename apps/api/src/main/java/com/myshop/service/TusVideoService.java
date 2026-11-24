package com.myshop.service;

import com.myshop.constant.ErrorCode;
import com.myshop.context.RequestContext;
import com.myshop.exception.BusinessException;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import me.desair.tus.server.TusFileUploadService;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Authenticated tus sessions with a persistent asset record and one processing worker. */
@Slf4j
@Service
public class TusVideoService {
    private static final String TUS_PATH = "/api/admin/media/tus";
    private final TusFileUploadService tus;
    private final MediaAssetRegistry assets;
    private final PrivateUploadStorage privateStorage;
    private final VideoFileProcessor processor;
    private final long maxUploadBytes;
    private final long maxChunkBytes;
    @Value("${video.recover-on-startup:true}")
    private boolean recoverOnStartup;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "myshop-video-processing");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<UUID> queued = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Thread> running = new ConcurrentHashMap<>();
    private final Object lifecycle = new Object();

    public TusVideoService(PrivateUploadStorage privateStorage, MediaAssetRegistry assets, VideoFileProcessor processor,
            @Value("${video.max-upload-bytes:1073741824}") long maxUploadBytes,
            @Value("${video.max-chunk-bytes:8388608}") long maxChunkBytes) {
        if (maxUploadBytes < 1 || maxChunkBytes < 1)
            throw new IllegalArgumentException("invalid video upload limits");
        this.privateStorage = privateStorage;
        this.assets = assets;
        this.processor = processor;
        this.maxUploadBytes = maxUploadBytes;
        this.maxChunkBytes = Math.min(maxChunkBytes, maxUploadBytes);
        Path root = privateStorage.prepareManagedFile("video-tus/.anchor").getParent();
        this.tus = new TusFileUploadService().withStoragePath(root.toString()).withUploadUri(TUS_PATH)
                .withMaxUploadSize(maxUploadBytes).withUploadExpirationPeriod(7L * 24 * 60 * 60 * 1000)
                .disableTusExtension("creation-with-upload").disableTusExtension("concatenation")
                .disableTusExtension("cors");
    }

    public record Created(UUID id, long maxBytes, long chunkBytes) { }
    public record Status(UUID id, String status, String name, long size, String error, String url, long chunkBytes, boolean reused) { }

    public Created create(String name, long size) {
        UUID owner = owner();
        if (size < 1 || size > maxUploadBytes) throw bad("视频大小超过允许范围");
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".webm")))
            throw bad("仅支持 MP4、MOV 和 WebM 视频");
        return new Created(assets.createVideo(name, owner, size), maxUploadBytes, maxChunkBytes);
    }

    public Status status(UUID id) {
        var session = assets.findVideoIncludingDeleted(id, owner()).orElseThrow(TusVideoService::notFound);
        if ("REUSED".equals(session.status())) return statusOf(session);
        return statusOf(assets.findVideo(id, owner()).orElseThrow(TusVideoService::notFound));
    }

    public Status statusByUpload(String uri) {
        String normalized = normalizeUploadUri(uri);
        var session = assets.findVideoByUploadUriIncludingDeleted(normalized, owner()).orElseThrow(TusVideoService::notFound);
        if ("REUSED".equals(session.status())) return statusOf(session);
        return statusOf(assets.findVideoByUploadUri(normalized, owner()).orElseThrow(TusVideoService::notFound));
    }

    public void retry(UUID id) throws IOException, TusException {
        MediaAssetRegistry.VideoSession session = assets.findVideo(id, owner()).orElseThrow(TusVideoService::notFound);
        if (!"FAILED".equals(session.status()) || session.uploadUri() == null) throw bad("该视频暂不能重试");
        UploadInfo info = tus.getUploadInfo(session.uploadUri(), session.uploaderId().toString());
        if (info == null || info.isUploadInProgress()) throw bad("上传文件已不可用，请重新上传");
        schedule(session);
    }

    public void cancel(UUID id) throws IOException, TusException {
        UUID userId = owner();
        MediaAssetRegistry.VideoSession session = assets.findVideoIncludingDeleted(id, userId).orElseThrow(TusVideoService::notFound);
        if ("REUSED".equals(session.status())) {
            if (!queued.contains(id)) cleanupCancelled(session);
            return;
        }
        assets.cancelVideo(id, userId);
        Thread active = running.get(id);
        if (active != null) active.interrupt();
        if (!queued.contains(id)) cleanupCancelled(session);
    }

    public void retryReadyCleanup(UUID id) throws IOException, TusException {
        MediaAssetRegistry.VideoSession session = assets.findVideoIncludingDeleted(id, owner()).orElseThrow(TusVideoService::notFound);
        if ("READY".equals(session.status())) cleanupReady(session);
        else if ("REUSED".equals(session.status()) || "上传已取消".equals(session.processingError())) {
            if (queued.contains(id)) throw bad("视频仍在结束处理，请稍后重试清理");
            cleanupCancelled(session);
        } else throw bad("该视频暂不能清理上传临时文件");
    }

    public void processTus(HttpServletRequest request, HttpServletResponse response) throws IOException, TusException {
        String method = request.getMethod().toUpperCase(Locale.ROOT);
        if ("OPTIONS".equals(method)) {
            tus.process(request, response);
            return;
        }
        UUID userId = owner();
        String ownerKey = userId.toString();
        String path = request.getRequestURI();
        if ("POST".equals(method) && TUS_PATH.equals(path)) {
            UUID id = assetIdFromMetadata(request.getHeader("Upload-Metadata"));
            long declaredSize = parseLength(request.getHeader("Upload-Length"));
            MediaAssetRegistry.VideoSession session = assets.findVideo(id, userId).orElseThrow(TusVideoService::notFound);
            if (!"UPLOADING".equals(session.status()) || session.uploadUri() != null
                    || session.declaredSize() != declaredSize) throw bad("上传会话不可用或大小不匹配");
            if (request.getContentLengthLong() > 0) throw bad("创建上传会话时不接受文件数据");
            // Creation has a tiny response. Buffer it until the database owns
            // the new tus URI so a failed bind cannot leak a successful 201.
            ContentCachingResponseWrapper buffered = new ContentCachingResponseWrapper(response);
            tus.process(request, buffered, ownerKey);
            if (buffered.getStatus() == HttpServletResponse.SC_CREATED) {
                String uri = normalizeUploadUri(buffered.getHeader("Location"));
                try {
                    assets.bindVideoUpload(id, userId, uri, declaredSize);
                } catch (RuntimeException failure) {
                    try { tus.deleteUpload(uri, ownerKey); } catch (IOException | TusException cleanup) {
                        log.warn("Could not remove an unbound video upload: {}", cleanup.getClass().getSimpleName());
                    }
                    if (!response.isCommitted()) response.reset();
                    throw failure;
                }
            }
            buffered.copyBodyToResponse();
            return;
        }

        String uri = normalizeUploadUri(path);
        MediaAssetRegistry.VideoSession session = ("DELETE".equals(method)
                ? assets.findVideoByUploadUriIncludingDeleted(uri, userId)
                : assets.findVideoByUploadUri(uri, userId)).orElseThrow(TusVideoService::notFound);
        if ("PATCH".equals(method)) {
            long chunk = request.getContentLengthLong();
            if (chunk < 0 || chunk > maxChunkBytes) {
                response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "视频分块超过限制");
                return;
            }
        }
        if ("DELETE".equals(method)) {
            if (!"1.0.0".equals(request.getHeader("Tus-Resumable"))) {
                response.setHeader("Tus-Version", "1.0.0");
                response.sendError(HttpServletResponse.SC_PRECONDITION_FAILED, "Unsupported tus version");
                return;
            }
            assets.cancelVideo(session.id(), userId);
            Thread active = running.get(session.id());
            if (active != null) active.interrupt();
            if (!queued.contains(session.id())) cleanupCancelled(session);
            // Termination is complete or recorded for the worker to finish.
            response.setHeader("Tus-Resumable", "1.0.0");
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }
        tus.process(request, response, ownerKey);
        if (("PATCH".equals(method) || "HEAD".equals(method)) && response.getStatus() < 300
                && ("UPLOADING".equals(session.status()) || "PROCESSING".equals(session.status()))) {
            UploadInfo info = tus.getUploadInfo(uri, ownerKey);
            if (info != null && !info.isUploadInProgress()) schedule(session);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverCompletedUploads() {
        if (!recoverOnStartup) return;
        for (MediaAssetRegistry.VideoSession session : assets.findReadyVideosNeedingUploadCleanup()) {
            try { cleanupReady(session); }
            catch (IOException | TusException | RuntimeException exception) {
                log.warn("Could not clean completed video upload {}: {}", session.id(), exception.getClass().getSimpleName());
            }
        }
        for (MediaAssetRegistry.VideoSession session : assets.findCancelledVideosNeedingCleanup()) {
            try { cleanupCancelled(session); }
            catch (IOException | TusException | RuntimeException exception) {
                log.warn("Could not clean cancelled video {}: {}", session.id(), exception.getClass().getSimpleName());
            }
        }
        for (MediaAssetRegistry.VideoSession session : assets.findRecoverableVideos()) {
            if ("FAILED".equals(session.status())) continue;
            try {
                UploadInfo info = tus.getUploadInfo(session.uploadUri(), session.uploaderId().toString());
                if (info == null) assets.markVideoFailed(session.id(), "上传会话已过期，请重新上传");
                else if (!info.isUploadInProgress()) schedule(session);
            } catch (IOException | TusException | RuntimeException exception) {
                log.warn("Could not recover video {}: {}", session.id(), exception.getClass().getSimpleName());
            }
        }
    }

    /** Expire abandoned, incomplete uploads without touching queued conversions. */
    @Scheduled(fixedDelayString = "${video.upload-cleanup-interval-ms:3600000}",
            initialDelayString = "${video.upload-cleanup-interval-ms:3600000}")
    public void expireIncompleteUploads() {
        if (!recoverOnStartup) return;
        assets.expireUnboundVideoSessions();
        for (MediaAssetRegistry.VideoSession session : assets.findRecoverableVideos()) {
            if (!"UPLOADING".equals(session.status()) || queued.contains(session.id())) continue;
            try {
                UploadInfo info = tus.getUploadInfo(session.uploadUri(), session.uploaderId().toString());
                if (info == null) assets.markVideoFailed(session.id(), "上传会话已过期，请重新上传");
                else if (info.isUploadInProgress() && info.getExpirationTimestamp() != null
                        && info.getExpirationTimestamp() < System.currentTimeMillis()) {
                    tus.deleteUpload(session.uploadUri(), session.uploaderId().toString());
                    assets.markVideoFailed(session.id(), "上传会话已过期，请重新上传");
                }
            } catch (IOException | TusException | RuntimeException failure) {
                log.warn("Could not expire video upload {}: {}", session.id(), failure.getClass().getSimpleName());
            }
        }
        // The tus library also removes expired orphan files from a creation
        // that crashed before its URI was persisted. Do not sweep while a
        // completed upload is queued or processing.
        synchronized (lifecycle) {
            if (queued.isEmpty() && assets.findRecoverableVideos().stream().noneMatch(value -> "PROCESSING".equals(value.status()))) {
                try { tus.cleanup(); }
                catch (IOException failure) { log.warn("Expired tus cleanup failed: {}", failure.getClass().getSimpleName()); }
            }
        }
    }

    private void schedule(MediaAssetRegistry.VideoSession session) {
        synchronized (lifecycle) {
            if (!queued.add(session.id())) return;
            try {
                assets.markVideoProcessing(session.id());
                worker.execute(() -> processCompleted(session));
            } catch (RuntimeException exception) {
                queued.remove(session.id());
                try {
                    if (assets.findVideoIncludingDeleted(session.id(), session.uploaderId())
                            .map(value -> "上传已取消".equals(value.processingError()) || "REUSED".equals(value.status())).orElse(false))
                        cleanupCancelled(session);
                } catch (IOException | TusException | RuntimeException cleanup) {
                    exception.addSuppressed(cleanup);
                }
                throw exception;
            }
        }
    }

    private void processCompleted(MediaAssetRegistry.VideoSession session) {
        Path temporary = null;
        running.put(session.id(), Thread.currentThread());
        try {
            if (assets.findVideo(session.id(), session.uploaderId()).isEmpty()) return;
            Path workDir = privateStorage.prepareManagedFile("video-work/.anchor").getParent();
            clearPrivateWorkFiles(workDir, session.id());
            temporary = Files.createTempFile(workDir, "upload-" + session.id() + "-", ".source");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = tus.getUploadedBytes(session.uploadUri(), session.uploaderId().toString());
                 var output = Files.newOutputStream(temporary)) {
                if (input == null) throw new IOException("上传文件不完整");
                long copied = 0;
                byte[] buffer = new byte[64 * 1024];
                for (int count; (count = input.read(buffer)) != -1;) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("视频处理已中止");
                    copied += count;
                    if (copied > session.declaredSize()) throw new IOException("上传文件大小不匹配");
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
                if (copied != session.declaredSize()) throw new IOException("上传文件不完整");
            }
            String sourceHash = HexFormat.of().formatHex(digest.digest());
            if (assets.reuseVideoIfExists(session.id(), sourceHash).isPresent()) return;
            VideoFileProcessor.Result result = processor.process(temporary, session.id());
            MediaAssetRegistry.VideoCompletion completion = assets.markVideoReady(session.id(), result.storagePath(), "video/mp4", result.sizeBytes(), result.durationMs(), result.sha256(), sourceHash);
            if (!completion.reused()) {
                try { cleanupReady(session); }
                catch (IOException | TusException | RuntimeException exception) {
                    log.warn("Completed video temporary upload cleanup remains pending: {}", exception.getClass().getSimpleName());
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); // Leave PROCESSING for recovery after restart.
        } catch (Exception failure) {
            log.warn("Video processing failed for {}: {}", session.id(), failure.getClass().getSimpleName());
            try { assets.markVideoFailed(session.id(), failure.getMessage()); }
            catch (RuntimeException stateChanged) {
                log.warn("Video state changed while processing {}", session.id());
            }
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); }
            catch (IOException cleanup) { log.warn("Video temporary file cleanup failed: {}", cleanup.getClass().getSimpleName()); }
            running.remove(session.id());
            queued.remove(session.id());
            try {
                if (assets.findVideoIncludingDeleted(session.id(), session.uploaderId())
                        .map(value -> "上传已取消".equals(value.processingError()) || "REUSED".equals(value.status())).orElse(false)) {
                    Thread.interrupted(); // Permit filesystem cleanup after cancellation interrupted ffmpeg.
                    cleanupCancelled(session);
                }
            } catch (IOException | TusException | RuntimeException cleanup) {
                log.warn("Cancelled video cleanup remains pending for {}: {}", session.id(), cleanup.getClass().getSimpleName());
            }
        }
    }

    private void cleanupCancelled(MediaAssetRegistry.VideoSession session) throws IOException, TusException {
        try {
            if (session.uploadUri() != null) tus.deleteUpload(session.uploadUri(), session.uploaderId().toString());
            Path workDir = privateStorage.prepareManagedFile("video-work/.anchor").getParent();
            clearPrivateWorkFiles(workDir, session.id());
            processor.discardCancelled(session.id());
            assets.markVideoCleanupComplete(session.id(), session.uploaderId());
        } catch (IOException | TusException | RuntimeException failure) {
            try { assets.markVideoCleanupFailed(session.id(), session.uploaderId(), "视频文件清理失败，请重试"); }
            catch (RuntimeException stateFailure) { failure.addSuppressed(stateFailure); }
            throw failure;
        }
    }

    private void cleanupReady(MediaAssetRegistry.VideoSession session) throws IOException, TusException {
        try {
            if (session.uploadUri() != null) tus.deleteUpload(session.uploadUri(), session.uploaderId().toString());
            Path workDir = privateStorage.prepareManagedFile("video-work/.anchor").getParent();
            clearPrivateWorkFiles(workDir, session.id());
            assets.markReadyVideoCleanupComplete(session.id(), session.uploaderId());
        } catch (IOException | TusException | RuntimeException failure) {
            try { assets.markReadyVideoCleanupFailed(session.id(), session.uploaderId(), "视频上传临时文件清理失败，请重试"); }
            catch (RuntimeException stateFailure) { failure.addSuppressed(stateFailure); }
            throw failure;
        }
    }

    private static void clearPrivateWorkFiles(Path directory, UUID id) throws IOException {
        try (var files = Files.newDirectoryStream(directory, "upload-" + id + "-*.source")) {
            for (Path file : files) Files.deleteIfExists(file);
        }
    }

    @PreDestroy
    public void stop() { worker.shutdownNow(); }

    private Status statusOf(MediaAssetRegistry.VideoSession session) {
        if ("REUSED".equals(session.status())) {
            var target = assets.findReadyVideoForReuse(session.reusedAssetId()).orElse(null);
            if (target == null) return new Status(session.id(), "FAILED", session.originalName(), session.declaredSize(),
                    "复用的原视频已被删除，请重新上传", null, maxChunkBytes, true);
            return new Status(target.assetId(), "READY", session.originalName(), session.declaredSize(),
                    null, "/api/uploads/" + target.storagePath(), maxChunkBytes, true);
        }
        String url = "READY".equals(session.status()) ? "/api/uploads/videos/" + session.id() + ".mp4" : null;
        return new Status(session.id(), session.status(), session.originalName(), session.declaredSize(), session.processingError(), url, maxChunkBytes, false);
    }

    private long parseLength(String value) {
        try {
            long size = Long.parseLong(value);
            if (size < 1 || size > maxUploadBytes) throw bad("视频大小超过允许范围");
            return size;
        } catch (NumberFormatException exception) { throw bad("视频大小无效"); }
    }

    private static UUID assetIdFromMetadata(String metadata) {
        if (metadata == null || metadata.length() > 8192) throw bad("上传元数据无效");
        for (String entry : metadata.split(",")) {
            String[] pair = entry.trim().split(" ", 2);
            if (pair.length == 2 && "asset_id".equals(pair[0])) {
                try { return UUID.fromString(new String(Base64.getDecoder().decode(pair[1]), StandardCharsets.UTF_8)); }
                catch (IllegalArgumentException ignored) { throw bad("上传资源标识无效"); }
            }
        }
        throw bad("缺少上传资源标识");
    }

    private static String normalizeUploadUri(String uri) {
        if (uri == null || !uri.matches("^/api/admin/media/tus/[0-9a-fA-F-]{36}$")) throw bad("上传地址无效");
        return uri;
    }

    private static UUID owner() {
        UUID userId = RequestContext.getUserId();
        if (userId == null) throw new BusinessException(ErrorCode.FORBIDDEN, "请先登录管理员账号", HttpStatus.FORBIDDEN);
        return userId;
    }

    private static BusinessException bad(String message) { return new BusinessException(ErrorCode.BAD_REQUEST, message); }
    private static BusinessException notFound() { return new BusinessException(ErrorCode.NOT_FOUND, "上传会话不存在", HttpStatus.NOT_FOUND); }
}
