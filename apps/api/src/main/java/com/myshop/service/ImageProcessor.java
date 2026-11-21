package com.myshop.service;

import com.drew.imaging.jpeg.JpegMetadataReader;
import com.drew.lang.ByteArrayReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifReader;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Semaphore;

/** Bounded decoding and format-preserving processing for untrusted uploads. */
@Component
public class ImageProcessor {
    static final int MAX_DIMENSION = 12_000;
    static final long MAX_PIXELS = 20_000_000L;
    static final int OUTPUT_DIMENSION = 2560;
    // A single decode prevents a burst of small compressed images exhausting the heap.
    private final Semaphore decodePermit = new Semaphore(1, true);

    public record ProcessedImage(byte[] bytes, String extension, String reason) { }

    /** Compress eligible images using bounded decoding and visual quality checks. */
    public ProcessedImage processForStorage(byte[] input, String extension) throws IOException {
        acquire();
        try {
            byte[] base = processBounded(input, extension);
            String ext = extension.equals(".jpeg") ? ".jpg" : extension;
            if (!ext.equals(".png") && !ext.equals(".jpg")) return new ProcessedImage(base, ext, "format_preserved");
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(input));
            if (original == null) throw new IOException("Invalid image");
            boolean jpegMetadata = ext.equals(".jpg") && hasPreservableJpegMetadata(input);
            boolean resized = Math.max(original.getWidth(), original.getHeight()) > OUTPUT_DIMENSION;
            // EXIF pixel dimensions and embedded thumbnails become stale when
            // the image is resized. Preserve such a source intact in that case.
            if (jpegMetadata && resized) return new ProcessedImage(input, ext, "metadata_preserved");
            if (jpegMetadata && !Arrays.equals(base, input)) {
                byte[] withMetadata = copyJpegApplicationSegments(input, base);
                if (withMetadata == null || withMetadata.length >= input.length)
                    return new ProcessedImage(input, ext, "metadata_preserved");
                base = withMetadata;
            } else if (jpegMetadata) {
                return new ProcessedImage(input, ext, "metadata_preserved");
            }
            if ((long) original.getWidth() * original.getHeight() > 4_000_000L) {
                if (!ext.equals(".png")) {
                    BufferedImage result = ImageIO.read(new ByteArrayInputStream(base));
                    if (result == null || !acceptableJpeg(original, result))
                        return new ProcessedImage(input, ext, "quality_preserved");
                }
                return new ProcessedImage(base, ext, Arrays.equals(base, input) ? "size_preserved" : "compressed");
            }
            if (!ext.equals(".png")) {
                BufferedImage result = ImageIO.read(new ByteArrayInputStream(base));
                if (result != null && !acceptableJpeg(original, result))
                    return new ProcessedImage(input, ext, "quality_preserved");
                return new ProcessedImage(base, ext, Arrays.equals(base, input) ? "no_saving" : "compressed");
            }
            if (!safePngForConversion(input)) return new ProcessedImage(input, ext, "metadata_preserved");
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(base));
            if (image == null || hasTransparency(image)) return new ProcessedImage(base, ext, "transparency_preserved");
            BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            try { g.drawImage(image, 0, 0, null); } finally { g.dispose(); }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Thumbnails.of(rgb).scale(1.0).outputFormat("jpg").outputQuality(0.95).toOutputStream(output);
            byte[] jpeg = output.toByteArray();
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(jpeg));
            if (jpeg.length + 12_000 < base.length && jpeg.length * 4L <= base.length * 3L
                    && decoded != null && acceptableJpeg(rgb, decoded))
                return new ProcessedImage(jpeg, ".jpg", "compressed");
            return new ProcessedImage(base, ext, Arrays.equals(base, input) ? "no_saving" : "compressed");
        } finally { decodePermit.release(); }
    }

    private static boolean hasTransparency(BufferedImage image) {
        if (!image.getColorModel().hasAlpha()) return false;
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < image.getWidth(); x++)
                if ((image.getRGB(x, y) >>> 24) != 255) return true;
        return false;
    }

    private static boolean safePngForConversion(byte[] bytes) throws IOException {
        PngInfo info = inspectPng(bytes);
        if (info.animated() || info.exif() != null) return false;
        for (int pos = 8; pos + 12 <= bytes.length;) {
            int length = (int) be32(bytes, pos);
            String type = new String(bytes, pos + 4, 4, StandardCharsets.US_ASCII);
            if (List.of("iCCP", "gAMA", "cHRM").contains(type)) return false;
            pos += length + 12;
            if (type.equals("IEND")) return pos == bytes.length;
        }
        return false;
    }

    private static boolean acceptableJpeg(BufferedImage original, BufferedImage result) {
        if (result.getWidth() > original.getWidth() || result.getHeight() > original.getHeight()) return false;
        if (original.getWidth() != result.getWidth() || original.getHeight() != result.getHeight()) {
            double originalRatio = (double) original.getWidth() / original.getHeight();
            double resultRatio = (double) result.getWidth() / result.getHeight();
            if (Math.abs(originalRatio - resultRatio) / originalRatio > 0.005) return false;
            try {
                ByteArrayOutputStream scaled = new ByteArrayOutputStream();
                Thumbnails.of(original).size(result.getWidth(), result.getHeight()).outputFormat("png").toOutputStream(scaled);
                BufferedImage reference = ImageIO.read(new ByteArrayInputStream(scaled.toByteArray()));
                return reference != null && acceptableJpeg(reference, result);
            } catch (IOException | RuntimeException ignored) { return false; }
        }
        long error = 0, large = 0, channels = (long) original.getWidth() * original.getHeight() * 3;
        for (int y = 0; y < original.getHeight(); y++) for (int x = 0; x < original.getWidth(); x++) {
            int a = original.getRGB(x, y), b = result.getRGB(x, y);
            for (int shift = 0; shift <= 16; shift += 8) {
                int d = Math.abs((a >> shift & 255) - (b >> shift & 255));
                error += d;
                if (d > 20) large++;
            }
        }
        return error <= channels * 4L && large * 100L <= channels;
    }

    static boolean acceptableJpeg(byte[] before, byte[] after) {
        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(before));
            BufferedImage result = ImageIO.read(new ByteArrayInputStream(after));
            return original != null && result != null && acceptableJpeg(original, result);
        } catch (IOException | RuntimeException ignored) { return false; }
    }

    static boolean eligibleOldJpeg(byte[] bytes, int width, int height) {
        try {
            if (orientation(bytes, null) != 1) return false;
            return !hasPreservableJpegMetadata(bytes)
                    || (width <= OUTPUT_DIMENSION && height <= OUTPUT_DIMENSION);
        } catch (IOException | RuntimeException ignored) { return false; }
    }

    private static boolean hasPreservableJpegMetadata(byte[] bytes) {
        try {
            return jpegApplicationSegments(bytes).stream().anyMatch(segment -> {
                int marker = segment[1] & 0xff;
                return marker >= 0xe1 && marker <= 0xef;
            });
        } catch (IOException ignored) { return true; }
    }

    private static byte[] copyJpegApplicationSegments(byte[] source, byte[] encoded) {
        try {
            var metadata = jpegApplicationSegments(source);
            ByteArrayOutputStream output = new ByteArrayOutputStream(encoded.length + metadata.stream().mapToInt(a -> a.length).sum());
            output.write(encoded, 0, 2);
            for (byte[] segment : metadata) output.write(segment);
            int position = 2;
            while (position < encoded.length) {
                int start = position;
                if ((encoded[position++] & 0xff) != 0xff) return null;
                while (position < encoded.length && (encoded[position] & 0xff) == 0xff) position++;
                if (position >= encoded.length) return null;
                int marker = encoded[position++] & 0xff;
                if (marker == 0xda || marker == 0xd9) {
                    output.write(encoded, start, encoded.length - start);
                    return output.toByteArray();
                }
                if (marker == 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
                    if (marker < 0xe0) output.write(encoded, start, position - start);
                    continue;
                }
                if (position + 2 > encoded.length) return null;
                int length = ((encoded[position] & 0xff) << 8) | (encoded[position + 1] & 0xff);
                int end = position + length;
                if (length < 2 || end > encoded.length) return null;
                if (!((marker >= 0xe0 && marker <= 0xef) || marker == 0xfe))
                    output.write(encoded, start, end - start);
                position = end;
            }
            return null;
        } catch (IOException | RuntimeException ignored) { return null; }
    }

    private static List<byte[]> jpegApplicationSegments(byte[] bytes) throws IOException {
        if (bytes.length < 4 || (bytes[0] & 0xff) != 0xff || (bytes[1] & 0xff) != 0xd8)
            throw new IOException("Invalid JPEG header");
        List<byte[]> segments = new ArrayList<>();
        int position = 2;
        while (position < bytes.length) {
            int start = position;
            if ((bytes[position++] & 0xff) != 0xff) throw new IOException("Invalid JPEG marker");
            while (position < bytes.length && (bytes[position] & 0xff) == 0xff) position++;
            if (position >= bytes.length) throw new IOException("Truncated JPEG marker");
            int marker = bytes[position++] & 0xff;
            if (marker == 0xda || marker == 0xd9) return segments;
            if (marker == 0x01 || (marker >= 0xd0 && marker <= 0xd7)) continue;
            if (position + 2 > bytes.length) throw new IOException("Truncated JPEG segment");
            int length = ((bytes[position] & 0xff) << 8) | (bytes[position + 1] & 0xff);
            int end = position + length;
            if (length < 2 || end > bytes.length) throw new IOException("Invalid JPEG segment length");
            if ((marker >= 0xe0 && marker <= 0xef) || marker == 0xfe)
                segments.add(Arrays.copyOfRange(bytes, start, end));
            position = end;
        }
        throw new IOException("JPEG scan marker missing");
    }

    public byte[] process(byte[] input, String extension) throws IOException {
        acquire();
        try { return processBounded(input, extension); }
        finally { decodePermit.release(); }
    }

    public byte[] process(MultipartFile file, String extension) throws IOException {
        acquire();
        try {
            byte[] input = file.getBytes();
            if (input.length > 10 * 1024 * 1024) throw new IOException("Image is too large");
            return processBounded(input, extension);
        } finally { decodePermit.release(); }
    }

    private void acquire() throws IOException {
        try {
            decodePermit.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Image processing interrupted", e);
        }
    }

    private byte[] processBounded(byte[] input, String extension) throws IOException {
        try {
            if (extension.equals(".webp")) {
                checkWebp(input);
                return input;
            }
            if (extension.equals(".gif") && input.length >= 10) {
                checkDimensions(le16(input, 6), le16(input, 8));
            }
            PngInfo png = extension.equals(".png") ? inspectPng(input) : null;
            try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(input))) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) throw new IOException("Unsupported image");
                ImageReader reader = readers.next();
                try {
                    reader.setInput(stream, false, true);
                    int width = reader.getWidth(0);
                    int height = reader.getHeight(0);
                    checkDimensions(width, height);
                    BufferedImage image = reader.read(0);
                    if (image == null) throw new IOException("Image cannot be decoded");
                    if (!extension.equals(".jpg") && !extension.equals(".jpeg") && !extension.equals(".png")) return input;
                    // APNG is a PNG too: keep its animation and original bytes.
                    if (png != null && png.animated()) return input;
                    int orientation = orientation(input, png);
                    image = orient(image, orientation);
                    boolean resized = Math.max(width, height) > OUTPUT_DIMENSION;
                    var builder = (resized ? Thumbnails.of(image).size(OUTPUT_DIMENSION, OUTPUT_DIMENSION)
                            : Thumbnails.of(image).scale(1.0)).outputFormat(extension.equals(".png") ? "png" : "jpg");
                    if (!extension.equals(".png")) builder.outputQuality(0.92);
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    builder.toOutputStream(output);
                    byte[] optimized = output.toByteArray();
                    // Retain the original when recompression would waste space and no
                    // orientation correction or resize was required.
                    return !resized && orientation == 1 && optimized.length >= input.length ? input : optimized;
                } finally {
                    reader.dispose();
                }
            }
        } catch (RuntimeException e) {
            throw new IOException("Invalid image", e);
        }
    }

    private static void checkDimensions(int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION
                || (long) width * height > MAX_PIXELS) throw new IOException("Image dimensions exceed limit");
    }

    private static int orientation(byte[] input, PngInfo png) throws IOException {
        try {
            Metadata metadata;
            if (png != null) {
                if (png.exif() == null) return 1;
                metadata = new Metadata();
                new ExifReader().extract(new ByteArrayReader(png.exif()), metadata);
            } else {
                // Only read JPEG EXIF, not arbitrary XMP/ICC or other metadata.
                metadata = JpegMetadataReader.readMetadata(new ByteArrayInputStream(input), List.of(new ExifReader()));
            }
            var directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (directory != null && directory.hasErrors()) throw new IOException("Invalid image EXIF");
            Integer value = directory == null ? null : directory.getInteger(ExifIFD0Directory.TAG_ORIENTATION);
            if (value == null) return 1;
            // Some editors write Orientation=0. Treat that invalid optional
            // hint as the encoded pixel order; the JPEG is still decodable.
            if (value < 1 || value > 8) return 1;
            return value;
        } catch (com.drew.imaging.ImageProcessingException e) {
            throw new IOException("Invalid image metadata", e);
        }
    }

    private static BufferedImage orient(BufferedImage source, int orientation) {
        if (orientation == 1) return source;
        int w = source.getWidth(), h = source.getHeight();
        boolean swap = orientation >= 5;
        BufferedImage result = new BufferedImage(swap ? h : w, swap ? w : h,
                source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        AffineTransform transform = switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, w);
            default -> new AffineTransform();
        };
        Graphics2D graphics = result.createGraphics();
        try { graphics.drawImage(source, transform, null); } finally { graphics.dispose(); }
        return result;
    }

    private static PngInfo inspectPng(byte[] bytes) throws IOException {
        int pos = 8;
        boolean animated = false;
        byte[] exif = null;
        while (pos + 12 <= bytes.length) {
            long length = be32(bytes, pos);
            if (length > bytes.length - pos - 12L) throw new IOException("Invalid PNG chunk");
            String type = new String(bytes, pos + 4, 4, StandardCharsets.US_ASCII);
            if (type.equals("acTL")) animated = true;
            if (type.equals("eXIf")) {
                if (exif != null || length > 1024 * 1024) throw new IOException("Invalid PNG EXIF");
                exif = Arrays.copyOfRange(bytes, pos + 8, pos + 8 + (int) length);
            }
            pos += (int) length + 12;
            if (type.equals("IEND")) return new PngInfo(animated, exif);
        }
        throw new IOException("Invalid PNG structure");
    }

    private record PngInfo(boolean animated, byte[] exif) { }

    /** Inspect the RIFF container without decoding or changing animated WebP. */
    private static void checkWebp(byte[] bytes) throws IOException {
        if (bytes.length < 20 || le32(bytes, 4) + 8 != bytes.length) throw new IOException("Invalid WebP container");
        int pos = 12;
        boolean dimensionsFound = false;
        boolean imageFound = false;
        while (pos + 8 <= bytes.length) {
            String type = new String(bytes, pos, 4, StandardCharsets.US_ASCII);
            long length = le32(bytes, pos + 4);
            long next = pos + 8L + length + (length & 1);
            if (next > bytes.length) throw new IOException("Invalid WebP chunk");
            int data = pos + 8;
            if (type.equals("VP8X") && length >= 10) {
                checkDimensions(1 + le24(bytes, data + 4), 1 + le24(bytes, data + 7));
                dimensionsFound = true;
            } else if (type.equals("VP8L") && length > 5 && (bytes[data] & 255) == 0x2f) {
                int bits = (int) le32(bytes, data + 1);
                checkDimensions((bits & 0x3fff) + 1, ((bits >>> 14) & 0x3fff) + 1);
                dimensionsFound = true;
                imageFound = true;
            } else if (type.equals("VP8 ") && length > 10 && bytes[data + 3] == (byte) 0x9d
                    && bytes[data + 4] == 0x01 && bytes[data + 5] == 0x2a) {
                checkDimensions(le16(bytes, data + 6) & 0x3fff, le16(bytes, data + 8) & 0x3fff);
                dimensionsFound = true;
                imageFound = true;
            } else if (type.equals("ANMF") && length >= 24) {
                checkDimensions(1 + le24(bytes, data + 6), 1 + le24(bytes, data + 9));
                imageFound |= checkWebpFrame(bytes, data + 16, data + (int) length);
            }
            pos = (int) next;
        }
        if (!dimensionsFound || !imageFound || pos != bytes.length) throw new IOException("Invalid WebP image");
    }

    private static boolean checkWebpFrame(byte[] bytes, int pos, int end) throws IOException {
        boolean imageFound = false;
        while (pos + 8 <= end) {
            String type = new String(bytes, pos, 4, StandardCharsets.US_ASCII);
            long length = le32(bytes, pos + 4);
            long next = pos + 8L + length + (length & 1);
            if (next > end) throw new IOException("Invalid WebP frame");
            int data = pos + 8;
            if (type.equals("VP8L") && length > 5 && (bytes[data] & 255) == 0x2f) {
                int bits = (int) le32(bytes, data + 1);
                checkDimensions((bits & 0x3fff) + 1, ((bits >>> 14) & 0x3fff) + 1);
                imageFound = true;
            } else if (type.equals("VP8 ") && length > 10 && bytes[data + 3] == (byte) 0x9d
                    && bytes[data + 4] == 0x01 && bytes[data + 5] == 0x2a) {
                checkDimensions(le16(bytes, data + 6) & 0x3fff, le16(bytes, data + 8) & 0x3fff);
                imageFound = true;
            }
            pos = (int) next;
        }
        if (pos != end || !imageFound) throw new IOException("Invalid WebP frame");
        return true;
    }

    private static int le16(byte[] b, int p) { return (b[p] & 255) | ((b[p + 1] & 255) << 8); }
    private static int le24(byte[] b, int p) { return le16(b, p) | ((b[p + 2] & 255) << 16); }
    private static long le32(byte[] b, int p) { return (long) le16(b, p) | ((long) le16(b, p + 2) << 16); }
    private static long be32(byte[] b, int p) { return ((long) (b[p] & 255) << 24) | ((long) (b[p+1] & 255) << 16) | ((long) (b[p+2] & 255) << 8) | (b[p+3] & 255L); }
}
