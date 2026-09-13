package com.myshop.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrivateUploadStorageTest {

    @TempDir
    Path tempDirectory;

    @Test
    void storesAndReadsOnlyGeneratedRelativeKeysOutsideThePublicRoot() throws IOException {
        UploadStorage publicStorage = publicStorage();
        PrivateUploadStorage privateStorage = privateStorage(publicStorage, tempDirectory.resolve("private"));
        privateStorage.initialize();
        byte[] bytes = new byte[]{1, 2, 3, 4};
        privateStorage.write("after-sales/request/file.png",
                new MockMultipartFile("attachments", "file.png", "image/png", bytes));

        Path resolved = privateStorage.resolveForRead("after-sales/request/file.png");
        assertArrayEquals(bytes, Files.readAllBytes(resolved));
        assertTrue(resolved.startsWith(tempDirectory.resolve("private").toAbsolutePath()));
        assertFalse(resolved.startsWith(publicStorage.directory().toAbsolutePath()));
        assertTrue(privateStorage.deleteManagedFile("after-sales/request/file.png"));
        assertFalse(Files.exists(resolved));
    }

    @Test
    void rejectsTraversalAbsoluteAndBackslashKeysWithoutTouchingOutsideFiles() throws IOException {
        UploadStorage publicStorage = publicStorage();
        Path privateRoot = tempDirectory.resolve("private");
        PrivateUploadStorage privateStorage = privateStorage(publicStorage, privateRoot);
        privateStorage.initialize();
        Path outside = tempDirectory.resolve("outside.txt");
        Files.writeString(outside, "must remain");

        for (String invalid : new String[]{"../outside.txt", "after-sales/../outside.txt",
                outside.toString(), "after-sales\\outside.txt", ".", ""}) {
            assertThrows(IllegalArgumentException.class, () -> privateStorage.resolveForRead(invalid));
            assertFalse(privateStorage.deleteManagedFile(invalid));
        }
        assertTrue(Files.exists(outside));
        assertTrue(Files.readString(outside).equals("must remain"));
    }

    @Test
    void refusesAnyOverlapWithThePublicStaticResourceRoot() {
        UploadStorage publicStorage = publicStorage();
        assertThrows(IllegalStateException.class, () ->
                privateStorage(publicStorage, publicStorage.directory()).initialize());
        assertThrows(IllegalStateException.class, () ->
                privateStorage(publicStorage, publicStorage.directory().resolve("private")).initialize());
        assertThrows(IllegalStateException.class, () ->
                privateStorage(publicStorage, tempDirectory).initialize());
    }

    private UploadStorage publicStorage() {
        UploadStorage storage = new UploadStorage(tempDirectory.resolve("public").toString());
        storage.initialize();
        return storage;
    }

    private PrivateUploadStorage privateStorage(UploadStorage publicStorage, Path privateRoot) {
        return new PrivateUploadStorage(privateRoot.toString(), publicStorage);
    }
}
