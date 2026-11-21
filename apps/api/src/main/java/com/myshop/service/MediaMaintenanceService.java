package com.myshop.service;

import com.myshop.constant.ErrorCode;
import com.myshop.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

/** Conservative, administrator-initiated maintenance of public root images. */
@Service
public class MediaMaintenanceService {
    private static final Pattern FILENAME = Pattern.compile("(?i)(?:[0-9a-f]{64}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.(?:jpg|jpeg|png|gif|webp|bmp)");
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final long MAX_OPTIMIZE_PIXELS = 4_000_000;
    private final UploadStorage storage;
    private final MediaFileGuard guard;
    private final ImageProcessor processor;
    private final String prefix;
    private final Semaphore optimizePermit = new Semaphore(1, true);

    public MediaMaintenanceService(UploadStorage storage, MediaFileGuard guard, ImageProcessor processor,
                                   @org.springframework.beans.factory.annotation.Value("${upload.url-prefix:/api/uploads}") String prefix) {
        this.storage = storage;
        this.guard = guard;
        this.processor = processor;
        this.prefix = prefix.replaceAll("/+$", "");
    }

    public Inspection inspect(String filename) {
        validate(filename);
        return guard.exclusive(true, connection -> inspection(connection, filename));
    }

    public void delete(String filename) {
        validate(filename);
        guard.exclusive(true, connection -> {
            Inspection state = inspection(connection, filename);
            if (!state.references().isEmpty())
                throw new BusinessException(ErrorCode.BAD_REQUEST, "图片正在被业务数据引用，不能删除", HttpStatus.CONFLICT);
            try (var query = connection.prepareStatement("INSERT INTO media_deleted_files(filename) VALUES (?) ON CONFLICT DO NOTHING")) {
                query.setString(1, filename.toLowerCase(Locale.ROOT));
                query.executeUpdate();
            }
            // Commit the guard before removing the file. A crash in between can
            // leave an unused file, but cannot allow a missing image reference.
            connection.commit();
            if (!storage.isSafePublicFile(filename) || !storage.deleteManagedFile(filename)) {
                if (storage.isSafePublicFile(filename)) {
                    MediaFileGuard.restorePublishedFile(connection, filename);
                    connection.commit();
                }
                throw new BusinessException(ErrorCode.SERVER_ERROR, "图片删除失败，请刷新后重试", HttpStatus.CONFLICT);
            }
            try {
                storage.forgetMediaName(filename.substring(0, filename.lastIndexOf('.')).toLowerCase(Locale.ROOT));
            } catch (IOException exception) {
                throw new BusinessException(ErrorCode.SERVER_ERROR, "图片已删除，但名称清理失败，请刷新资源列表");
            }
            return null;
        });
    }

