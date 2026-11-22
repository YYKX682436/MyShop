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
    private static final long MAX_OPTIMIZE_PIXELS = 8_000_000;
    private final UploadStorage storage;
    private final MediaFileGuard guard;
    private final ImageProcessor processor;
    private final String prefix;
    private final Semaphore optimizePermit = new Semaphore(1, true);
    @org.springframework.beans.factory.annotation.Value("${app.public-site-url:http://localhost:3000}")
    private String publicSiteUrl = "http://localhost:3000";

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
            if (!storage.isSafePublicFile(filename))
                throw new BusinessException(ErrorCode.NOT_FOUND, "资源不存在", HttpStatus.NOT_FOUND);
            // Verify guard coverage and reportable references before changing anything.
            inspection(connection, filename);
            String oldUrl = prefix + "/" + filename;
            String absoluteUrl = publicSiteUrl.replaceAll("/+$", "") + oldUrl;
            clearProductCovers(connection, filename);
            try (var statement = connection.prepareStatement(
                    "UPDATE site_configs SET config_value = '' WHERE config_key = 'favicon_url' AND config_value IN (?, ?)")) {
                statement.setString(1, oldUrl);
                statement.setString(2, absoluteUrl);
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("UPDATE subsites SET favicon_url = NULL WHERE favicon_url IN (?, ?)")) {
                statement.setString(1, oldUrl);
                statement.setString(2, absoluteUrl);
                statement.executeUpdate();
            }
            try (var query = connection.prepareStatement("INSERT INTO media_deleted_files(filename) VALUES (?) ON CONFLICT DO NOTHING")) {
                query.setString(1, filename.toLowerCase(Locale.ROOT));
                query.executeUpdate();
            }
            // Commit the tombstone and field updates before deleting the file.
            // If cleanup fails, the guarded file remains listed and a retry can
            // finish deletion without allowing stale forms to recreate a reference.
            connection.commit();
            try {
                storage.forgetMediaNameExcept(filename.substring(0, filename.lastIndexOf('.')).toLowerCase(Locale.ROOT), filename);
            } catch (IOException exception) {
                throw new BusinessException(ErrorCode.SERVER_ERROR, "图片清理未完成，请刷新后重试", HttpStatus.CONFLICT);
            }
            if (!storage.isSafePublicFile(filename) || !storage.deleteManagedFile(filename))
                throw new BusinessException(ErrorCode.SERVER_ERROR, "图片清理未完成，请刷新后重试", HttpStatus.CONFLICT);
            return null;
        });
    }

    private void clearProductCovers(Connection connection, String filename) throws Exception {
        for (ProductCover cover : findProductCovers(connection, filename)) {
            try (var update = connection.prepareStatement("UPDATE products SET cover_url = NULL WHERE id = ? AND cover_url = ?")) {
                update.setObject(1, cover.id());
                update.setString(2, cover.url());
                if (update.executeUpdate() != 1)
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "商品封面已变化，请重试", HttpStatus.CONFLICT);
            }
        }
    }

    private List<ProductCover> findProductCovers(Connection connection, String filename) throws Exception {
        String target = prefix + "/" + filename;
        var origin = java.net.URI.create(publicSiteUrl);
        List<ProductCover> covers = new ArrayList<>();
        try (var query = connection.prepareStatement("""
                SELECT id, title, cover_url FROM products
                WHERE cover_url IS NOT NULL AND
                  (public.myshop_media_normalize(cover_url) IS NULL
                    OR position(? IN public.myshop_media_normalize(cover_url)) > 0)
                FOR UPDATE NOWAIT
                """)) {
            query.setString(1, filename.toLowerCase(Locale.ROOT));
            try (var result = query.executeQuery()) {
                while (result.next()) {
                    String value = result.getString(3);
                    final java.net.URI uri;
                    try { uri = java.net.URI.create(value); }
                    catch (IllegalArgumentException exception) {
                        throw new BusinessException(ErrorCode.BAD_REQUEST, "商品封面引用格式不明确，未执行删除", HttpStatus.CONFLICT);
                    }
                    boolean local = !uri.isAbsolute() && uri.getRawAuthority() == null;
                    if (uri.isAbsolute()) local = ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                            && uri.getUserInfo() == null && origin.getHost() != null && origin.getHost().equalsIgnoreCase(uri.getHost())
                            && uri.getPort() == origin.getPort();
                    if (!local) continue;
                    if (!target.equals(uri.getPath()))
                        throw new BusinessException(ErrorCode.BAD_REQUEST, "商品封面引用格式不明确，未执行删除", HttpStatus.CONFLICT);
                    covers.add(new ProductCover(result.getObject(1), result.getString(2), value));
                }
            }
        }
        return covers;
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
        String extension = filename.substring(filename.lastIndexOf('.')).toLowerCase(Locale.ROOT);
        if (!eligible(filename, original, dimensions))
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅支持尺寸合适、无动画或复杂色彩配置的 PNG/JPEG");
        final ImageProcessor.ProcessedImage processed;
        try { processed = processor.processForStorage(original, extension); }
        catch (IOException exception) { throw new BusinessException(ErrorCode.BAD_REQUEST, "图片无法安全优化"); }
        byte[] optimized = processed.bytes();
        if (optimized.length >= original.length
                || (processed.extension().equals(".png") && !samePixels(original, optimized))
                || (!extension.equals(".png") && !ImageProcessor.acceptableJpeg(original, optimized)))
            throw new BusinessException(ErrorCode.BAD_REQUEST, "图片没有符合质量要求的压缩收益，已跳过");

        return guard.exclusive(true, connection -> {
            if (!Arrays.equals(original, readImage(filename)))
                throw new BusinessException(ErrorCode.BAD_REQUEST, "图片已变化，已跳过", HttpStatus.CONFLICT);
            references(connection, filename);
            List<ProductCover> covers = findProductCovers(connection, filename);
            String newFilename = UploadStorage.identifier(optimized) + processed.extension();
            storage.publish(newFilename, optimized, true);
            MediaFileGuard.restorePublishedFile(connection, newFilename);
            try {
                String name = storage.mediaName(filename.substring(0, filename.lastIndexOf('.')), filename);
                if (!processed.extension().equalsIgnoreCase(filename.substring(filename.lastIndexOf('.'))) && name.lastIndexOf('.') >= 0)
                    name = name.substring(0, name.lastIndexOf('.')) + processed.extension();
                storage.rememberMediaName(newFilename.substring(0, 64), name);
            } catch (IOException ignored) { /* Display name is optional. */ }
            List<ChangedProduct> products = new ArrayList<>();
            for (ProductCover cover : covers) {
                try (var statement = connection.prepareStatement("UPDATE products SET cover_url = ? WHERE id = ? AND cover_url = ?")) {
                    statement.setString(1, prefix + "/" + newFilename);
                    statement.setObject(2, cover.id());
                    statement.setString(3, cover.url());
                    if (statement.executeUpdate() != 1)
                        throw new BusinessException(ErrorCode.BAD_REQUEST, "商品引用已变化，已跳过", HttpStatus.CONFLICT);
                    products.add(new ChangedProduct(cover.id().toString(), cover.title()));
                }
            }
            return new Optimization(prefix + "/" + newFilename, original.length, optimized.length, products.size(),
                    products, references(connection, filename));
        });
    }

    private Inspection inspection(Connection connection, String filename) throws Exception {
        if (!storage.isSafePublicFile(filename)) throw new BusinessException(ErrorCode.NOT_FOUND, "资源不存在", HttpStatus.NOT_FOUND);
        long size = Files.size(storage.resolve(filename));
        Dimensions dimensions = null;
        boolean eligible = false;
        if (size <= MAX_BYTES) {
            byte[] bytes = readImage(filename);
            dimensions = dimensions(bytes);
            eligible = eligible(filename, bytes, dimensions);
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

    private static boolean eligible(String filename, byte[] bytes, Dimensions dimensions) {
        if (eligiblePng(filename, bytes, dimensions)) return true;
        String lower = filename.toLowerCase(Locale.ROOT);
        return (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) && dimensions != null
                && dimensions.width() > 0 && dimensions.height() > 0
                && dimensions.width() <= ImageProcessor.MAX_DIMENSION && dimensions.height() <= ImageProcessor.MAX_DIMENSION
                && (long) dimensions.width() * dimensions.height() <= MAX_OPTIMIZE_PIXELS
                && ImageProcessor.eligibleOldJpeg(bytes, dimensions.width(), dimensions.height());
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
    private record ProductCover(Object id, String title, String url) { }
    public record Reference(String table, String source, long count) { }
    public record ChangedProduct(String id, String title) { }
    public record Inspection(long size, String format, Integer width, Integer height, boolean optimizable, List<Reference> references) { }
    public record Optimization(String url, long originalSize, long optimizedSize, int replacedCovers,
                               List<ChangedProduct> changedProducts, List<Reference> retainedReferences) { }
}
