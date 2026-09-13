package com.myshop.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Private storage for after-sale evidence. It deliberately has no MVC
 * resource handler; every read must pass through database ownership checks.
 */
@Slf4j
@Component
public class PrivateUploadStorage {

    private final Path privateDirectory;
    private final UploadStorage publicUploadStorage;

    public PrivateUploadStorage(
            @Value("${private-upload.path:${PRIVATE_UPLOAD_PATH:./private-uploads}}") String configuredPath,
            UploadStorage publicUploadStorage) {
        this.privateDirectory = resolveConfiguredPath(configuredPath);
        this.publicUploadStorage = publicUploadStorage;
    }

    @PostConstruct
    public void initialize() {
        Path publicDirectory = publicUploadStorage.directory().toAbsolutePath().normalize();
        Path privatePath = privateDirectory.toAbsolutePath().normalize();
        if (privatePath.startsWith(publicDirectory) || publicDirectory.startsWith(privatePath)) {
            throw unavailable("PRIVATE_UPLOAD_PATH must not overlap UPLOAD_PATH");
        }
        if (Files.exists(privateDirectory, LinkOption.NOFOLLOW_LINKS)
                && Files.isSymbolicLink(privateDirectory)) {
            throw unavailable("the configured private directory cannot be a symbolic link");
        }
        if (Files.exists(privateDirectory, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(privateDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw unavailable("the configured private path is not a directory");
        }
        try {
            Files.createDirectories(privateDirectory);
            if (Files.isSymbolicLink(privateDirectory)) {
                throw unavailable("the configured private directory cannot be a symbolic link");
            }
            Path publicReal = publicDirectory.toRealPath();
            Path privateReal = privateDirectory.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (privateReal.startsWith(publicReal) || publicReal.startsWith(privateReal)) {
                throw unavailable("PRIVATE_UPLOAD_PATH must not overlap UPLOAD_PATH");
            }
            Path probe = Files.createTempFile(privateDirectory, ".myshop-private-write-", ".tmp");
            try (OutputStream output = Files.newOutputStream(
                    probe, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                output.write(0x4d);
                output.flush();
            }
            Files.deleteIfExists(probe);
        } catch (IOException | SecurityException exception) {
            throw unavailable("the configured private directory is not writable");
        }
        log.info("Private upload storage initialized and write probe passed");
    }

    /** Store a validated multipart file at a generated, relative key. */
    public void write(String storageKey, MultipartFile file) {
        Path target = prepareManagedFile(storageKey);
        try (InputStream input = file.getInputStream();
             OutputStream output = Files.newOutputStream(target,
                     StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            input.transferTo(output);
            output.flush();
        } catch (IOException | SecurityException exception) {
            deleteManagedFile(storageKey);
            throw new IllegalStateException("private attachment could not be stored", exception);
        }
    }

    /** Resolve a database-owned key for a protected read. */
    public Path resolveForRead(String storageKey) {
        Path target = resolveManagedPath(storageKey);
        ensureNoSymlinkPath(target.getParent());
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(target)
                || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("private attachment is not a regular file");
        }
        try {
            verifyInsideRoot(target);
        } catch (IOException | SecurityException exception) {
            throw new IllegalArgumentException("private attachment path is unavailable", exception);
        }
        return target;
    }

    /** Delete one generated file only after the same traversal checks. */
    public boolean deleteManagedFile(String storageKey) {
        final Path target;
        try {
            target = resolveManagedPath(storageKey);
            ensureNoSymlinkPath(target.getParent());
        } catch (IllegalArgumentException exception) {
            return false;
        }
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(target)
                || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            verifyInsideRoot(target);
            return Files.deleteIfExists(target);
        } catch (IOException | SecurityException | IllegalArgumentException exception) {
            return false;
        }
    }

    public Path prepareManagedFile(String storageKey) {
        Path target = resolveManagedPath(storageKey);
        Path parent = target.getParent();
        ensureNoSymlinkPath(parent);
        try {
            Files.createDirectories(parent);
            ensureNoSymlinkPath(parent);
            // The target is intentionally absent until write() opens it with
            // CREATE_NEW. Validate the existing parent now; the lexical and
            // no-symlink checks above protect the eventual child path.
            verifyInsideRoot(parent);
            return target;
        } catch (IOException | SecurityException exception) {
            throw new IllegalStateException("private attachment path is not writable", exception);
        }
    }

    private Path resolveManagedPath(String storageKey) {
        if (storageKey == null || storageKey.isBlank() || storageKey.contains("\\")) {
            throw new IllegalArgumentException("private attachment key is invalid");
        }
        try {
            Path requested = Paths.get(storageKey);
            if (requested.isAbsolute() || requested.getNameCount() == 0) {
                throw new IllegalArgumentException("private attachment key must be relative");
            }
            for (Path part : requested) {
                if (part.toString().equals(".") || part.toString().equals("..")) {
                    throw new IllegalArgumentException("private attachment key contains traversal");
                }
            }
            Path resolved = privateDirectory.resolve(requested).normalize();
            if (!resolved.startsWith(privateDirectory)) {
                throw new IllegalArgumentException("private attachment key escapes storage");
            }
            return resolved;
        } catch (InvalidPathException | SecurityException exception) {
            throw new IllegalArgumentException("private attachment key is invalid", exception);
        }
    }

    private void ensureNoSymlinkPath(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("private attachment parent is invalid");
        }
        Path root = privateDirectory.toAbsolutePath().normalize();
        Path current = root;
        if (Files.isSymbolicLink(current)) {
            throw new IllegalArgumentException("private storage root is a symbolic link");
        }
        Path relative = root.relativize(path.toAbsolutePath().normalize());
        for (Path part : relative) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("private attachment path contains a symbolic link");
            }
        }
    }

    private void verifyInsideRoot(Path target) throws IOException {
        Path realRoot = privateDirectory.toRealPath(LinkOption.NOFOLLOW_LINKS);
        Path realTarget = target.toRealPath();
        if (!realTarget.startsWith(realRoot)) {
            throw new IllegalArgumentException("private attachment path escapes storage");
        }
    }

    private static Path resolveConfiguredPath(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) {
            throw unavailable("the configured private path is blank");
        }
        try {
            Path path = Paths.get(configuredPath.trim());
            if (!path.isAbsolute()) {
                path = Paths.get(System.getProperty("user.dir")).resolve(path);
            }
            return path.normalize();
        } catch (InvalidPathException | SecurityException exception) {
            throw unavailable("the configured private path is invalid");
        }
    }

    private static IllegalStateException unavailable(String reason) {
        return new IllegalStateException("Private upload storage initialization failed: " + reason
                + ". Check PRIVATE_UPLOAD_PATH and directory permissions.");
    }
}
