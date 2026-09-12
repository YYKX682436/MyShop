package com.myshop.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
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

/**
 * Owns the configured upload directory and proves it is usable before the
 * application advertises itself as healthy.
 */
@Slf4j
@Component
public class UploadStorage {

    private final Path uploadDirectory;

    public UploadStorage(@Value("${upload.path:./uploads}") String configuredPath) {
        this.uploadDirectory = resolveConfiguredPath(configuredPath);
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
            Files.delete(probe);
            log.info("Upload storage initialized and write probe passed");
        } catch (IOException | SecurityException e) {
            deleteProbe(probe);
            throw unavailable("the configured directory is not writable");
        }
    }

    public Path directory() {
        return uploadDirectory;
    }

    public Path resolve(String filename) {
        return resolveManagedPath(filename);
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

    private static void deleteProbe(Path probe) {
        if (probe == null) {
            return;
        }
        try {
            Files.deleteIfExists(probe);
        } catch (IOException | SecurityException ignored) {
            // The startup failure below is the actionable signal. Do not
            // expose filesystem paths or a second noisy exception.
        }
    }

    private static IllegalStateException unavailable(String reason) {
        String message = "Upload storage initialization failed: " + reason
                + ". Check UPLOAD_PATH and directory permissions.";
        log.error(message);
        return new IllegalStateException(message);
    }
}
