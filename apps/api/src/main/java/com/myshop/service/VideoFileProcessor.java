package com.myshop.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Bounded, single-file conversion. The coordinator serializes calls to this processor. */
@Component
public class VideoFileProcessor {
    private final UploadStorage storage;
    private final ObjectMapper mapper;
    private final long timeoutSeconds;

    public VideoFileProcessor(UploadStorage storage, ObjectMapper mapper,
            @Value("${video.processing-timeout-seconds:14400}") long timeoutSeconds) {
        if (timeoutSeconds < 1) throw new IllegalArgumentException("video processing timeout must be positive");
        this.storage = storage;
        this.mapper = mapper;
        this.timeoutSeconds = timeoutSeconds;
    }

    public record Result(String storagePath, long sizeBytes, long durationMs, String sha256) { }
    private record Probe(String format, int videoIndex, String videoCodec, String pixelFormat, String audioCodec,
                         int width, int height, long durationMs) { }

    /** Only called after the coordinator has confirmed the asset was cancelled. */
    void discardCancelled(UUID assetId) throws IOException {
        Path target = storage.prepareManagedFile("videos/" + assetId + ".mp4");
        Files.deleteIfExists(target);
        Path workDir = storage.prepareManagedFile(".video-work/.anchor").getParent();
        try (var files = Files.newDirectoryStream(workDir, "video-" + assetId + "-*.mp4")) {
            for (Path file : files) Files.deleteIfExists(file);
        }
    }

    /** Source must be a private local file; no bytes are loaded into the Java heap. */
    public Result process(Path source, UUID assetId) throws IOException, InterruptedException {
        if (source == null || assetId == null || !Files.isRegularFile(source)) throw new IOException("视频临时文件不可用");
        Path workDir = storage.prepareManagedFile(".video-work/.anchor").getParent();
        String relativePath = "videos/" + assetId + ".mp4";
        Path published = storage.prepareManagedFile(relativePath);

        // A prior process may have published the immutable file just before
        // crashing. Validate it rather than overwriting the stable URL.
        if (Files.exists(published)) return resultForExisting(published, relativePath, workDir);

        Probe input = probe(source, workDir);
        if (!supportedContainer(input.format()) || input.videoIndex() < 0 || input.videoCodec() == null
                || input.width() < 1 || input.height() < 1 || input.width() > 8192 || input.height() > 8192
                || input.durationMs() < 1) throw new IOException("视频封装、画面尺寸或时长不受支持");
        boolean copy = "h264".equals(input.videoCodec()) && "yuv420p".equals(input.pixelFormat())
                && (input.audioCodec() == null || "aac".equals(input.audioCodec()));
        Path temporary = Files.createTempFile(workDir, "video-" + assetId + "-", ".mp4");
        try {
            List<String> command = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-hide_banner", "-v", "error", "-xerror",
                    "-protocol_whitelist", "file,pipe", "-i", source.toString(), "-map", "0:" + input.videoIndex(), "-map", "0:a:0?"));
            if (copy) command.addAll(List.of("-c:v", "copy", "-c:a", "copy"));
            else command.addAll(List.of("-c:v", "libx264", "-preset", "medium", "-crf", "20", "-pix_fmt", "yuv420p",
                    "-vf", "pad=ceil(iw/2)*2:ceil(ih/2)*2", "-c:a", "aac", "-b:a", "160k"));
            command.addAll(List.of("-movflags", "+faststart", "-f", "mp4", "-y", temporary.toString()));
            run(command, workDir, timeoutSeconds);
            Probe output = probe(temporary, workDir);
            if (!supportedMp4(output.format()) || !"h264".equals(output.videoCodec())
                    || !"yuv420p".equals(output.pixelFormat())
                    || (output.audioCodec() != null && !"aac".equals(output.audioCodec())) || output.durationMs() < 1)
                throw new IOException("生成的视频未通过播放格式检查");
            try {
                // No replacement: an immutable URL must never acquire new bytes.
                Files.createLink(published, temporary);
            } catch (FileAlreadyExistsException concurrentPublication) {
                return resultForExisting(published, relativePath, workDir);
            }
            return new Result(relativePath, Files.size(published), output.durationMs(), sha256(published));
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Result resultForExisting(Path file, String relativePath, Path workDir) throws IOException, InterruptedException {
        if (!storage.isSafePublicFile(relativePath)) throw new IOException("视频成品路径不可用");
        Probe output = probe(file, workDir);
        if (!supportedMp4(output.format()) || !"h264".equals(output.videoCodec())
                || !"yuv420p".equals(output.pixelFormat())
                || (output.audioCodec() != null && !"aac".equals(output.audioCodec())) || output.durationMs() < 1)
            throw new IOException("已发布的视频文件无效，请检查后重试");
        return new Result(relativePath, Files.size(file), output.durationMs(), sha256(file));
    }

    private Probe probe(Path file, Path workDir) throws IOException, InterruptedException {
        String json = run(List.of("ffprobe", "-v", "error", "-protocol_whitelist", "file,pipe",
                "-show_entries", "format=format_name,duration:stream=index,codec_type,codec_name,pix_fmt,width,height:stream_disposition=attached_pic",
                "-of", "json", file.toString()), workDir, Math.min(timeoutSeconds, 60));
        JsonNode root = mapper.readTree(json);
        JsonNode format = root.path("format");
        String video = null, pixel = null, audio = null;
        int width = 0, height = 0, videoIndex = -1;
        for (JsonNode stream : root.path("streams")) {
            String type = stream.path("codec_type").asText();
            if (video == null && "video".equals(type) && stream.path("disposition").path("attached_pic").asInt(0) == 0) {
                video = stream.path("codec_name").asText(null);
                videoIndex = stream.path("index").asInt(-1);
                pixel = stream.path("pix_fmt").asText(null);
                width = stream.path("width").asInt();
                height = stream.path("height").asInt();
            } else if (audio == null && "audio".equals(type)) audio = stream.path("codec_name").asText(null);
        }
        double seconds = format.path("duration").asDouble(0);
        long millis = Double.isFinite(seconds) && seconds > 0 && seconds < Long.MAX_VALUE / 1000d
                ? Math.round(seconds * 1000) : 0;
        return new Probe(format.path("format_name").asText(""), videoIndex, video, pixel, audio, width, height, millis);
    }

    private static boolean supportedMp4(String format) {
        return List.of(format.split(",")).contains("mp4");
    }

    private static boolean supportedContainer(String format) {
        return supportedMp4(format) || List.of(format.split(",")).contains("webm");
    }

    private static String run(List<String> arguments, Path workDir, long timeoutSeconds) throws IOException, InterruptedException {
        Path log = Files.createTempFile(workDir, "process-", ".log");
        Process process = null;
        try {
            process = new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                throw new IOException("视频处理超时");
            }
            if (process.exitValue() != 0) throw new IOException("视频格式检查或转换失败");
            if (Files.size(log) > 1024 * 1024) throw new IOException("视频处理输出过大");
            return Files.readString(log);
        } finally {
            if (process != null && process.isAlive()) {
                boolean interrupted = Thread.interrupted();
                process.destroyForcibly();
                try { process.waitFor(5, TimeUnit.SECONDS); }
                catch (InterruptedException exception) { interrupted = true; }
                finally { if (interrupted) Thread.currentThread().interrupt(); }
            }
            Files.deleteIfExists(log);
        }
    }

    private static String sha256(Path path) throws IOException, InterruptedException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                for (int count; (count = input.read(buffer)) >= 0;) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("视频处理已中止");
                    if (count > 0) digest.update(buffer, 0, count);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
