package com.myshop.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ImageStorageCompressionTest {
    @TempDir Path directory;

    @Test void photographicPngIsCompressedToAUniformSetOfWebpVersions() throws Exception {
        Path photograph = fixture("fox.avif");
        Path png = directory.resolve("photograph.png");
        runVips(photograph, png, "800x>");
        try (var result = new VipsImageProcessor().process(png, directory.resolve("work"), VipsImageProcessor.Purpose.PRODUCT)) {
            assertEquals("image/webp", result.display().mime());
            assertEquals(result.display().mime(), result.thumbnail().mime());
            assertEquals(result.display().mime(), result.clear().mime());
            assertTrue(result.display().size() < Files.size(png), "photo PNG should benefit from compression");
            assertTrue(result.display().size() <= 300 * 1024, "representative photo should meet the display target");
            assertEquals(800, result.display().width());
            assertEquals(480, result.thumbnail().width());
            assertEquals(800, result.clear().width());
        }
    }

    @Test void realHeicAndAvifAreDecodedAndConverted() throws Exception {
        for (String name : new String[]{"fox-derived.heic", "fox-p3-icc.heic", "fox.avif"}) {
            Path source = fixture(name);
            try (var result = new VipsImageProcessor().process(source, directory.resolve("work"), VipsImageProcessor.Purpose.PRODUCT)) {
                assertFalse(result.animated());
                assertEquals("image/webp", result.display().mime());
                assertEquals(result.display().mime(), result.thumbnail().mime());
                assertEquals(result.display().mime(), result.clear().mime());
                assertTrue(result.display().width() > 0);
                assertNotNull(decode(result.display()));
                if (name.equals("fox-derived.heic") || name.equals("fox.avif"))
                    assertEquals("color_unspecified", result.reason());
                else assertNotEquals("color_unspecified", result.reason());
            }
        }
    }

    @Test void heicWithNoColorPropertyIsDecodedAndReportsSrgbAssumption() throws Exception {
        byte[] input = Files.readAllBytes(fixture("fox-derived.heic"));
        byte[] marker = "colrnclx".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int offset = -1;
        for (int i = 0; i <= input.length - marker.length; i++) {
            boolean match = true;
            for (int j = 0; j < marker.length; j++) if (input[i + j] != marker[j]) { match = false; break; }
            if (match) {
                assertEquals(-1, offset, "the fixture must contain one nclx property");
                offset = i;
            }
        }
        assertTrue(offset >= 4, "the fixture must contain an nclx property");
        // Replace the property type with an ignorable free box of identical
        // size. The real HEIC payload, item offsets, and dimensions are intact.
        System.arraycopy("free".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, input, offset, 4);
        Path noProfile = directory.resolve("fox-no-profile.heic");
        Files.write(noProfile, input);
        Process inspect = new ProcessBuilder("heif-info", noProfile.toString()).redirectErrorStream(true).start();
        assertTrue(inspect.waitFor(45, TimeUnit.SECONDS));
        String report = new String(inspect.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, inspect.exitValue(), report);
        assertTrue(report.contains("color profile: no"), report);
        try (var result = new VipsImageProcessor().process(noProfile, directory.resolve("work"), VipsImageProcessor.Purpose.PRODUCT)) {
            assertEquals("color_unspecified", result.reason());
            assertEquals("image/webp", result.display().mime());
            assertNotNull(decode(result.display()));
        }
    }

    @Test void displayP3NclxIsConvertedAndAmbiguousProfilesAreRejected() throws Exception {
        assertNclxConvertedToSrgb("fox-p3-nclx.heic");
        var processor = new VipsImageProcessor();
        var conflict = assertThrows(java.io.IOException.class, () -> processor.process(fixture("fox-conflict.heic"),
                directory.resolve("work"), VipsImageProcessor.Purpose.PRODUCT));
        assertTrue(conflict.getMessage().contains("both ICC and nclx"));
        var hdr = assertThrows(java.io.IOException.class, () -> processor.process(fixture("fox-hdr.heic"),
                directory.resolve("work"), VipsImageProcessor.Purpose.PRODUCT));
        assertTrue(hdr.getMessage().contains("unsupported"));
    }

    @Test void rec709NclxIsConvertedToSrgb() throws Exception {
        assertNclxConvertedToSrgb("fox-rec709-nclx.heic");
    }

    private void assertNclxConvertedToSrgb(String sample) throws Exception {
        Path referencePng = directory.resolve("reference.png");
        runVips(fixture("fox.avif"), referencePng, "30000x>");
        BufferedImage reference = ImageIO.read(referencePng.toFile());

        Path naiveWebp = directory.resolve("unconverted.webp");
        Process naive = new ProcessBuilder("vipsthumbnail", fixture(sample).toString(), "--size", "1600x>",
                "--export-profile", "/usr/share/color/icc/sRGB.icc", "-o", naiveWebp + "[Q=82,effort=4,strip]")
                .redirectErrorStream(true).start();
        assertTrue(naive.waitFor(45, TimeUnit.SECONDS));
        String output = new String(naive.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, naive.exitValue(), output);
        Path naivePng = directory.resolve("unconverted.png");
        runVips(naiveWebp, naivePng, "30000x>");
        double unconvertedError = rgbError(reference, ImageIO.read(naivePng.toFile()));

        try (var converted = new VipsImageProcessor().process(fixture(sample),
                directory.resolve("work"), VipsImageProcessor.Purpose.PRODUCT)) {
            double convertedError = rgbError(reference, decode(converted.display()));
            assertTrue(convertedError < 3.2, "Converted image should match the sRGB reference: " + convertedError);
            assertTrue(convertedError + 0.4 < unconvertedError,
                    "converting nclx must improve color over ignoring it: " + convertedError + " vs " + unconvertedError);
        }
    }

    @Test void qrRemainsDecodableInBothDisplayAndThumbnail() throws Exception {
        String message = "https://mc.mc520.top/?test=qr";
        var bits = new QRCodeWriter().encode(message, BarcodeFormat.QR_CODE, 600, 600);
        BufferedImage qr = new BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 600; y++) for (int x = 0; x < 600; x++)
            qr.setRGB(x, y, bits.get(x, y) ? Color.BLACK.getRGB() : Color.WHITE.getRGB());
        Path source = directory.resolve("qr.png");
        ImageIO.write(qr, "png", source.toFile());
        try (var result = new VipsImageProcessor().process(source, directory.resolve("work"), VipsImageProcessor.Purpose.PRODUCT)) {
            assertTrue(result.qr());
            assertEquals(message, decodeQr(decode(result.display())));
            assertEquals(message, decodeQr(decode(result.thumbnail())));
            assertEquals(result.display().mime(), result.clear().mime());
        }
    }

    @Test void longImageUsesWidthAndPreservesSmallText() throws Exception {
        BufferedImage image = new BufferedImage(1000, 6000, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 1000, 6000);
        graphics.setColor(Color.BLACK);
        for (int y = 50; y < 5950; y += 55) graphics.drawString("Tutorial: confirm the next step 1234567890", 30, y);
        graphics.dispose();
        Path source = directory.resolve("long.png");
        ImageIO.write(image, "png", source.toFile());
        try (var result = new VipsImageProcessor().process(source, directory.resolve("work"), VipsImageProcessor.Purpose.PRODUCT)) {
            assertEquals("long_image_lossless", result.reason());
            assertEquals(1000, result.display().width());
            assertEquals(6000, result.display().height());
            BufferedImage decoded = decode(result.display());
            int darkPixels = 0;
            for (int y = 35; y < 50; y++) for (int x = 30; x < 280; x++)
                if ((decoded.getRGB(x, y) & 0xffffff) < 0x606060) darkPixels++;
            assertTrue(darkPixels > 30, "the first line of tutorial text must remain visible");
        }
    }

    @Test void embeddedLinearRgbProfileIsConvertedDespiteInvalidExifOrientation() throws Exception {
        BufferedImage image = new BufferedImage(800, 800, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(128, 80, 200)); graphics.fillRect(0, 0, 800, 800); graphics.dispose();
        byte[] original = jpeg(image);
        byte[] profile = ICC_Profile.getInstance(ColorSpace.CS_LINEAR_RGB).getData();
        byte[] icc = new byte[14 + profile.length];
        byte[] header = {'I','C','C','_','P','R','O','F','I','L','E',0,1,1};
        System.arraycopy(header, 0, icc, 0, header.length);
        System.arraycopy(profile, 0, icc, header.length, profile.length);
        ByteArrayOutputStream tagged = new ByteArrayOutputStream();
        tagged.write(original, 0, 2);
        byte[] invalidOrientation = {(byte) 0xff, (byte) 0xe1, 0, 34, 'E', 'x', 'i', 'f', 0, 0,
                'I', 'I', 42, 0, 8, 0, 0, 0, 1, 0, 0x12, 1, 3, 0,
                1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        tagged.write(invalidOrientation);
        var segment = new DataOutputStream(tagged);
        segment.writeByte(0xff); segment.writeByte(0xe2); segment.writeShort(icc.length + 2); segment.write(icc);
        tagged.write(original, 2, original.length - 2);
        Path source = directory.resolve("linear-profile.jpg");
        Files.write(source, tagged.toByteArray());
        try (var result = new VipsImageProcessor().process(source, directory.resolve("work"), VipsImageProcessor.Purpose.PRODUCT)) {
            Color pixel = new Color(decode(result.display()).getRGB(400, 400));
            assertTrue(pixel.getRed() >= 180 && pixel.getRed() <= 195, "linear red must be converted to sRGB: " + pixel);
            assertTrue(pixel.getGreen() >= 145 && pixel.getGreen() <= 160, "linear green must be converted to sRGB: " + pixel);
            assertTrue(pixel.getBlue() >= 225 && pixel.getBlue() <= 235, "linear blue must be converted to sRGB: " + pixel);
        }
    }

    private Path fixture(String name) throws Exception {
        return Path.of(getClass().getResource("/media-formats/" + name).toURI());
    }
    private BufferedImage decode(VipsImageProcessor.Variant variant) throws Exception {
        Path png = directory.resolve(UUID.randomUUID() + ".png");
        runVips(variant.path(), png, "30000x>");
        BufferedImage result = ImageIO.read(png.toFile());
        assertNotNull(result);
        return result;
    }
    private static void runVips(Path source, Path target, String size) throws Exception {
        Process process = new ProcessBuilder("vipsthumbnail", source.toString(), "--size", size,
                "-o", target.toString() + "[compression=3]").redirectErrorStream(true).start();
        assertTrue(process.waitFor(45, TimeUnit.SECONDS), "test image decode timed out");
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
    }
    private static String decodeQr(BufferedImage image) throws Exception {
        int width = image.getWidth(), height = image.getHeight();
        return new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(
                new RGBLuminanceSource(width, height, image.getRGB(0, 0, width, height, null, 0, width))))).getText();
    }
    private static double rgbError(BufferedImage reference, BufferedImage actual) {
        assertEquals(reference.getWidth(), actual.getWidth());
        assertEquals(reference.getHeight(), actual.getHeight());
        long error = 0, samples = 0;
        for (int y = 0; y < reference.getHeight(); y += 5) for (int x = 0; x < reference.getWidth(); x += 5) {
            int a = reference.getRGB(x, y), b = actual.getRGB(x, y);
            for (int shift : new int[]{0, 8, 16}) error += Math.abs(((a >> shift) & 255) - ((b >> shift) & 255));
            samples += 3;
        }
        return (double) error / samples;
    }
    private static byte[] jpeg(BufferedImage image) throws Exception {
        var writer = ImageIO.getImageWritersByFormatName("jpg").next();
        var output = new ByteArrayOutputStream();
        try (var stream = new MemoryCacheImageOutputStream(output)) {
            writer.setOutput(stream);
            ImageWriteParam options = writer.getDefaultWriteParam();
            options.setCompressionMode(ImageWriteParam.MODE_EXPLICIT); options.setCompressionQuality(0.99f);
            writer.write(null, new IIOImage(image, null, null), options);
        } finally { writer.dispose(); }
        return output.toByteArray();
    }
}
