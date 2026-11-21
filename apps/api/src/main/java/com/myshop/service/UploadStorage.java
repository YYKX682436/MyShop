package com.myshop.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.LinkOption;
import java.nio.file.FileAlreadyExistsException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Owns the configured upload directory and proves it is usable before the
 * application advertises itself as healthy.
 */
@Slf4j
@Component
public class UploadStorage {

    private final Path uploadDirectory;
    private final HardLinkCreator hardLinkCreator;

    @Autowired
    public UploadStorage(@Value("${upload.path:./uploads}") String configuredPath) {
        this(configuredPath, Files::createLink);
    }

    UploadStorage(String configuredPath, HardLinkCreator hardLinkCreator) {
        this.uploadDirectory = resolveConfiguredPath(configuredPath);
        this.hardLinkCreator = hardLinkCreator;
    }

    @PostConstruct
    public void initialize() {
        if (Files.exists(uploadDirectory) && !Files.isDirectory(uploadDirectory)) {
            throw unavailable("the configured path is not a directory");
        }

        try {
            Files.createDirectories(uploadDirectory);
        } catch (IOException | SecurityException e) {
            throw unavailable("the configured directory cannot be created");
        }

        if (!Files.isDirectory(uploadDirectory)) {
            throw unavailable("the configured path is not a directory");
        }

        Path probe = null;
        try {
            probe = Files.createTempFile(uploadDirectory, ".myshop-write-", ".tmp");
            try (OutputStream output = Files.newOutputStream(
                    probe, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                output.write(0x4d);
                output.flush();
            }
        } catch (IOException | SecurityException e) {
            IOException cleanupFailure = deleteProbes(probe);
            if (cleanupFailure != null) e.addSuppressed(cleanupFailure);
            throw unavailable("the configured directory is not writable");
        }

        IOException cleanupFailure = deleteProbes(probe);
        if (cleanupFailure != null) {
            throw unavailable("the upload write-check file could not be removed", cleanupFailure);
        }
        verifyHardLinkSupport();
        log.info("Upload storage initialized and write and hard-link checks passed");
    }

    private void verifyHardLinkSupport() {
        Path source = null;
        Path hardLink = null;
        Exception checkFailure = null;
        try {
            source = Files.createTempFile(uploadDirectory, ".myshop-hardlink-source-", ".tmp");
            try (OutputStream output = Files.newOutputStream(source, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                output.write(0x4d);
            }
            hardLink = uploadDirectory.resolve(".myshop-hardlink-" + UUID.randomUUID() + ".tmp");
            hardLinkCreator.createLink(hardLink, source);
            if (!Files.isSameFile(source, hardLink)) {
                throw new IOException("hard-link verification did not resolve to the same file");
            }
        } catch (IOException | SecurityException | UnsupportedOperationException exception) {
            checkFailure = exception;
        }

        IOException cleanupFailure = deleteProbes(hardLink, source);
        if (checkFailure != null) {
            if (cleanupFailure != null) checkFailure.addSuppressed(cleanupFailure);
            throw unavailable("the configured upload directory does not support verified hard-link publication", checkFailure);
        }
        if (cleanupFailure != null) {
            throw unavailable("the temporary hard-link self-check files could not be removed", cleanupFailure);
        }
    }

    public Path directory() {
        return uploadDirectory;
    }

    public Path resolve(String filename) {
        return resolveManagedPath(filename);
    }

    /** Publish a complete file without replacing an existing file, even across processes. */
    public void publish(String relativePath, byte[] bytes, boolean allowExisting) throws IOException {
        Path target = prepareManagedFile(relativePath);
        Path temporary = Files.createTempFile(uploadDirectory, ".myshop-upload-", ".tmp");
        try {
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                // Hard links are atomic and fail if the destination exists. Both
                // paths are on the same configured upload filesystem.
                Files.createLink(target, temporary);
            } catch (FileAlreadyExistsException exists) {
                if (!allowExisting || !isSafePublicFile(relativePath) || Files.size(target) != bytes.length
                        || !MessageDigest.isEqual(sha256(bytes), sha256File(target))) {
                    throw new IOException("Existing upload does not match its identifier", exists);
                }
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static byte[] sha256(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static byte[] sha256File(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[8192];
                for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static String identifier(byte[] bytes) { return HexFormat.of().formatHex(sha256(bytes)); }

    public void rememberMediaName(String identifier, String name) throws IOException {
        if (!identifier.matches("[0-9a-f]{64}")) throw new IOException("Invalid identifier");
        Path metadata = uploadDirectory.resolve(".media-info");
        if (!Files.exists(metadata, LinkOption.NOFOLLOW_LINKS)) {
            try { Files.createDirectory(metadata); } catch (FileAlreadyExistsException ignored) { }
        }
        if (Files.isSymbolicLink(metadata) || !Files.isDirectory(metadata, LinkOption.NOFOLLOW_LINKS)
                || !metadata.toRealPath().startsWith(uploadDirectory.toRealPath())) throw new IOException("Invalid media metadata directory");
        Path target = metadata.resolve(identifier + ".txt");
        Path temporary = Files.createTempFile(uploadDirectory, ".myshop-name-", ".tmp");
        try {
            Files.writeString(temporary, name, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            try { Files.createLink(target, temporary); }
            catch (FileAlreadyExistsException ignored) {
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid media metadata");
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    public String mediaName(String identifier, String fallback) {
        if (!identifier.matches("[0-9a-f]{64}")) return fallback;
        Path metadata = uploadDirectory.resolve(".media-info");
        Path file = metadata.resolve(identifier + ".txt");
        try {
            if (Files.isSymbolicLink(metadata) || !Files.isDirectory(metadata, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 2048
                    || !file.toRealPath().startsWith(uploadDirectory.toRealPath())) return fallback;
            String name = Files.readString(file, StandardCharsets.UTF_8).trim();
            return name.isEmpty() ? fallback : name;
        } catch (IOException | SecurityException ignored) { return fallback; }
    }

    /** Remove only metadata for an orphaned content hash; never follow links. */
    public void forgetMediaName(String identifier) throws IOException {
        if (!identifier.matches("[0-9a-f]{64}")) return;
        try (var files = Files.list(uploadDirectory)) {
            if (files.anyMatch(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).startsWith(identifier + ".")
                    && isSafePublicFile(path.getFileName().toString()))) return;
        }
        Path metadata = uploadDirectory.resolve(".media-info");
        if (!Files.exists(metadata, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(metadata) || !Files.isDirectory(metadata, LinkOption.NOFOLLOW_LINKS)
                || !metadata.toRealPath().startsWith(uploadDirectory.toRealPath())) throw new IOException("Invalid media metadata directory");
        Path file = metadata.resolve(identifier + ".txt");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid media metadata");
        Files.delete(file);
    }

    /** Reject hidden paths, symlink components and files outside the actual root. */
    public boolean isSafePublicFile(String relativePath) {
        try {
            if (relativePath == null || relativePath.indexOf('\\') >= 0) return false;
            for (Path part : Paths.get(relativePath)) {
                if (part.toString().equals(".") || part.toString().equals("..")) return false;
            }
            Path target = resolveManagedPath(relativePath);
            Path relative = uploadDirectory.relativize(target);
            Path current = uploadDirectory;
            for (Path part : relative) {
                if (part.toString().startsWith(".")) return false;
                current = current.resolve(part);
                if (Files.isSymbolicLink(current)) return false;
            }
            return Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    && target.toRealPath().startsWith(uploadDirectory.toRealPath());
        } catch (IOException | IllegalArgumentException | SecurityException exception) {
            return false;
        }
    }

    /**
     * Prepare a generated upload path for writing after resolving any parent
     * directories and proving that symlinks cannot redirect the write outside
     * the configured storage root.
     */
    public Path prepareManagedFile(String relativePath) {
        Path target = resolveManagedPath(relativePath);
        try {
            Files.createDirectories(target.getParent());
            Path realDirectory = uploadDirectory.toRealPath();
            Path realParent = target.getParent().toRealPath();
            if (!realParent.startsWith(realDirectory)) {
                throw new IllegalArgumentException("managed upload path escapes storage");
            }
            return target;
        } catch (IOException | SecurityException exception) {
            throw new IllegalArgumentException("managed upload path is not writable", exception);
        }
    }

    /** Resolve a relative path without allowing it to escape UploadStorage. */
    public Path resolveManagedPath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("managed upload path is blank");
        }

        try {
            Path requested = Paths.get(relativePath);
            if (requested.isAbsolute()) {
                throw new IllegalArgumentException("managed upload path must be relative");
            }
            Path resolved = uploadDirectory.resolve(requested).normalize();
            if (!resolved.startsWith(uploadDirectory)) {
                throw new IllegalArgumentException("managed upload path escapes storage");
            }
            return resolved;
        } catch (InvalidPathException | SecurityException exception) {
            throw new IllegalArgumentException("managed upload path is invalid", exception);
        }
    }

    /**
     * Delete one regular file only after proving both the normalized and real
     * path remain under the configured storage directory.
     */
    public boolean deleteManagedFile(String relativePath) {
        final Path target;
        try {
            target = resolveManagedPath(relativePath);
        } catch (IllegalArgumentException exception) {
            log.warn("Managed upload deletion rejected");
            return false;
        }

        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }

        try {
            Path realDirectory = uploadDirectory.toRealPath();
            Path realTarget = target.toRealPath();
            if (!realTarget.startsWith(realDirectory)) {
                log.warn("Managed upload deletion rejected outside storage");
                return false;
            }
            return Files.deleteIfExists(target);
        } catch (IOException | SecurityException exception) {
            log.warn("Managed upload deletion failed");
            return false;
        }
    }

    private static Path resolveConfiguredPath(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) {
            throw unavailable("the configured path is blank");
        }

        try {
            Path path = Paths.get(configuredPath.trim());
            if (!path.isAbsolute()) {
                path = Paths.get(System.getProperty("user.dir")).resolve(path);
            }
            return path.normalize();
        } catch (InvalidPathException | SecurityException e) {
            throw unavailable("the configured path is invalid");
        }
    }

    private static IOException deleteProbes(Path... probes) {
        IOException failure = null;
        for (Path probe : probes) {
            if (probe == null) continue;
            try {
                Files.deleteIfExists(probe);
            } catch (IOException | SecurityException exception) {
                IOException cleanupFailure = exception instanceof IOException ioException
                        ? ioException : new IOException("temporary upload self-check file could not be removed", exception);
                if (failure == null) failure = cleanupFailure;
                else failure.addSuppressed(cleanupFailure);
            }
        }
        return failure;
    }

    private static IllegalStateException unavailable(String reason) {
        String message = "Upload storage initialization failed: " + reason
                + ". Check UPLOAD_PATH and directory permissions.";
        log.error(message);
        return new IllegalStateException(message);
    }

    private static IllegalStateException unavailable(String reason, Throwable cause) {
        String message = "Upload storage initialization failed: " + reason
                + ". Check UPLOAD_PATH, hard-link support, and directory permissions.";
        log.error(message, cause);
        return new IllegalStateException(message, cause);
    }

    @FunctionalInterface
    interface HardLinkCreator {
        Path createLink(Path link, Path existing) throws IOException;
    }
}
