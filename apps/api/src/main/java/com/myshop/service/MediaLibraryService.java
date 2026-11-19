package com.myshop.service;

import com.myshop.constant.ErrorCode;
import com.myshop.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class MediaLibraryService {
    private static final Pattern MANAGED_IMAGE = Pattern.compile("(?i)(?:[0-9a-f]{64}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.(?:jpg|jpeg|png|gif|webp|bmp)");
    private final UploadStorage storage;
    private final String urlPrefix;

    public MediaLibraryService(UploadStorage storage, @Value("${upload.url-prefix:/api/uploads}") String prefix) {
        this.storage = storage;
        String normalized = prefix == null ? "" : prefix.trim().replaceAll("/+$", "");
        this.urlPrefix = normalized.isEmpty() ? "/api/uploads" : normalized;
    }

    public MediaPage list(int page, int pageSize, String query, String order, boolean qrOnly) {
        if (page < 1 || page > 100_000 || pageSize < 1 || pageSize > 100 || query.length() > 120
                || (!order.equals("asc") && !order.equals("desc"))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "资源查询参数无效");
        }
        String keyword = query.trim().toLowerCase(Locale.ROOT);
        try (var files = Files.list(storage.directory())) {
            Comparator<MediaItem> comparator = Comparator.comparingLong(MediaItem::uploadedAt).thenComparing(MediaItem::url);
            if (order.equals("desc")) comparator = comparator.reversed();
            List<MediaItem> matches = files.map(this::item)
                    .filter(java.util.Objects::nonNull)
                    .filter(item -> !qrOnly || List.of("jpg", "jpeg", "png", "webp").contains(item.format()))
                    .filter(item -> item.name().toLowerCase(Locale.ROOT).contains(keyword)
                            || item.url().toLowerCase(Locale.ROOT).contains(keyword))
                    .sorted(comparator).toList();
            int start = (int) Math.min((long) (page - 1) * pageSize, matches.size());
            int end = Math.min(start + pageSize, matches.size());
            return new MediaPage(matches.subList(start, end), matches.size(), page, pageSize);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.SERVER_ERROR, "资源加载失败");
        }
    }

    private MediaItem item(Path file) {
        String filename = file.getFileName().toString();
        if (!MANAGED_IMAGE.matcher(filename).matches() || !storage.isSafePublicFile(filename)) return null;
        try {
            int dot = filename.lastIndexOf('.');
            String name = storage.mediaName(filename.substring(0, dot), filename);
            return new MediaItem(name, urlPrefix + "/" + filename, Files.size(file),
                    filename.substring(dot + 1).toLowerCase(Locale.ROOT), Files.getLastModifiedTime(file).toMillis());
        } catch (IOException | SecurityException ignored) { return null; }
    }

    public record MediaItem(String name, String url, long size, String format, long uploadedAt) { }
    public record MediaPage(List<MediaItem> items, int total, int page, int pageSize) { }
}
