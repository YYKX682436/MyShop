package com.myshop.service;

import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.UUID;

/** Extract only explicit media destinations in the fields MyShop owns. */
@Component
public class ManagedMediaReferences {
    private static final Parser PARSER = Parser.builder()
            .extensions(List.of(TablesExtension.create(), StrikethroughExtension.create(), AutolinkExtension.create()))
            .build();
    private final JdbcTemplate jdbc;
    private final String prefix;
    private final URI site;

    public ManagedMediaReferences(JdbcTemplate jdbc,
            @Value("${upload.url-prefix:/api/uploads}") String prefix,
            @Value("${app.public-site-url:http://localhost:3000}") String publicSite) {
        this.jdbc = jdbc;
        this.prefix = prefix.replaceAll("/+$", "") + "/";
        this.site = URI.create(publicSite);
    }

    public Set<String> markdown(String value) {
        return markdownCounts(value).keySet();
    }

    public Map<String, Integer> markdownCounts(String value) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (value == null || value.isBlank()) return result;
        ArrayDeque<Node> stack = new ArrayDeque<>();
        stack.push(PARSER.parse(value));
        while (!stack.isEmpty()) {
            Node node = stack.pop();
            String destination = node instanceof Image image ? image.getDestination()
                    : node instanceof Link link ? link.getDestination() : null;
            String path = managedPath(destination);
            if (path != null) result.merge(path, 1, Integer::sum);
            // Child nodes of an image form its alt text. They are not
            // separately rendered or fetched media references.
            if (node instanceof Image) continue;
            for (Node child = node.getLastChild(); child != null; child = child.getPrevious()) stack.push(child);
        }
        return result;
    }

    public Set<String> direct(String value) {
        String path = managedPath(value);
        return path == null ? Set.of() : Set.of(path);
    }

    public String url(String relativePath) { return prefix + relativePath; }

    /**
     * Remove references that disappeared when a parsed media node was edited.
     * This reads other asset rows without locking them: the caller already
     * holds the owner row and must never acquire unrelated asset locks here.
     * Existing references are only removed, never added, by this operation.
     */
    public List<String> pruneRemovedMarkdownReferences(String ownerType, UUID ownerId, String field, String markdown) {
        Set<String> remaining = markdown(markdown);
        var removedAvatars = new ArrayList<String>();
        var referenced = jdbc.query("""
                SELECT a.id,a.storage_path,a.display_path,a.thumbnail_path,a.clear_path,a.scope,a.deleted_at
                FROM media_references r JOIN media_assets a ON a.id=r.asset_id
                WHERE r.owner_type=? AND r.owner_id=? AND r.field=?
                """, (rs, n) -> new ReferencedAsset(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getTimestamp(7) != null), ownerType, ownerId, field);
        for (ReferencedAsset asset : referenced) {
            boolean retained = false;
            for (String path : asset.paths()) {
                if (remaining.contains(path) || asset.deleted() && remaining.stream().anyMatch(value -> equivalent(path, value))) {
                    retained = true;
                    break;
                }
            }
            if (!retained) {
                jdbc.update("DELETE FROM media_references WHERE asset_id=? AND owner_type=? AND owner_id=? AND field=?",
                        asset.id(), ownerType, ownerId, field);
                if ("AVATAR".equals(asset.scope()) && !asset.deleted() && asset.storage() != null)
                    removedAvatars.add(asset.storage());
            }
        }
        return removedAvatars;
    }

    private record ReferencedAsset(UUID id, String storage, String display, String thumbnail, String clear,
                                   String scope, boolean deleted) {
        List<String> paths() {
            List<String> result = new ArrayList<>();
            for (String path : new String[]{storage, display, thumbnail, clear}) if (path != null) result.add(path);
            return result;
        }
    }

    /** The retired tombstone guard normalized legacy root filenames to lower case. */
    public boolean equivalent(String first, String second) {
        if (first == null || second == null) return false;
        if (first.equals(second)) return true;
        return !first.contains("/") && !second.contains("/") && first.equalsIgnoreCase(second);
    }

    public String managedPath(String value) {
        if (value == null || value.isBlank() || value.indexOf('\\') >= 0) return null;
        try {
            URI uri = URI.create(value.trim());
            if (uri.isAbsolute()) {
                if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                        || uri.getUserInfo() != null || !isSiteHost(uri.getHost()) || !compatiblePort(uri)) return null;
            } else if (uri.getRawAuthority() != null) return null;
            String path = uri.getPath();
            if (path == null || !path.startsWith(prefix)) return null;
            String relative = path.substring(prefix.length());
            if (relative.isBlank() || relative.indexOf('\\') >= 0) return null;
            for (String part : relative.split("/", -1))
                if (part.isEmpty() || part.equals(".") || part.equals("..") || part.startsWith(".")) return null;
            return relative;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private boolean compatiblePort(URI uri) {
        if (site.getPort() >= 0) {
            if (uri.getPort() >= 0) return uri.getPort() == site.getPort();
            return ("https".equalsIgnoreCase(uri.getScheme()) && site.getPort() == 443)
                    || ("http".equalsIgnoreCase(uri.getScheme()) && site.getPort() == 80);
        }
        return uri.getPort() < 0 || ("https".equalsIgnoreCase(uri.getScheme()) && uri.getPort() == 443)
                || ("http".equalsIgnoreCase(uri.getScheme()) && uri.getPort() == 80);
    }

    private boolean isSiteHost(String host) {
        if (host == null || site.getHost() == null) return false;
        String main = site.getHost().toLowerCase(Locale.ROOT);
        String current = host.toLowerCase(Locale.ROOT);
        if (main.equals(current)) return true;
        int dot = main.indexOf('.');
        if (dot < 0 || !current.endsWith(main.substring(dot))) return false;
        String prefix = current.substring(0, current.length() - main.substring(dot).length());
        if (!prefix.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM subsites WHERE prefix = ?)", Boolean.class, prefix));
    }
}
