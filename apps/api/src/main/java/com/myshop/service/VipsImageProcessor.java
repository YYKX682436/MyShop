package com.myshop.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Bounded local-file processing for public images. It never publishes files.
 * The caller owns the input; the returned result owns an isolated temporary
 * directory and must be closed after its variants have been atomically published.
 */
@Component
public class VipsImageProcessor {
    public enum Purpose { PRODUCT, AVATAR, FAVICON, QR }

    public record Variant(Path path, String mime, int width, int height, long size) { }

    public static final class Processed implements AutoCloseable {
        private final Path directory;
        private final Variant display;
        private final Variant thumbnail;
        private final Variant clear;
        private final String sourceMime;
        private final long originalSize;
        private final boolean animated;
        private final boolean qr;
        private final String reason;

        private Processed(Path directory, Variant display, Variant thumbnail, Variant clear,
                          String sourceMime, long originalSize, boolean animated, boolean qr, String reason) {
            this.directory = directory;
            this.display = display;
            this.thumbnail = thumbnail;
            this.clear = clear;
            this.sourceMime = sourceMime;
            this.originalSize = originalSize;
            this.animated = animated;
            this.qr = qr;
            this.reason = reason;
        }

        public Variant display() { return display; }
        public Variant thumbnail() { return thumbnail; }
        public Variant clear() { return clear; }
        public String sourceMime() { return sourceMime; }
        public long originalSize() { return originalSize; }
        public boolean animated() { return animated; }
        public boolean qr() { return qr; }
        public String reason() { return reason; }

        @Override public void close() throws IOException { deleteDirectory(directory); }
    }

    private static final long MAX_SOURCE_BYTES = 10L * 1024 * 1024;
    private static final int MAX_DIMENSION = 30_000;
    private static final long MAX_PIXELS = 80_000_000L;
    private static final long DISPLAY_TARGET = 300L * 1024;
    private static final long THUMB_TARGET = 100L * 1024;
    private static final int[] QUALITY_ATTEMPTS = { 82, 76, 70, 64 };
    private static final Semaphore PERMIT = new Semaphore(1, true);
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(45);
    private static final List<Path> SRGB_PROFILES = List.of(
            Path.of("/usr/share/color/icc/sRGB.icc"),
            Path.of("/usr/share/color/icc/colord/sRGB.icc"),
            Path.of("/usr/share/color/icc/ghostscript/srgb.icc"));

