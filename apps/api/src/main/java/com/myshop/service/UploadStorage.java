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
        return uploadDirectory.resolve(filename);
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
