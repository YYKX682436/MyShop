package com.myshop.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadStorageTest {

    @TempDir
    Path tempDirectory;

    @Test
    void writeDeleteProbePassesAndLeavesNoProbeFile() throws IOException {
        UploadStorage storage = new UploadStorage(tempDirectory.resolve("uploads").toString());

        assertDoesNotThrow(storage::initialize);
        assertTrue(Files.isDirectory(storage.directory()));
        try (var files = Files.list(storage.directory())) {
            assertFalse(files.findAny().isPresent());
        }
    }

    @Test
    void startupRequiresHardLinksAndRemovesProbeFilesWhenUnsupported() throws IOException {
        Path directory = tempDirectory.resolve("no-hardlinks");
        UploadStorage storage = new UploadStorage(directory.toString(), (link, existing) -> {
            throw new UnsupportedOperationException("hard links are unavailable");
        });

        IllegalStateException exception = assertThrows(IllegalStateException.class, storage::initialize);

        assertTrue(exception.getMessage().contains("hard-link"));
        try (var files = Files.list(directory)) {
            assertFalse(files.findAny().isPresent());
        }
    }

    @Test
    void startupVerifiesTheProbeIsAHardLinkAndRemovesCopiedProbeFiles() throws IOException {
        Path directory = tempDirectory.resolve("copy-instead-of-link");
        UploadStorage storage = new UploadStorage(directory.toString(), Files::copy);

        IllegalStateException exception = assertThrows(IllegalStateException.class, storage::initialize);

        assertTrue(exception.getMessage().contains("hard-link"));
        try (var files = Files.list(directory)) {
            assertFalse(files.findAny().isPresent());
        }
    }

    @Test
    void publishRejectsDifferentContentAtExistingPathWithoutChangingOriginal() throws IOException {
        UploadStorage storage = new UploadStorage(tempDirectory.resolve("uploads").toString());
        storage.initialize();
        byte[] original = {1, 2, 3};
        Path destination = storage.resolve("same.png");
        storage.publish("same.png", original, false);

        assertThrows(IOException.class, () -> storage.publish("same.png", new byte[]{4, 5, 6}, true));
        assertArrayEquals(original, Files.readAllBytes(destination));
        try (var files = Files.list(storage.directory())) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void startupFailsWhenConfiguredPathIsNotAUsableDirectory() throws IOException {
        Path file = Files.createFile(tempDirectory.resolve("not-a-directory"));
        UploadStorage storage = new UploadStorage(file.toString());

        IllegalStateException exception = assertThrows(IllegalStateException.class, storage::initialize);

        assertTrue(exception.getMessage().contains("not a directory"));
    }

    @Test
    void startupFailsWhenExistingDirectoryIsNotWritable() throws IOException {
        Path uploadDirectory = Files.createDirectory(tempDirectory.resolve("not-writable-uploads"));
        if (!Files.getFileStore(uploadDirectory).supportsFileAttributeView("posix")) {
            Assumptions.assumeTrue(false, "POSIX file permissions are not supported");
        }

        Set<PosixFilePermission> originalPermissions = Files.getPosixFilePermissions(uploadDirectory);
        Set<PosixFilePermission> readOnlyPermissions = new HashSet<>(originalPermissions);
        readOnlyPermissions.remove(PosixFilePermission.OWNER_WRITE);
        readOnlyPermissions.remove(PosixFilePermission.GROUP_WRITE);
        readOnlyPermissions.remove(PosixFilePermission.OTHERS_WRITE);

        boolean permissionsChanged = false;
        try {
            try {
                Files.setPosixFilePermissions(uploadDirectory, readOnlyPermissions);
                permissionsChanged = true;
            } catch (UnsupportedOperationException e) {
                Assumptions.assumeTrue(false, "POSIX file permissions cannot be changed");
            }

            if (Files.isWritable(uploadDirectory)) {
                Assumptions.assumeTrue(false, "the current process can write despite removed POSIX write permissions");
            }

            UploadStorage storage = new UploadStorage(uploadDirectory.toString());

            IllegalStateException exception = assertThrows(IllegalStateException.class, storage::initialize);

            assertTrue(exception.getMessage().contains("not writable"));
        } finally {
            if (permissionsChanged) {
                Files.setPosixFilePermissions(uploadDirectory, originalPermissions);
            }
        }
    }

    @Test
    void managedDeletionRejectsTraversalAndAbsolutePathsWithoutTouchingSiblings() throws IOException {
        UploadStorage storage = new UploadStorage(tempDirectory.resolve("uploads").toString());
        storage.initialize();
        Path outside = tempDirectory.resolve("outside.txt");
        Files.writeString(outside, "must remain");

        assertFalse(storage.deleteManagedFile("../outside.txt"));
        assertFalse(storage.deleteManagedFile(outside.toString()));
        assertTrue(Files.exists(outside));
        assertTrue(Files.readString(outside).equals("must remain"));
    }
}
