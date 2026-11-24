package com.myshop.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class VipsImageProcessorTest {
    @TempDir Path directory;

    @Test void rejectedInputCleansOnlyTheProcessorTemporaryDirectory() throws Exception {
        Path source = directory.resolve("original.bin");
        byte[] contents = "not an image".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(source, contents);
        Path work = Files.createDirectory(directory.resolve("work"));
        Files.writeString(work.resolve("unrelated.txt"), "keep");

        assertThrows(IOException.class, () -> new VipsImageProcessor().process(source, work, VipsImageProcessor.Purpose.PRODUCT));

        assertArrayEquals(contents, Files.readAllBytes(source));
        try (var entries = Files.list(work)) {
            assertEquals(java.util.List.of("unrelated.txt"), entries.map(path -> path.getFileName().toString()).toList());
        }
    }

    @Test void imageSizeAndSymlinkAreRejectedBeforeStartingAProcess() throws Exception {
        Path work = Files.createDirectory(directory.resolve("work"));
        Path oversized = directory.resolve("too-large.jpg");
        try (var channel = java.nio.channels.FileChannel.open(oversized,
                java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)) {
            channel.position(10L * 1024 * 1024);
            channel.write(java.nio.ByteBuffer.wrap(new byte[] { 1 }));
        }
        assertThrows(IOException.class, () -> new VipsImageProcessor().process(oversized, work, VipsImageProcessor.Purpose.PRODUCT));
        Path target = Files.writeString(directory.resolve("target"), "image");
        Path link = Files.createSymbolicLink(directory.resolve("link"), target);
        assertThrows(IOException.class, () -> new VipsImageProcessor().process(link, work, VipsImageProcessor.Purpose.AVATAR));
        try (var entries = Files.list(work)) { assertEquals(0, entries.count()); }
    }
}
