package com.myshop.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.imageio.IIOImage;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class ImageProcessingTest {
    @TempDir Path temporary;

    @Test
    void photoIsSmallerAndKeepsVisibleDetail() throws Exception {
        BufferedImage photo = new BufferedImage(1200, 900, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < photo.getHeight(); y++) for (int x = 0; x < photo.getWidth(); x++) {
            int r = (x * 255 / 1200 + y / 9) % 256, g = y * 255 / 900, b = (x + y) % 256;
            photo.setRGB(x, y, (r << 16) | (g << 8) | b);
        }
        byte[] before = jpeg(photo, 1f);
        try (var after = process(before, ".jpg")) {
            BufferedImage decodedBefore = ImageIO.read(new ByteArrayInputStream(before));
            BufferedImage decodedAfter = decode(after.display());
            assertEquals(1200, decodedAfter.getWidth());
            assertEquals(900, decodedAfter.getHeight());
            long error = 0;
            for (int y = 0; y < 900; y += 5) for (int x = 0; x < 1200; x += 5) {
                int a = decodedBefore.getRGB(x, y), b = decodedAfter.getRGB(x, y);
                for (int shift : new int[]{0, 8, 16}) error += Math.abs(((a >> shift) & 255) - ((b >> shift) & 255));
            }
            double meanError = error / (240.0 * 180 * 3);
            System.out.printf("PHOTO_BYTES before=%d display=%d mean_rgb_error=%.2f%n", before.length, after.display().size(), meanError);
            assertTrue(after.display().size() < before.length);
            assertTrue(meanError < 8, "WebP detail loss exceeded test threshold: " + meanError);
            assertEquals("image/webp", after.display().mime());
        }
    }

    @Test
    void transparentPngScalesAndKeepsAlpha() throws Exception {
        BufferedImage input = new BufferedImage(3000, 300, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = input.createGraphics();
        g.setColor(new Color(230, 40, 20, 128)); g.fillRect(0, 0, 1500, 300); g.dispose();
        try (var result = process(png(input), ".png")) {
            BufferedImage decoded = decode(result.display());
            assertEquals(1600, decoded.getWidth());
            assertEquals(0, decoded.getRGB(1400, 80) >>> 24);
            assertEquals(128, decoded.getRGB(200, 80) >>> 24);
        }
    }

    @Test
    void exifOrientationIsAppliedBeforeSaving() throws Exception {
        BufferedImage input = new BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = input.createGraphics(); g.setColor(Color.RED); g.fillRect(0, 0, 40, 40); g.setColor(Color.BLUE); g.fillRect(40, 0, 40, 40); g.dispose();
        byte[] original = jpeg(input, 0.98f);
        byte[] exif = new byte[]{(byte)0xff,(byte)0xe1,0,34,'E','x','i','f',0,0,'I','I',42,0,8,0,0,0,1,0,0x12,1,3,0,1,0,0,0,6,0,0,0,0,0,0,0};
        ByteArrayOutputStream oriented = new ByteArrayOutputStream();
        oriented.write(original, 0, 2); oriented.write(exif); oriented.write(original, 2, original.length - 2);
        try (var output = process(oriented.toByteArray(), ".jpg")) {
            BufferedImage image = decode(output.display());
            assertEquals(40, image.getWidth()); assertEquals(80, image.getHeight());
            assertTrue(new Color(image.getRGB(20, 10)).getRed() > new Color(image.getRGB(20, 10)).getBlue());
            assertTrue(new Color(image.getRGB(20, 70)).getBlue() > new Color(image.getRGB(20, 70)).getRed());
        }
    }

    @Test
    void pngExifOrientationIsApplied() throws Exception {
        BufferedImage input = new BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = input.createGraphics(); g.setColor(Color.RED); g.fillRect(0, 0, 40, 40); g.setColor(Color.BLUE); g.fillRect(40, 0, 40, 40); g.dispose();
        byte[] exif = {'I','I',42,0,8,0,0,0,1,0,0x12,1,3,0,1,0,0,0,6,0,0,0,0,0,0,0};
        try (var result = process(withPngChunk(png(input), "eXIf", exif), ".png")) {
            BufferedImage image = decode(result.display());
            assertEquals(40, image.getWidth()); assertEquals(80, image.getHeight());
            assertTrue(new Color(image.getRGB(20, 10)).getRed() > new Color(image.getRGB(20, 10)).getBlue());
        }
    }

    @Test
    void pngTrailingBytesAfterIendDoNotBreakStaticOrAnimatedImages() throws Exception {
        byte[] trailingMetadata = "camera-private-metadata".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] staticSource = png(new BufferedImage(32, 24, BufferedImage.TYPE_INT_ARGB));
        byte[] staticPng = java.util.Arrays.copyOf(staticSource, staticSource.length + trailingMetadata.length);
        System.arraycopy(trailingMetadata, 0, staticPng, staticSource.length, trailingMetadata.length);
        try (var result = process(staticPng, ".png")) {
            assertFalse(result.animated());
            assertEquals(32, decode(result.display()).getWidth());
        }

        byte[] animated = animatedPng();
        byte[] animatedWithTrailing = java.util.Arrays.copyOf(animated, animated.length + trailingMetadata.length);
        System.arraycopy(trailingMetadata, 0, animatedWithTrailing, animated.length, trailingMetadata.length);
        try (var result = process(animatedWithTrailing, ".png")) {
            assertTrue(result.animated());
            assertArrayEquals(animatedWithTrailing, Files.readAllBytes(result.display().path()));
            assertArrayEquals(animatedWithTrailing, Files.readAllBytes(result.clear().path()));
        }
    }

    @Test
    void oversizedCompressedPngMetadataCannotHangImageProcessing() throws Exception {
        var compressed = new ByteArrayOutputStream();
        compressed.write(new byte[]{'n','o','t','e',0,0});
        try (var deflater = new java.util.zip.DeflaterOutputStream(compressed)) {
            byte[] block = new byte[8192];
            for (int i = 0; i < 24576; i++) deflater.write(block);
        }
        byte[] input = withPngChunk(png(new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB)), "zTXt", compressed.toByteArray());
        assertTimeoutPreemptively(Duration.ofSeconds(55), () -> {
            try (var output = process(input, ".png")) {
                assertNotNull(decode(output.display()));
            } catch (IOException rejected) {
                assertNotNull(rejected.getMessage(), "an oversized metadata block must fail cleanly");
            }
        });
    }

    @Test
    void animatedGifAndWebpRemainUnchanged() throws Exception {
        byte[] gif = animatedGif();
        try (var result = process(gif, ".gif")) {
            assertTrue(result.animated());
            assertArrayEquals(gif, Files.readAllBytes(result.display().path()));
            assertArrayEquals(gif, Files.readAllBytes(result.thumbnail().path()));
            try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(Files.readAllBytes(result.display().path())))) {
                var reader = ImageIO.getImageReaders(stream).next(); reader.setInput(stream);
                assertEquals(2, reader.getNumImages(true)); reader.dispose();
            }
        }
        byte[] webp = Base64.getDecoder().decode("UklGRjwAAABXRUJQVlA4IDAAAAAwAgCdASoCAAIAAUAmJaACdLoB+AH4AAYeAAD+8U2v/Vn00Iv7SP/1v/qCA/SAAAA=");
        try (var result = process(webp, ".webp")) {
            assertFalse(result.animated());
            assertEquals(2, decode(result.display()).getWidth());
        }
        byte[] animatedWebp = Base64.getDecoder().decode("UklGRtwAAABXRUJQVlA4WAoAAAACAAAAHwAAHwAAQU5JTQYAAAD/////AABBTk1GWAAAAAAAAAAAAB8AAB8AAMgAAAJWUDggQAAAAHADAJ0BKiAAIAA+bTSWR6QjIiEoCACADYllAMougH4AAEGUEAD+8JtD/8guWF1yNf/yA/5Af8gP/499jCgwAABBTk1GUAAAAAAAAAAAAB8AAB8AAMgAAABWUDggOAAAALQCAJ0BKiAAIAA+bTSWR4KAgAAA2JZQB2AACAoKAAD++R3b//5Af/5Af/5Af/yA//4hd7IDAAAA");
        try (var result = process(animatedWebp, ".webp")) {
            assertTrue(result.animated());
            assertArrayEquals(animatedWebp, Files.readAllBytes(result.display().path()));
            assertArrayEquals(animatedWebp, Files.readAllBytes(result.clear().path()));
        }
    }

    @Test
    void animatedPngIsNotFlattened() throws Exception {
        byte[] bytes = animatedPng();
        assertNotNull(ImageIO.read(new ByteArrayInputStream(bytes)));
        try (var output = process(bytes, ".png")) {
            assertTrue(output.animated());
            assertArrayEquals(bytes, Files.readAllBytes(output.display().path()));
            assertArrayEquals(bytes, Files.readAllBytes(output.thumbnail().path()));
            assertArrayEquals(bytes, Files.readAllBytes(output.clear().path()));
        }
    }

    @Test
    void corruptAndOversizedDimensionsAreRejected() throws Exception {
        byte[] bogus = {(byte)0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0};
        assertThrows(IOException.class, () -> process(bogus, ".png"));
        byte[] large = png(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB));
        java.nio.ByteBuffer.wrap(large, 16, 4).putInt(30_001);
        var crc = new java.util.zip.CRC32(); crc.update(large, 12, 17);
        java.nio.ByteBuffer.wrap(large, 29, 4).putInt((int) crc.getValue());
        assertThrows(IOException.class, () -> process(large, ".png"));
        assertThrows(IOException.class, () -> process("RIFF0000WEBP".getBytes(), ".webp"));
    }

    private VipsImageProcessor.Processed process(byte[] bytes, String extension) throws IOException {
        Path source = temporary.resolve(UUID.randomUUID() + extension);
        Files.write(source, bytes);
        return new VipsImageProcessor().process(source, temporary.resolve("work"), VipsImageProcessor.Purpose.PRODUCT);
    }

    private BufferedImage decode(VipsImageProcessor.Variant variant) throws Exception {
        Path decoded = temporary.resolve(UUID.randomUUID() + ".png");
        Process process = new ProcessBuilder("vipsthumbnail", variant.path().toString(), "--size", "30000x>",
                "-o", decoded.toString() + "[compression=3]").redirectErrorStream(true).start();
        assertTrue(process.waitFor(45, TimeUnit.SECONDS), "test image decode timed out");
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        BufferedImage image = ImageIO.read(decoded.toFile());
        assertNotNull(image);
        return image;
    }
    private static byte[] png(BufferedImage image) throws IOException { var out = new ByteArrayOutputStream(); ImageIO.write(image, "png", out); return out.toByteArray(); }
    private static byte[] jpeg(BufferedImage image, float quality) throws IOException {
        var writer = ImageIO.getImageWritersByFormatName("jpg").next();
        var out = new ByteArrayOutputStream();
        try (var stream = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(stream); ImageWriteParam param = writer.getDefaultWriteParam(); param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT); param.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally { writer.dispose(); }
        return out.toByteArray();
    }
    public static byte[] animatedGif() throws IOException {
        var writer = ImageIO.getImageWritersByFormatName("gif").next(); var out = new ByteArrayOutputStream();
        try (var stream = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(stream); writer.prepareWriteSequence(null);
            for (Color color : new Color[]{Color.RED, Color.BLUE}) {
                BufferedImage frame = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = frame.createGraphics(); g.setColor(color); g.fillRect(0, 0, 8, 8); g.dispose();
                writer.writeToSequence(new IIOImage(frame, null, null), null);
            }
            writer.endWriteSequence();
        } finally { writer.dispose(); }
        return out.toByteArray();
    }

    public static byte[] animatedPng() throws IOException {
        var bytes = new ByteArrayOutputStream(); var out = new java.io.DataOutputStream(bytes);
        out.write(new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10});
        var header = new ByteArrayOutputStream(); var h = new java.io.DataOutputStream(header);
        h.writeInt(8); h.writeInt(8); h.write(new byte[]{8, 6, 0, 0, 0});
        chunk(out, "IHDR", header.toByteArray());
        chunk(out, "acTL", new byte[]{0, 0, 0, 2, 0, 0, 0, 0});
        chunk(out, "fcTL", frameControl(0));
        chunk(out, "IDAT", compressedFrame(true));
        chunk(out, "fcTL", frameControl(1));
        var second = new ByteArrayOutputStream(); var fd = new java.io.DataOutputStream(second);
        fd.writeInt(2); fd.write(compressedFrame(false));
        chunk(out, "fdAT", second.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return bytes.toByteArray();
    }

    private static byte[] frameControl(int sequence) throws IOException {
        var bytes = new ByteArrayOutputStream(); var out = new java.io.DataOutputStream(bytes);
        out.writeInt(sequence); out.writeInt(8); out.writeInt(8); out.writeInt(0); out.writeInt(0);
        out.writeShort(1); out.writeShort(10); out.writeByte(0); out.writeByte(0);
        return bytes.toByteArray();
    }

    private static byte[] compressedFrame(boolean red) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new java.util.zip.DeflaterOutputStream(bytes)) {
            for (int y = 0; y < 8; y++) {
                out.write(0);
                for (int x = 0; x < 8; x++) out.write(new byte[]{(byte)(red ? 255 : 0), 0, (byte)(red ? 0 : 255), (byte)255});
            }
        }
        return bytes.toByteArray();
    }

    private static void chunk(java.io.DataOutputStream out, String type, byte[] data) throws IOException {
        byte[] name = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var crc = new java.util.zip.CRC32(); crc.update(name); crc.update(data);
        out.writeInt(data.length); out.write(name); out.write(data); out.writeInt((int) crc.getValue());
    }

    private static byte[] withPngChunk(byte[] png, String type, byte[] data) throws IOException {
        var bytes = new ByteArrayOutputStream(); var out = new java.io.DataOutputStream(bytes);
        out.write(png, 0, 33); // PNG signature and IHDR
        chunk(out, type, data);
        out.write(png, 33, png.length - 33);
        return bytes.toByteArray();
    }
}
