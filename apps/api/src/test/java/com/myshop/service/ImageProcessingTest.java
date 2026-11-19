package com.myshop.service;

import com.myshop.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

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
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ImageProcessingTest {
    @TempDir Path temporary;

    @Test
    void photoIsSmallerAndKeepsVisibleDetail() throws Exception {
        BufferedImage photo = new BufferedImage(1200, 900, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < photo.getHeight(); y++) for (int x = 0; x < photo.getWidth(); x++) {
            int r = (x * 255 / 1200 + y / 9) % 256, g = y * 255 / 900, b = (x + y) % 256;
            photo.setRGB(x, y, (r << 16) | (g << 8) | b);
        }
        byte[] before = jpeg(photo, 1f);
        byte[] after = new ImageProcessor().process(before, ".jpg");
        BufferedImage decodedBefore = ImageIO.read(new ByteArrayInputStream(before));
        BufferedImage decodedAfter = ImageIO.read(new ByteArrayInputStream(after));
        long error = 0;
        for (int y = 0; y < 900; y += 5) for (int x = 0; x < 1200; x += 5) {
            int a = decodedBefore.getRGB(x, y), b = decodedAfter.getRGB(x, y);
            for (int shift : new int[]{0, 8, 16}) error += Math.abs(((a >> shift) & 255) - ((b >> shift) & 255));
        }
        double meanError = error / (240.0 * 180 * 3);
        System.out.printf("PHOTO_BYTES before=%d after=%d mean_rgb_error=%.2f%n", before.length, after.length, meanError);
        assertTrue(after.length < before.length);
        assertTrue(meanError < 8, "JPEG detail loss exceeded test threshold: " + meanError);
    }

    @Test
    void transparentPngScalesAndKeepsAlpha() throws Exception {
        BufferedImage input = new BufferedImage(3000, 300, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = input.createGraphics();
        g.setColor(new Color(230, 40, 20, 128)); g.fillRect(0, 0, 1500, 300); g.dispose();
        byte[] bytes = png(input);
        byte[] result = new ImageProcessor().process(bytes, ".png");
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result));
        assertEquals(2560, decoded.getWidth());
        assertEquals(0, decoded.getRGB(2400, 100) >>> 24);
        assertEquals(128, decoded.getRGB(200, 100) >>> 24);
    }

    @Test
    void exifOrientationIsAppliedBeforeSaving() throws Exception {
        BufferedImage input = new BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = input.createGraphics(); g.setColor(Color.RED); g.fillRect(0, 0, 40, 40); g.setColor(Color.BLUE); g.fillRect(40, 0, 40, 40); g.dispose();
        byte[] original = jpeg(input, 0.98f);
        // JPEG APP1 with a little-endian TIFF IFD and orientation = 6.
        byte[] exif = new byte[]{(byte)0xff,(byte)0xe1,0,34,'E','x','i','f',0,0,'I','I',42,0,8,0,0,0,1,0,0x12,1,3,0,1,0,0,0,6,0,0,0,0,0,0,0};
        ByteArrayOutputStream oriented = new ByteArrayOutputStream();
        oriented.write(original, 0, 2); oriented.write(exif); oriented.write(original, 2, original.length - 2);
        byte[] output = new ImageProcessor().process(oriented.toByteArray(), ".jpg");
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(output));
        assertEquals(40, image.getWidth()); assertEquals(80, image.getHeight());
        assertTrue(new Color(image.getRGB(20, 10)).getRed() > new Color(image.getRGB(20, 10)).getBlue());
        assertTrue(new Color(image.getRGB(20, 70)).getBlue() > new Color(image.getRGB(20, 70)).getRed());
    }

    @Test
    void pngExifOrientationIsApplied() throws Exception {
        BufferedImage input = new BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = input.createGraphics(); g.setColor(Color.RED); g.fillRect(0, 0, 40, 40); g.setColor(Color.BLUE); g.fillRect(40, 0, 40, 40); g.dispose();
        byte[] exif = {'I','I',42,0,8,0,0,0,1,0,0x12,1,3,0,1,0,0,0,6,0,0,0,0,0,0,0};
        byte[] result = new ImageProcessor().process(withPngChunk(png(input), "eXIf", exif), ".png");
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(result));
        assertEquals(40, image.getWidth()); assertEquals(80, image.getHeight());
        assertTrue(new Color(image.getRGB(20, 10)).getRed() > new Color(image.getRGB(20, 10)).getBlue());
    }

    @Test
    void compressedPngTextDoesNotExpandDuringOrientationInspection() throws Exception {
        var compressed = new ByteArrayOutputStream();
        compressed.write(new byte[]{'n','o','t','e',0,0});
        try (var deflater = new java.util.zip.DeflaterOutputStream(compressed)) {
            byte[] block = new byte[8192];
            for (int i = 0; i < 24576; i++) deflater.write(block); // 192 MiB compressed metadata
        }
        byte[] png = png(new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB));
        byte[] output = new ImageProcessor().process(withPngChunk(png, "zTXt", compressed.toByteArray()), ".png");
        assertNotNull(ImageIO.read(new ByteArrayInputStream(output)));
    }

    @Test
    void animatedGifAndWebpRemainUnchanged() throws Exception {
        byte[] gif = animatedGif();
        byte[] output = new ImageProcessor().process(gif, ".gif");
        assertArrayEquals(gif, output);
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(output))) {
            var reader = ImageIO.getImageReaders(stream).next(); reader.setInput(stream);
            assertEquals(2, reader.getNumImages(true)); reader.dispose();
        }
        byte[] webp = Base64.getDecoder().decode("UklGRjwAAABXRUJQVlA4IDAAAAAwAgCdASoCAAIAAUAmJaACdLoB+AH4AAYeAAD+8U2v/Vn00Iv7SP/1v/qCA/SAAAA=");
        assertArrayEquals(webp, new ImageProcessor().process(webp, ".webp"));
        byte[] animatedWebp = Base64.getDecoder().decode("UklGRtwAAABXRUJQVlA4WAoAAAACAAAAHwAAHwAAQU5JTQYAAAD/////AABBTk1GWAAAAAAAAAAAAB8AAB8AAMgAAAJWUDggQAAAAHADAJ0BKiAAIAA+bTSWR6QjIiEoCACADYllAMougH4AAEGUEAD+8JtD/8guWF1yNf/yA/5Af8gP/499jCgwAABBTk1GUAAAAAAAAAAAAB8AAB8AAMgAAABWUDggOAAAALQCAJ0BKiAAIAA+bTSWR4KAgAAA2JZQB2AACAoKAAD++R3b//5Af/5Af/5Af/yA//4hd7IDAAAA");
        assertArrayEquals(animatedWebp, new ImageProcessor().process(animatedWebp, ".webp"));
    }

    @Test
    void animatedPngIsNotFlattened() throws Exception {
        byte[] bytes = animatedPng();
        assertNotNull(ImageIO.read(new ByteArrayInputStream(bytes)));
        assertArrayEquals(bytes, new ImageProcessor().process(bytes, ".png"));
    }

    @Test
    void corruptAndOversizedDimensionsAreRejected() throws Exception {
        ImageUploadService service = service();
        byte[] bogus = {(byte)0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0};
        assertThrows(BusinessException.class, () -> service.uploadAdminImage(file("bad.png", "image/png", bogus)));
        byte[] large = png(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB));
        large[16] = 0; large[17] = 0; large[18] = 0x40; large[19] = 0; // width 16384
        assertThrows(BusinessException.class, () -> service.uploadAdminImage(file("big.png", "image/png", large)));
        assertThrows(BusinessException.class, () -> service.uploadAdminImage(file("bad.webp", "image/webp", "RIFF0000WEBP".getBytes())));
    }

    @Test
    void repeatedConcurrentUploadsShareOneCompletePublicFileAndAvatarsStaySeparate() throws Exception {
        ImageUploadService service = service();
        byte[] image = png(new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB));
        var pool = Executors.newFixedThreadPool(8);
        try {
            var futures = IntStream.range(0, 16).mapToObj(i -> pool.submit(() -> service.uploadAdminImage(file("photo.png", "image/png", image)).url())).toList();
            String url = futures.getFirst().get();
            for (var future : futures) assertEquals(url, future.get());
            assertEquals(url, service.uploadAdminImage(file("renamed.png", "image/png", image)).url());
            String avatar1 = service.uploadAvatar(file("avatar.png", "image/png", image)).url();
            String avatar2 = service.uploadAvatar(file("avatar.png", "image/png", image)).url();
            assertNotEquals(url, avatar1); assertNotEquals(avatar1, avatar2);
            assertTrue(service.deleteManagedAvatar(avatar1));
            assertTrue(Files.exists(temporary.resolve("uploads").resolve(url.substring(url.lastIndexOf('/') + 1))));
            assertTrue(Files.exists(temporary.resolve("uploads").resolve(avatar2.substring("/api/uploads/".length()))));
            try (var files = Files.list(temporary.resolve("uploads"))) {
                assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(".myshop-")));
            }
        } finally { pool.shutdownNow(); }
    }

    private ImageUploadService service() {
        UploadStorage storage = new UploadStorage(temporary.resolve("uploads").toString()); storage.initialize();
        return new ImageUploadService(storage, new ImageProcessor(), "/api/uploads");
    }
    private static MockMultipartFile file(String name, String type, byte[] bytes) { return new MockMultipartFile("file", name, type, bytes); }
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
    private static byte[] animatedGif() throws IOException {
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

    private static byte[] animatedPng() throws IOException {
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