    /** Source and workRoot must be local files controlled by the application. */
    public Processed process(Path source, Path workRoot, Purpose purpose) throws IOException {
        if (source == null || workRoot == null || purpose == null || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Image source is unavailable");
        long originalSize = Files.size(source);
        if (originalSize <= 0 || originalSize > MAX_SOURCE_BYTES) throw new IOException("Image size exceeds the allowed limit");
        Files.createDirectories(workRoot);
        if (!Files.isDirectory(workRoot, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Image work directory is invalid");
        boolean acquired = false;
        Path directory = null;
        try {
            try { acquired = PERMIT.tryAcquire(20, TimeUnit.SECONDS); }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Image processing interrupted", exception);
            }
            if (!acquired) throw new IOException("Image processing is busy, please retry");
            directory = Files.createTempDirectory(workRoot.toRealPath(), ".vips-");
            Path input = directory.resolve("input.bin");
            Files.copy(source, input);
            ImageKind kind = detectKind(input);
            Header header = readHeader(directory, "input.bin");
            validateDimensions(header.width(), header.pageHeight());
            // HEIF may expose multiple still pictures as pages. Page count is
            // not an animation signal there; the ISO sequence brand is.
            boolean isoImage = kind.mime().equals("image/avif") || kind.mime().equals("image/heic") || kind.mime().equals("image/heif");
            ColorTreatment color = isoImage ? inspectIsoColor(directory) : new ColorTreatment(null, false);
            boolean animated = isAnimated(input, kind) || (!isoImage && header.pages() > 1);
            // Actually decode the first frame, including for formats preserved byte-for-byte.
            renderPng(directory, "input.bin", "decode-check.png", "1600x1600>");
            BufferedImage qrSample = ImageIO.read(directory.resolve("decode-check.png").toFile());
            if (qrSample == null) throw new IOException("Image cannot be decoded");
            String qrText = decodeQr(qrSample);
            boolean qr = purpose == Purpose.QR || qrText != null;

            int orientedWidth = header.orientation() >= 5 && header.orientation() <= 8 ? header.pageHeight() : header.width();
            int orientedHeight = header.orientation() >= 5 && header.orientation() <= 8 ? header.width() : header.pageHeight();
            if (animated) {
                if (!kind.browserSupported()) throw new IOException("Animated image format cannot be served safely");
                Path display = copy(input, directory.resolve("display" + kind.extension()));
                Path thumbnail = copy(input, directory.resolve("thumbnail" + kind.extension()));
                Path clear = copy(input, directory.resolve("clear" + kind.extension()));
                return new Processed(directory, variant(display, kind.mime(), orientedWidth, orientedHeight),
                        variant(thumbnail, kind.mime(), orientedWidth, orientedHeight),
                        variant(clear, kind.mime(), orientedWidth, orientedHeight), kind.mime(), originalSize, true, qr,
                        "animation_preserved");
            }

            boolean isLong = orientedHeight >= 2400 && (long) orientedHeight >= 3L * orientedWidth;
            // A long screenshot can mix photographs, text and small QR codes.
            // Detecting only visually dominant text would miss the latter two,
            // so preserve all long images losslessly at their display width.
            boolean longText = isLong;
            boolean lossless = qr || longText;
            Variant clear;
            if (kind.mime().equals("image/webp")) {
                Path clearFile = copy(input, directory.resolve("clear.webp"));
                clear = variant(clearFile, "image/webp", orientedWidth, orientedHeight);
            } else {
                Path clearFile = encode(directory, "input.bin", "clear.webp", MAX_DIMENSION, 94, lossless, color.importProfile());
                Header clearHeader = readHeader(directory, clearFile.getFileName().toString());
                clear = variant(clearFile, "image/webp", clearHeader.width(), clearHeader.pageHeight());
            }

            int displayWidth = purpose == Purpose.AVATAR ? 512 : purpose == Purpose.FAVICON ? 512 : 1600;
            int thumbnailWidth = purpose == Purpose.AVATAR || purpose == Purpose.FAVICON ? 192 : 480;
            if (purpose == Purpose.QR && qrText == null) {
                // An explicit QR input may contain a code too small for our
                // detector. Keep its full pixel resolution and encode losslessly.
                displayWidth = MAX_DIMENSION;
                thumbnailWidth = MAX_DIMENSION;
            }
            Variant display = encodeBest(directory, "input.bin", "display", displayWidth, DISPLAY_TARGET, lossless, color.importProfile());
            Variant thumbnail = encodeBest(directory, "input.bin", "thumbnail", thumbnailWidth, THUMB_TARGET, lossless, color.importProfile());
            boolean retainedOriginal = false;

            // The three public versions use one extension so the clear URL is
            // derivable from a display URL. For a small, already compact source,
            // retain it for all three only when neither rendition needs resizing.
            if (kind.browserSupported() && orientedWidth <= thumbnailWidth
                    && originalSize <= display.size() && originalSize <= thumbnail.size()) {
                clear = variant(copy(input, directory.resolve("clear-original" + kind.extension())), kind.mime(), orientedWidth, orientedHeight);
                display = copyVariant(clear, directory, "display-original" + kind.extension());
                thumbnail = copyVariant(clear, directory, "thumbnail-original" + kind.extension());
                retainedOriginal = true;
            }

            if (qrText != null) {
                if (!qrMatches(directory, clear, qrText)) {
                    if (!kind.browserSupported()) throw new IOException("QR code could not be preserved during conversion");
                    clear = variant(copy(input, directory.resolve("clear-qr-original" + kind.extension())), kind.mime(), orientedWidth, orientedHeight);
                    display = copyVariant(clear, directory, "display-qr-original" + kind.extension());
                    thumbnail = copyVariant(clear, directory, "thumbnail-qr-original" + kind.extension());
                }
                if (!qrMatches(directory, display, qrText)) display = copyVariant(clear, directory, "display-qr" + extension(clear.mime()));
                if (!qrMatches(directory, thumbnail, qrText)) thumbnail = copyVariant(clear, directory, "thumbnail-qr" + extension(clear.mime()));
            }
            String reason = color.unspecified() ? "color_unspecified" : qr ? "qr_lossless" : longText ? "long_image_lossless"
                    : retainedOriginal ? "size_preserved" : "compressed";
            return new Processed(directory, display, thumbnail, clear, kind.mime(), originalSize, false, qr, reason);
        } catch (IOException | RuntimeException exception) {
            if (directory != null) try { deleteDirectory(directory); } catch (IOException cleanup) { exception.addSuppressed(cleanup); }
            throw exception;
        } finally {
            if (acquired) PERMIT.release();
        }
    }

    private Variant encodeBest(Path directory, String input, String name, int width, long target, boolean lossless, Path inputProfile) throws IOException {
        Path best = null;
        long bestSize = Long.MAX_VALUE;
        int attempts = lossless ? 1 : QUALITY_ATTEMPTS.length;
        for (int i = 0; i < attempts; i++) {
            Path candidate = encode(directory, input, name + "-try-" + i + ".webp", width, QUALITY_ATTEMPTS[i], lossless, inputProfile);
            long size = Files.size(candidate);
            if (size < bestSize) {
                if (best != null) Files.deleteIfExists(best);
                best = candidate;
                bestSize = size;
            } else Files.deleteIfExists(candidate);
            if (bestSize <= target) break;
        }
        if (best == null) throw new IOException("Image encoding produced no result");
        Path output = directory.resolve(name + ".webp");
        Files.move(best, output, StandardCopyOption.REPLACE_EXISTING);
        Header header = readHeader(directory, output.getFileName().toString());
        validateDimensions(header.width(), header.pageHeight());
        return variant(output, "image/webp", header.width(), header.pageHeight());
    }

    private Path encode(Path directory, String input, String output, int width, int quality, boolean lossless, Path inputProfile) throws IOException {
        List<String> command = new ArrayList<>(List.of("vipsthumbnail", input, "--size", width + "x>"));
        Path profile = SRGB_PROFILES.stream().filter(Files::isRegularFile).findFirst().orElse(null);
        if (inputProfile != null && profile == null) throw new IOException("The sRGB output profile is unavailable");
        if (inputProfile != null) { command.add("--import-profile"); command.add(inputProfile.toString()); }
        if (profile != null) { command.add("--export-profile"); command.add(profile.toString()); }
        String options = "Q=" + quality + ",effort=4" + (lossless ? ",lossless" : "") + (profile != null ? ",strip" : "");
        command.add("-o"); command.add(output + "[" + options + "]");
        run(directory, command, PROCESS_TIMEOUT);
        Path result = directory.resolve(output);
        if (!Files.isRegularFile(result, LinkOption.NOFOLLOW_LINKS) || Files.size(result) == 0)
            throw new IOException("Image encoding produced an empty file");
        return result;
    }

    private static void renderPng(Path directory, String input, String output, String size) throws IOException {
        run(directory, List.of("vipsthumbnail", input, "--size", size, "-o", output + "[compression=3]"), PROCESS_TIMEOUT);
    }

    /** Noble libvips 8.15 discards HEIF nclx; recover only known, unambiguous SDR profiles. */
    private static ColorTreatment inspectIsoColor(Path directory) throws IOException {
        String summary = run(directory, List.of("heif-info", "input.bin"), PROCESS_TIMEOUT);
        boolean primary = false;
        String profileType = null;
        for (String raw : summary.split("\\R")) {
            String line = raw.trim();
            if (line.startsWith("image:")) primary = line.endsWith(", primary");
            else if (primary && line.startsWith("color profile:")) profileType = line.substring("color profile:".length()).trim();
        }
        if (profileType == null) throw new IOException("Image color metadata could not be inspected");
        // Unlike the common JPEG/PNG convention, an ISO image with no colour
        // declaration needs an explicit sRGB assumption communicated to the
        // uploader; libvips otherwise labels the decoded pixels as sRGB.
        if (profileType.equals("no")) return new ColorTreatment(null, true);
        if (!profileType.equals("nclx") && !profileType.equals("prof") && !profileType.equals("rICC"))
            throw new IOException("Image color profile is unsupported: " + profileType);
        String dump = run(directory, List.of("heif-info", "-d", "input.bin"), PROCESS_TIMEOUT);
        List<Nclx> profiles = new ArrayList<>();
        Integer primaries = null, transfer = null;
        boolean nclx = false;
        for (String raw : dump.split("\\R")) {
            String line = raw.substring(raw.lastIndexOf('|') + 1).trim();
            if (line.startsWith("colour_type:")) {
                if (nclx) {
                    if (primaries == null || transfer == null) throw new IOException("Incomplete image nclx color metadata");
                    profiles.add(new Nclx(primaries, transfer));
                }
                nclx = line.substring("colour_type:".length()).trim().equals("nclx");
                primaries = null; transfer = null;
            } else if (nclx && line.startsWith("colour_primaries:")) {
                primaries = parseColorNumber(line, "colour_primaries:");
            } else if (nclx && line.startsWith("transfer_characteristics:")) {
                transfer = parseColorNumber(line, "transfer_characteristics:");
            }
        }
        if (nclx) {
            if (primaries == null || transfer == null) throw new IOException("Incomplete image nclx color metadata");
            profiles.add(new Nclx(primaries, transfer));
        }
        if (!profileType.equals("nclx")) {
            if (!profiles.isEmpty()) throw new IOException("Image contains both ICC and nclx color profiles that cannot be safely resolved");
            return new ColorTreatment(null, false);
        }
        List<Nclx> unique = profiles.stream().distinct().toList();
        if (unique.size() != 1) throw new IOException("Image has ambiguous nclx color metadata");
        Nclx declared = unique.getFirst();
        if (declared.primaries() == 2 && declared.transfer() == 2) return new ColorTreatment(null, true);
        if (declared.primaries() == 1 && declared.transfer() == 13) return new ColorTreatment(null, false);
        String file = declared.primaries() == 12 && declared.transfer() == 13 ? "DisplayP3-v4.icc"
                : declared.primaries() == 1 && declared.transfer() == 1 ? "Rec709-v4.icc" : null;
        if (file == null) throw new IOException("Image nclx color profile is unsupported (primaries "
                + declared.primaries() + ", transfer " + declared.transfer() + ")");
        Path path = directory.resolve(file);
        try (InputStream source = VipsImageProcessor.class.getResourceAsStream("/media-profiles/" + file)) {
            if (source == null) throw new IOException("Image color profile is unavailable");
            Files.copy(source, path);
        }
        return new ColorTreatment(path, false);
    }

    private static int parseColorNumber(String line, String prefix) throws IOException {
        try { return Integer.parseInt(line.substring(prefix.length()).trim()); }
        catch (NumberFormatException exception) { throw new IOException("Invalid image color metadata", exception); }
    }

    private static boolean qrMatches(Path directory, Variant variant, String expected) throws IOException {
        String sample = "verify-" + variant.path().getFileName() + ".png";
        renderPng(directory, variant.path().getFileName().toString(), sample, "1600x1600>");
        try {
            BufferedImage image = ImageIO.read(directory.resolve(sample).toFile());
            return image != null && expected.equals(decodeQr(image));
        } finally { Files.deleteIfExists(directory.resolve(sample)); }
    }

    private static String decodeQr(BufferedImage image) {
        int width = image.getWidth(), height = image.getHeight();
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        // Transparent pixels are rendered over the normal white document
        // background rather than interpreted as opaque black by ZXing.
        if (image.getColorModel().hasAlpha()) {
            for (int i = 0; i < pixels.length; i++) {
                int pixel = pixels[i], alpha = pixel >>> 24;
                int red = ((pixel >>> 16 & 255) * alpha + 255 * (255 - alpha)) / 255;
                int green = ((pixel >>> 8 & 255) * alpha + 255 * (255 - alpha)) / 255;
                int blue = ((pixel & 255) * alpha + 255 * (255 - alpha)) / 255;
                pixels[i] = 0xff000000 | red << 16 | green << 8 | blue;
            }
        }
        var hints = new EnumMap<DecodeHintType, Object>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.QR_CODE));
        try {
            return new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(width, height, pixels))), hints).getText();
        } catch (NotFoundException ignored) { return null; }
    }

    private static Header readHeader(Path directory, String input) throws IOException {
        String output = run(directory, List.of("vipsheader", "-a", input), PROCESS_TIMEOUT);
        int width = 0, height = 0, pageHeight = 0, pages = 1, orientation = 1;
        for (String line : output.split("\\R")) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim().split("\\s+", 2)[0];
            try {
                switch (key) {
                    case "width" -> width = Integer.parseInt(value);
                    case "height" -> height = Integer.parseInt(value);
                    case "page-height" -> pageHeight = Integer.parseInt(value);
                    case "n-pages" -> pages = Integer.parseInt(value);
                    case "orientation" -> orientation = Integer.parseInt(value);
                    default -> { }
                }
            } catch (NumberFormatException ignored) { /* Other metadata is not numeric. */ }
        }
        if (pageHeight <= 0) pageHeight = height;
        if (width <= 0 || height <= 0 || pageHeight <= 0 || pages <= 0) throw new IOException("Image dimensions are invalid");
        return new Header(width, pageHeight, pages, orientation);
    }

    private static String run(Path directory, List<String> command, Duration timeout) throws IOException {
        Path log = Files.createTempFile(directory, "command-", ".log");
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().put("LC_ALL", "C");
            builder.environment().put("VIPS_CONCURRENCY", "2");
            builder.environment().put("VIPS_MAX_MEM", "134217728");
            process = builder.start();
            boolean finished;
            try { finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS); }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Image processing interrupted", exception);
            }
            if (!finished) throw new IOException("Image processing timed out");
            String output;
            if (Files.size(log) > 1024 * 1024) throw new IOException("Image metadata exceeds the inspection limit");
            try (InputStream stream = Files.newInputStream(log)) { output = new String(stream.readAllBytes(), StandardCharsets.UTF_8); }
            if (process.exitValue() != 0) {
                String detail = output.replaceAll("[\\p{Cntrl}]+", " ").trim();
                if (detail.length() > 300) detail = detail.substring(0, 300);
                throw new IOException("Image processing failed (exit " + process.exitValue() + ")"
                        + (detail.isEmpty() ? "" : ": " + detail));
            }
            return output;
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                try { process.waitFor(5, TimeUnit.SECONDS); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            }
            Files.deleteIfExists(log);
        }
    }

    private static ImageKind detectKind(Path input) throws IOException {
        byte[] bytes;
        try (InputStream stream = Files.newInputStream(input)) { bytes = stream.readNBytes(128); }
        if (bytes.length >= 3 && (bytes[0] & 255) == 0xff && (bytes[1] & 255) == 0xd8 && (bytes[2] & 255) == 0xff)
            return new ImageKind("image/jpeg", ".jpg", true);
        if (bytes.length >= 8 && bytes[0] == (byte) 0x89 && ascii(bytes, 1, 3).equals("PNG"))
            return new ImageKind("image/png", ".png", true);
        if (bytes.length >= 12 && ascii(bytes, 0, 4).equals("RIFF") && ascii(bytes, 8, 4).equals("WEBP"))
            return new ImageKind("image/webp", ".webp", true);
        if (bytes.length >= 6 && (ascii(bytes, 0, 6).equals("GIF87a") || ascii(bytes, 0, 6).equals("GIF89a")))
            return new ImageKind("image/gif", ".gif", true);
        if (bytes.length >= 2 && ascii(bytes, 0, 2).equals("BM")) return new ImageKind("image/bmp", ".bmp", false);
        if (bytes.length >= 16 && ascii(bytes, 4, 4).equals("ftyp")) {
            String brands = ascii(bytes, 8, bytes.length - 8);
            if (brands.contains("avif") || brands.contains("avis")) return new ImageKind("image/avif", ".avif", true);
            if (brands.contains("heic") || brands.contains("heix") || brands.contains("hevc") || brands.contains("hevx"))
                return new ImageKind("image/heic", ".heic", false);
            if (brands.contains("mif1") || brands.contains("msf1")) return new ImageKind("image/heif", ".heif", false);
        }
        throw new IOException("Unsupported image format");
    }

    private static boolean isAnimated(Path input, ImageKind kind) throws IOException {
        if (kind.mime().equals("image/avif") || kind.mime().equals("image/heic") || kind.mime().equals("image/heif")) {
            try (InputStream stream = Files.newInputStream(input)) {
                byte[] prefix = stream.readNBytes(12);
                if (prefix.length >= 12) {
                    String primaryBrand = ascii(prefix, 8, 4);
                    if (primaryBrand.equals("avis") || primaryBrand.equals("msf1")
                            || primaryBrand.equals("hevc") || primaryBrand.equals("hevx")) return true;
                }
            }
        }
        if (kind.mime().equals("image/gif")) return animatedGif(input);
        if (kind.mime().equals("image/png")) {
            try (var stream = new DataInputStream(Files.newInputStream(input))) {
                stream.skipNBytes(8);
                long remaining = Files.size(input) - 8;
                boolean animated = false;
                while (remaining >= 12) {
                    long length = Integer.toUnsignedLong(stream.readInt());
                    byte[] typeBytes = stream.readNBytes(4);
                    if (typeBytes.length != 4 || length > remaining - 12) throw new IOException("Invalid PNG structure");
                    String type = ascii(typeBytes, 0, 4);
                    if ("acTL".equals(type)) animated = true;
                    stream.skipNBytes(length + 4);
                    remaining -= length + 12;
                    // Some camera and messaging apps append private metadata after IEND.
                    // libvips validates and decodes the image itself; trailing bytes do not
                    // change APNG semantics because acTL must precede the image end chunk.
                    if ("IEND".equals(type)) return animated;
                }
                throw new IOException("Invalid PNG structure");
            }
        }
        if (kind.mime().equals("image/webp")) {
            try (var stream = new DataInputStream(Files.newInputStream(input))) {
                stream.skipNBytes(12);
                long remaining = Files.size(input) - 12;
                boolean animated = false;
                while (remaining >= 8) {
                    byte[] typeBytes = stream.readNBytes(4);
                    byte[] lengthBytes = stream.readNBytes(4);
                    if (typeBytes.length != 4 || lengthBytes.length != 4) throw new IOException("Invalid WebP structure");
                    long length = ((long) lengthBytes[0] & 255) | (((long) lengthBytes[1] & 255) << 8)
                            | (((long) lengthBytes[2] & 255) << 16) | (((long) lengthBytes[3] & 255) << 24);
                    long padded = length + (length & 1);
                    if (padded > remaining - 8) throw new IOException("Invalid WebP structure");
                    String type = ascii(typeBytes, 0, 4);
                    if ("ANIM".equals(type) || "ANMF".equals(type)) animated = true;
                    stream.skipNBytes(padded);
                    remaining -= 8 + padded;
                }
                if (remaining != 0) throw new IOException("Invalid WebP structure");
                return animated;
            }
        }
        return false;
    }

    private static boolean animatedGif(Path input) throws IOException {
        try (var stream = new DataInputStream(Files.newInputStream(input))) {
            stream.skipNBytes(6);
            byte[] screen = stream.readNBytes(7);
            if (screen.length != 7) throw new IOException("Invalid GIF structure");
            int packed = screen[4] & 255;
            if ((packed & 0x80) != 0) stream.skipNBytes(3L << ((packed & 7) + 1));
            int frames = 0;
            while (true) {
                int marker = stream.read();
                if (marker == 0x3b) {
                    if (frames == 0 || stream.read() != -1) throw new IOException("Invalid GIF structure");
                    return frames > 1;
                }
                if (marker == 0x21) {
                    if (stream.read() < 0) throw new IOException("Invalid GIF structure");
                    skipGifBlocks(stream);
                } else if (marker == 0x2c) {
                    byte[] descriptor = stream.readNBytes(9);
                    if (descriptor.length != 9) throw new IOException("Invalid GIF structure");
                    int localPacked = descriptor[8] & 255;
                    if ((localPacked & 0x80) != 0) stream.skipNBytes(3L << ((localPacked & 7) + 1));
                    if (stream.read() < 0) throw new IOException("Invalid GIF structure");
                    skipGifBlocks(stream);
                    frames++;
                } else throw new IOException("Invalid GIF structure");
            }
        }
    }

    private static void skipGifBlocks(DataInputStream stream) throws IOException {
        while (true) {
            int size = stream.read();
            if (size < 0) throw new IOException("Invalid GIF structure");
            if (size == 0) return;
            stream.skipNBytes(size);
        }
    }

    private static void validateDimensions(int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION || (long) width * height > MAX_PIXELS)
            throw new IOException("Image dimensions exceed the allowed limit");
    }

    private static Variant variant(Path path, String mime, int width, int height) throws IOException {
        return new Variant(path, mime, width, height, Files.size(path));
    }
    private static Path copy(Path source, Path destination) throws IOException { return Files.copy(source, destination); }
    private static Variant copyVariant(Variant source, Path directory, String name) throws IOException {
        return variant(copy(source.path(), directory.resolve(name)), source.mime(), source.width(), source.height());
    }
    private static String extension(String mime) {
        return switch (mime) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            case "image/avif" -> ".avif";
            default -> ".bin";
        };
    }
    private static String ascii(byte[] bytes, int offset, int length) { return new String(bytes, offset, length, StandardCharsets.US_ASCII); }
    private static void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (var entries = Files.walk(directory)) {
            for (Path path : entries.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
    private record ImageKind(String mime, String extension, boolean browserSupported) { }
    private record Header(int width, int pageHeight, int pages, int orientation) { }
    private record ColorTreatment(Path importProfile, boolean unspecified) { }
    private record Nclx(int primaries, int transfer) { }
}
