package com.myshop.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.qrcode.QRCodeWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ImageStorageCompressionTest {
    @TempDir Path directory;

    @Test void opaquePhotoPngCanBecomeSmallerJpegWithMatchingExtension() throws Exception {
        byte[] png = noisyPoster();
        var result = new ImageProcessor().processForStorage(png, ".png");
        assertEquals(".jpg", result.extension());
        assertTrue(result.bytes().length * 4L < png.length * 3L);
        assertEquals((byte) 0xff, result.bytes()[0]);
        assertEquals((byte) 0xd8, result.bytes()[1]);
        BufferedImage restored = ImageIO.read(new ByteArrayInputStream(result.bytes()));
        assertEquals(800, restored.getWidth());
        assertEquals(800, restored.getHeight());

        UploadStorage storage = new UploadStorage(directory.toString());
        storage.initialize();
        ImageUploadService service = new ImageUploadService(storage, new ImageProcessor(), "/api/uploads");
        var input = new MockMultipartFile("file", "poster.png", "image/png", png);
        var first = service.uploadAdminImage(input);
        assertTrue(first.url().endsWith(".jpg"));
        assertEquals(first.url(), service.uploadAdminImage(input).url());
        byte[] stored = Files.readAllBytes(storage.resolve(first.relativePath()));
        assertEquals(UploadStorage.identifier(stored) + ".jpg", first.relativePath());
        var qrMode = service.uploadAdminImage(input, true);
        assertTrue(qrMode.url().endsWith(".png"));
        assertArrayEquals(png, Files.readAllBytes(storage.resolve(qrMode.relativePath())));
    }

    private static byte[] noisyPoster() throws Exception {
        BufferedImage image = new BufferedImage(800, 800, BufferedImage.TYPE_INT_RGB);
        java.util.Random random = new java.util.Random(42);
        for (int y = 0; y < 800; y++) for (int x = 0; x < 800; x++) {
            int n = random.nextInt(12);
            image.setRGB(x, y, new Color(210 + n, 220 + n, 230 + n).getRGB());
        }
        Graphics2D g = image.createGraphics();
        g.setColor(Color.BLACK);
        g.drawString("Image text 123", 200, 300);
        g.dispose();
        return png(image);
    }

    @Test void qrAndTransparentPngKeepTheirPixelsAndFormat() throws Exception {
        var bits = new QRCodeWriter().encode("myshop test qr", BarcodeFormat.QR_CODE, 320, 320);
        BufferedImage qr = new BufferedImage(320, 320, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 320; y++) for (int x = 0; x < 320; x++)
            qr.setRGB(x, y, bits.get(x, y) ? Color.BLACK.getRGB() : Color.WHITE.getRGB());
        byte[] source = png(qr);
        var kept = new ImageProcessor().processForStorage(source, ".png");
        assertEquals(".png", kept.extension());
        assertArrayEquals(source, kept.bytes());

        BufferedImage alpha = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        alpha.setRGB(1, 1, 0x77ff00ff);
        var result = new ImageProcessor().processForStorage(png(alpha), ".png");
        assertEquals(".png", result.extension());
        assertEquals(0x77ff00ff, ImageIO.read(new ByteArrayInputStream(result.bytes())).getRGB(1, 1));
    }

    @Test void invalidExifOrientationDoesNotBlockUploadAndJpegMetadataIsPreserved() throws Exception {
        BufferedImage image = new BufferedImage(96, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(35, 115, 210)); graphics.fillRect(0, 0, 96, 64);
        graphics.setColor(Color.WHITE); graphics.drawString("metadata fixture", 8, 34); graphics.dispose();
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "jpg", jpeg));

        byte[] exifOrientationZero = {
                (byte) 0xff, (byte) 0xe1, 0, 34, 'E', 'x', 'i', 'f', 0, 0,
                'I', 'I', 42, 0, 8, 0, 0, 0, 1, 0,
                0x12, 1, 3, 0, 1, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0
        };
        byte[] icc = new byte[20];
        byte[] iccHeader = {'I','C','C','_','P','R','O','F','I','L','E',0,1,1};
        System.arraycopy(iccHeader, 0, icc, 0, iccHeader.length);
        ByteArrayOutputStream source = new ByteArrayOutputStream();
        source.write(jpeg.toByteArray(), 0, 2);
        source.write(exifOrientationZero);
        var segment = new DataOutputStream(source);
        segment.writeByte(0xff); segment.writeByte(0xe2); segment.writeShort(icc.length + 2); segment.write(icc);
        source.write(jpeg.toByteArray(), 2, jpeg.size() - 2);
        byte[] inputBytes = source.toByteArray();

        UploadStorage storage = new UploadStorage(directory.resolve("metadata-upload").toString());
        storage.initialize();
        ImageUploadService service = new ImageUploadService(storage, new ImageProcessor(), "/api/uploads");
        var uploaded = service.uploadAdminImage(new MockMultipartFile("file", "retouch.jpg", "image/jpeg", inputBytes));
        assertEquals("metadata_preserved", uploaded.reason());
        assertEquals(inputBytes.length, uploaded.storedSize());
        assertArrayEquals(inputBytes, Files.readAllBytes(storage.resolve(uploaded.relativePath())));
        assertTrue(uploaded.url().endsWith(".jpg"));
    }

    private static byte[] png(BufferedImage image) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return output.toByteArray();
    }
}
