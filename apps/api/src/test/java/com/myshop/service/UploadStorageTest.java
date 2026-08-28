package com.myshop.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void startupFailsWhenConfiguredPathIsNotAUsableDirectory() throws IOException {
        Path file = Files.createFile(tempDirectory.resolve("not-a-directory"));
        UploadStorage storage = new UploadStorage(file.toString());

        IllegalStateException exception = assertThrows(IllegalStateException.class, storage::initialize);

        assertTrue(exception.getMessage().contains("not a directory"));
    }
}
