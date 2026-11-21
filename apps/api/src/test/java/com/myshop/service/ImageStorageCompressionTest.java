package com.myshop.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.common.HybridBinarizer;
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
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
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
        var sameImageFromQrSlot = service.uploadAdminImage(input);
        assertEquals(first.url(), sameImageFromQrSlot.url());
        assertEquals(UploadStorage.identifier(stored) + ".jpg", sameImageFromQrSlot.relativePath());
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

    @Test void qrUsesStandardProcessingAndRemainsDecodable() throws Exception {
        var bits = new QRCodeWriter().encode("myshop test qr", BarcodeFormat.QR_CODE, 320, 320);
        BufferedImage qr = new BufferedImage(320, 320, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 320; y++) for (int x = 0; x < 320; x++)
            qr.setRGB(x, y, bits.get(x, y) ? Color.BLACK.getRGB() : Color.WHITE.getRGB());
        byte[] source = png(qr);
        var kept = new ImageProcessor().processForStorage(source, ".png");
        assertEquals(".png", kept.extension());
        assertNotEquals("qr_preserved", kept.reason());
        BufferedImage processedQr = ImageIO.read(new ByteArrayInputStream(kept.bytes()));
        assertEquals("myshop test qr", decodeQr(processedQr));

        BufferedImage alpha = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        alpha.setRGB(1, 1, 0x77ff00ff);
        var result = new ImageProcessor().processForStorage(png(alpha), ".png");
        assertEquals(".png", result.extension());
        assertEquals(0x77ff00ff, ImageIO.read(new ByteArrayInputStream(result.bytes())).getRGB(1, 1));
    }

    private static String decodeQr(BufferedImage image) throws Exception {
        int width = image.getWidth(), height = image.getHeight();
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        return new MultiFormatReader().decode(new BinaryBitmap(
                new HybridBinarizer(new RGBLuminanceSource(width, height, pixels)))).getText();
    }

    @Test void invalidExifOrientationDoesNotBlockUploadAndJpegMetadataSurvivesCompression() throws Exception {
        BufferedImage image = new BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB);
        java.util.Random random = new java.util.Random(314159);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            int texture = random.nextInt(9) - 4;
            int red = 48 + x * 110 / image.getWidth() + texture;
            int green = 55 + y * 100 / image.getHeight() + texture;
            int blue = 80 + (x + y) * 70 / (image.getWidth() + image.getHeight()) + texture;
            image.setRGB(x, y, (red << 16) | (green << 8) | blue);
        }
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        net.coobird.thumbnailator.Thumbnails.of(image).scale(1).outputFormat("jpg").outputQuality(0.99).toOutputStream(jpeg);

        byte[] exifOrientationZero = {
                (byte) 0xff, (byte) 0xe1, 0, 34, 'E', 'x', 'i', 'f', 0, 0,
                'I', 'I', 42, 0, 8, 0, 0, 0, 1, 0,
                0x12, 1, 3, 0, 1, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0
        };
        byte[] profile = ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData();
        byte[] icc = new byte[14 + profile.length];
        byte[] iccHeader = {'I','C','C','_','P','R','O','F','I','L','E',0,1,1};
        System.arraycopy(iccHeader, 0, icc, 0, iccHeader.length);
        System.arraycopy(profile, 0, icc, iccHeader.length, profile.length);
        ByteArrayOutputStream source = new ByteArrayOutputStream();
        source.write(jpeg.toByteArray(), 0, 2);
        source.write(exifOrientationZero);
        var segment = new DataOutputStream(source);
        segment.writeByte(0xff); segment.writeByte(0xe2); segment.writeShort(icc.length + 2); segment.write(icc);
        source.write(jpeg.toByteArray(), 2, jpeg.size() - 2);
        byte[] inputBytes = source.toByteArray();
        var processed = new ImageProcessor().processForStorage(inputBytes, ".jpg");
        assertEquals("compressed", processed.reason());
        assertTrue(processed.bytes().length < inputBytes.length);
        assertTrue(new String(processed.bytes(), java.nio.charset.StandardCharsets.ISO_8859_1).contains("Exif\0\0"));
        assertTrue(new String(processed.bytes(), java.nio.charset.StandardCharsets.ISO_8859_1).contains("ICC_PROFILE\0"));
        assertTrue(ImageProcessor.eligibleOldJpeg(inputBytes, image.getWidth(), image.getHeight()));

        UploadStorage storage = new UploadStorage(directory.resolve("metadata-upload").toString());
        storage.initialize();
        ImageUploadService service = new ImageUploadService(storage, new ImageProcessor(), "/api/uploads");
        var uploaded = service.uploadAdminImage(new MockMultipartFile("file", "retouch.jpg", "image/jpeg", inputBytes));
        assertEquals("compressed", uploaded.reason());
        assertEquals(processed.bytes().length, uploaded.storedSize());
        assertArrayEquals(processed.bytes(), Files.readAllBytes(storage.resolve(uploaded.relativePath())));
        assertTrue(uploaded.url().endsWith(".jpg"));
    }

    private static byte[] png(BufferedImage image) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return output.toByteArray();
    }
}
