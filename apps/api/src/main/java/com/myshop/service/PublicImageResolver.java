package com.myshop.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * A historical snapshot retains its URL, but a newly rendered order should
 * not request a deleted managed file from an upstream proxy or browser cache.
 */
@Service
public class PublicImageResolver {
    private final UploadStorage storage;
    private final String prefix;
    private final String origin;

    public PublicImageResolver(UploadStorage storage,
            @Value("${upload.url-prefix:/api/uploads}") String prefix,
            @Value("${app.public-site-url:http://localhost:3000}") String origin) {
        this.storage = storage;
        this.prefix = prefix.replaceAll("/+$", "") + "/";
        this.origin = origin.replaceAll("/+$", "");
    }

    public String visible(String url) {
        if (url == null) return null;
        final java.net.URI uri;
        final java.net.URI site;
        try { uri = java.net.URI.create(url); site = java.net.URI.create(origin); }
        catch (IllegalArgumentException ignored) { return url; }
        if (uri.isAbsolute() && (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getUserInfo() != null || site.getHost() == null || !site.getHost().equalsIgnoreCase(uri.getHost())
                || uri.getPort() != site.getPort())) return url;
        if (!uri.isAbsolute() && uri.getRawAuthority() != null) return url;
        String path = uri.getPath();
        if (path == null) return url;
        if (!path.startsWith(prefix)) return url;
        String name = path.substring(prefix.length());
        if (!name.matches("(?i)(?:[0-9a-f]{64}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.(?:jpg|jpeg|png|gif|webp|bmp)"))
            return url;
        return storage.isSafePublicFile(name) ? url : null;
    }
}