    public Optimization optimize(String filename) {
        if (!optimizePermit.tryAcquire())
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "已有图片正在优化，请稍后重试", HttpStatus.TOO_MANY_REQUESTS);
        try { return optimizeOnce(filename); }
        finally { optimizePermit.release(); }
    }

    private Optimization optimizeOnce(String filename) {
        validate(filename);
        // Read and compress before taking the DB lock. Original bytes are
        // checked again under the lock before any reference can change.
        byte[] original = readImage(filename);
        Dimensions dimensions = dimensions(original);
        if (!eligiblePng(filename, original, dimensions))
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅支持无动画、无颜色配置且尺寸不超过 400 万像素的 PNG 无损优化");
        final byte[] optimized;
        try { optimized = processor.process(original, ".png"); }
        catch (IOException exception) { throw new BusinessException(ErrorCode.BAD_REQUEST, "图片无法安全优化"); }
        if (optimized.length >= original.length || !samePixels(original, optimized))
            throw new BusinessException(ErrorCode.BAD_REQUEST, "图片没有可验证的无损压缩收益，已跳过");

        return guard.exclusive(true, connection -> {
            if (!Arrays.equals(original, readImage(filename)))
                throw new BusinessException(ErrorCode.BAD_REQUEST, "图片已变化，已跳过", HttpStatus.CONFLICT);
            List<Reference> references = references(connection, filename);
            if (references.stream().anyMatch(reference -> !reference.table().equals("products")))
                throw new BusinessException(ErrorCode.BAD_REQUEST, "图片被商品封面以外的数据引用，已跳过", HttpStatus.CONFLICT);
            String oldUrl = prefix + "/" + filename;
            long covers;
            try (var statement = connection.prepareStatement("SELECT id FROM products WHERE cover_url = ? FOR UPDATE NOWAIT")) {
                statement.setString(1, oldUrl);
                try (ResultSet result = statement.executeQuery()) {
                    covers = 0;
                    while (result.next()) covers++;
                }
            }
            long rows = references.stream().mapToLong(Reference::count).sum();
            if (covers != rows || productHasOtherReference(connection, filename))
                throw new BusinessException(ErrorCode.BAD_REQUEST, "图片引用不明确，已跳过", HttpStatus.CONFLICT);

            String newFilename = UploadStorage.identifier(optimized) + ".png";
            storage.publish(newFilename, optimized, true);
            MediaFileGuard.restorePublishedFile(connection, newFilename);
            try {
                storage.rememberMediaName(newFilename.substring(0, 64),
                        storage.mediaName(filename.substring(0, filename.lastIndexOf('.')), filename));
            } catch (IOException ignored) { /* Display name is optional. */ }
            int changed;
            try (var statement = connection.prepareStatement("UPDATE products SET cover_url = ? WHERE cover_url = ?")) {
                statement.setString(1, prefix + "/" + newFilename);
                statement.setString(2, oldUrl);
                changed = statement.executeUpdate();
            }
            if (changed != covers) throw new BusinessException(ErrorCode.BAD_REQUEST, "商品引用已变化，已跳过", HttpStatus.CONFLICT);
            return new Optimization(prefix + "/" + newFilename, original.length, optimized.length, changed);
        });
    }

    private Inspection inspection(Connection connection, String filename) throws Exception {
        if (!storage.isSafePublicFile(filename)) throw new BusinessException(ErrorCode.NOT_FOUND, "资源不存在", HttpStatus.NOT_FOUND);
        long size = Files.size(storage.resolve(filename));
        Dimensions dimensions = null;
        boolean eligible = false;
        if (filename.toLowerCase(Locale.ROOT).endsWith(".png") && size <= MAX_BYTES) {
            byte[] bytes = readImage(filename);
            dimensions = dimensions(bytes);
            eligible = eligiblePng(filename, bytes, dimensions);
        }
        return new Inspection(size, filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT),
                dimensions == null ? null : dimensions.width(), dimensions == null ? null : dimensions.height(),
                eligible, references(connection, filename));
    }

    private List<Reference> references(Connection connection, String filename) throws Exception {
        // Deletion briefly excludes writes. Bound both each query and the
        // whole scan; a large or stalled database must fail closed promptly.
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        try (var statement = connection.createStatement()) { statement.execute("SET LOCAL statement_timeout = '2s'"); }
        List<String> tables = new ArrayList<>();
        try (var statement = connection.createStatement(); var result = statement.executeQuery("""
                SELECT c.relname, EXISTS (
                    SELECT 1 FROM pg_trigger t
                    WHERE t.tgrelid = c.oid AND t.tgname = 'myshop_media_guard'
                      AND NOT t.tgisinternal AND t.tgenabled IN ('O', 'A')
                      AND (t.tgtype & 23) = 23
                      AND t.tgfoid = to_regprocedure('public.myshop_media_guard_write()')
                )
                FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')
                  AND c.relname NOT IN ('flyway_schema_history', 'media_deleted_files')
                ORDER BY c.relname
                """)) {
            while (result.next()) {
                String name = result.getString(1);
                if (!result.getBoolean(2) || !name.matches("[a-z0-9_]+"))
                    throw new BusinessException(ErrorCode.SERVER_ERROR, "资源引用保护不完整，已停止操作");
                tables.add(name);
            }
        }
        List<Reference> references = new ArrayList<>();
        for (String table : tables) {
            if (System.nanoTime() >= deadline)
                throw new BusinessException(ErrorCode.SERVER_ERROR, "资源引用检查超时，未执行操作", HttpStatus.CONFLICT);
            // CSS escapes can encode even the name of url(). Do not guess.
            String ambiguousCss = table.equals("site_configs")
                    ? " OR (t.config_key = 'custom_css' AND position(chr(92) IN coalesce(t.config_value, '')) > 0)"
                    : "";
            String sql = "SELECT count(*) FROM public.\"" + table + "\" t CROSS JOIN LATERAL "
                    + "(SELECT public.myshop_media_normalize(to_jsonb(t)::text) AS value) n "
                    + "WHERE n.value IS NULL OR position(? IN n.value) > 0" + ambiguousCss;
            try (var statement = connection.prepareStatement(sql)) {
                statement.setString(1, filename.toLowerCase(Locale.ROOT));
                try (var result = statement.executeQuery()) {
                    result.next(); long count = result.getLong(1);
                    if (count > 0) references.add(new Reference(table, label(table), count));
                }
            }
        }
        return references;
    }

    private boolean productHasOtherReference(Connection connection, String filename) throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT EXISTS (SELECT 1 FROM products p
                WHERE public.myshop_media_normalize((to_jsonb(p) - 'cover_url')::text) IS NULL
                   OR position(? IN public.myshop_media_normalize((to_jsonb(p) - 'cover_url')::text)) > 0)
                """)) {
            statement.setString(1, filename.toLowerCase(Locale.ROOT));
            try (var result = statement.executeQuery()) { result.next(); return result.getBoolean(1); }
        }
    }

    private byte[] readImage(String filename) {
        if (!storage.isSafePublicFile(filename)) throw new BusinessException(ErrorCode.NOT_FOUND, "资源不存在", HttpStatus.NOT_FOUND);
        try (var input = Files.newInputStream(storage.resolve(filename), LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new BusinessException(ErrorCode.BAD_REQUEST, "图片超过 10 MB，已跳过");
            return bytes;
        } catch (IOException exception) { throw new BusinessException(ErrorCode.SERVER_ERROR, "图片读取失败"); }
    }

    private static Dimensions dimensions(byte[] bytes) {
        try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try { reader.setInput(stream, true, true); return new Dimensions(reader.getWidth(0), reader.getHeight(0)); }
            finally { reader.dispose(); }
        } catch (IOException | RuntimeException exception) { return null; }
    }

    private static boolean eligiblePng(String filename, byte[] bytes, Dimensions dimensions) {
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".png") || dimensions == null
                || dimensions.width() <= 0 || dimensions.height() <= 0
                || dimensions.width() > ImageProcessor.OUTPUT_DIMENSION || dimensions.height() > ImageProcessor.OUTPUT_DIMENSION
                || (long) dimensions.width() * dimensions.height() > MAX_OPTIMIZE_PIXELS || bytes.length < 20) return false;
        // Do not discard animation, embedded color profiles, or orientation.
        for (int i = 8; i + 12 <= bytes.length;) {
            long length = ((long) (bytes[i] & 255) << 24) | ((long) (bytes[i+1] & 255) << 16)
                    | ((long) (bytes[i+2] & 255) << 8) | (bytes[i+3] & 255L);
            if (length > bytes.length - i - 12L) return false;
            String type = new String(bytes, i + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (List.of("acTL", "iCCP", "gAMA", "cHRM", "sRGB", "eXIf").contains(type)) return false;
            i += (int) length + 12;
            if (type.equals("IEND")) return i == bytes.length;
        }
        return false;
    }

    private static boolean samePixels(byte[] before, byte[] after) {
        try {
            BufferedImage a = ImageIO.read(new ByteArrayInputStream(before));
            BufferedImage b = ImageIO.read(new ByteArrayInputStream(after));
            if (a == null || b == null || a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return false;
            for (int y = 0; y < a.getHeight(); y++) {
                if (!Arrays.equals(a.getRGB(0, y, a.getWidth(), 1, null, 0, a.getWidth()),
                        b.getRGB(0, y, b.getWidth(), 1, null, 0, b.getWidth()))) return false;
            }
            return true;
        } catch (IOException | RuntimeException exception) { return false; }
    }

    private static String label(String table) {
        return switch (table) {
            case "products" -> "商品";
            case "order_items" -> "订单商品快照";
            case "site_configs" -> "网站设置、公告或二维码";
            case "subsites" -> "分站设置";
            case "supplier_product_mappings" -> "货源商品资料";
            case "users" -> "用户资料";
            default -> "其他业务数据（" + table + "）";
        };
    }

    private static void validate(String filename) {
        if (filename == null || !FILENAME.matcher(filename).matches())
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的资源名称");
    }

    private record Dimensions(int width, int height) { }
    public record Reference(String table, String source, long count) { }
    public record Inspection(long size, String format, Integer width, Integer height, boolean optimizable, List<Reference> references) { }
    public record Optimization(String url, long originalSize, long optimizedSize, int replacedCovers) { }
}
