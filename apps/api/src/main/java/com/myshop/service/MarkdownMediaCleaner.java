package com.myshop.service;

import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.LinkReferenceDefinition;
import org.commonmark.node.Node;
import org.commonmark.node.SourceSpan;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Removes only parsed media nodes, leaving the surrounding Markdown bytes intact. */
final class MarkdownMediaCleaner {
    private static final Parser PARSER = Parser.builder()
            .extensions(List.of(TablesExtension.create(), StrikethroughExtension.create(), AutolinkExtension.create()))
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
            .build();

    private MarkdownMediaCleaner() { }

    static String remove(String markdown, String targetPath, String publicSiteUrl) {
        if (markdown == null || markdown.isEmpty()) return markdown;
        URI site;
        try { site = URI.create(publicSiteUrl); }
        catch (IllegalArgumentException exception) { throw new IllegalStateException("Invalid public site URL", exception); }

        return removeMatching(markdown, destination -> matches(destination, targetPath, site));
    }

    static String removeManaged(String markdown, String relativePath, ManagedMediaReferences paths) {
        if (markdown == null || markdown.isEmpty()) return markdown;
        return removeMatching(markdown, destination -> relativePath.equals(paths.managedPath(destination)));
    }

    static String removeLegacyTombstone(String markdown, String relativePath, ManagedMediaReferences paths) {
        if (markdown == null || markdown.isEmpty()) return markdown;
        return removeMatching(markdown, destination -> paths.equivalent(relativePath, paths.managedPath(destination)));
    }

    private static String removeMatching(String markdown, java.util.function.Predicate<String> matches) {
        List<Replacement> replacements = new ArrayList<>();
        ArrayDeque<Node> nodes = new ArrayDeque<>();
        nodes.push(PARSER.parse(markdown));
        while (!nodes.isEmpty()) {
            Node node = nodes.pop();
            String destination = node instanceof Image image ? image.getDestination()
                    : node instanceof Link link ? link.getDestination()
                    : node instanceof LinkReferenceDefinition reference ? reference.getDestination() : null;
            if (destination != null && matches.test(destination)) {
                List<SourceSpan> spans = node.getSourceSpans();
                if (spans.isEmpty()) throw new IllegalStateException("Missing Markdown source position");
                if (node instanceof Link && hasUnrelatedImage(node, matches)
                        && unwrapLink(markdown, node, spans, replacements)) {
                    // Keep a different image inside the deleted link. Continue
                    // visiting children in case another child is also the
                    // deleted resource.
                    for (Node child = node.getLastChild(); child != null; child = child.getPrevious()) nodes.push(child);
                    continue;
                }
                boolean definition = node instanceof LinkReferenceDefinition;
                for (int i = 0; i < spans.size(); i++) {
                    SourceSpan span = spans.get(i);
                    replacements.add(new Replacement(span.getInputIndex(), span.getInputIndex() + span.getLength(),
                            i == 0 && !definition ? "（资源已删除）" : ""));
                }
                // A link containing an image may itself be the target. Replacing
                // the parent already removes the child and avoids overlapping edits.
                continue;
            }
            for (Node child = node.getLastChild(); child != null; child = child.getPrevious()) nodes.push(child);
        }
        replacements.sort(Comparator.comparingInt(Replacement::start).reversed());
        StringBuilder result = new StringBuilder(markdown);
        int previousStart = markdown.length();
        for (Replacement replacement : replacements) {
            if (replacement.start() < 0 || replacement.end() > previousStart || replacement.end() < replacement.start())
                throw new IllegalStateException("Overlapping Markdown source positions");
            result.replace(replacement.start(), replacement.end(), replacement.text());
            previousStart = replacement.start();
        }
        return result.toString();
    }

    /** An image's children describe alt text, not additional rendered images. */
    private static boolean hasUnrelatedImage(Node node, java.util.function.Predicate<String> matches) {
        ArrayDeque<Node> descendants = new ArrayDeque<>();
        for (Node child = node.getLastChild(); child != null; child = child.getPrevious()) descendants.push(child);
        while (!descendants.isEmpty()) {
            Node child = descendants.pop();
            if (child instanceof Image image) {
                if (!matches.test(image.getDestination())) return true;
                continue;
            }
            for (Node nested = child.getLastChild(); nested != null; nested = nested.getPrevious()) descendants.push(nested);
        }
        return false;
    }

    /**
     * Remove only a parsed link's surrounding syntax. Child source spans end
     * after escaped brackets, inline code and nested images, so the first
     * subsequent closing bracket is the end of this link's label. Preserve
     * gaps between multiline spans (including blockquote/list prefixes).
     */
    private static boolean unwrapLink(String markdown, Node link, List<SourceSpan> spans, List<Replacement> replacements) {
        int start = spans.getFirst().getInputIndex();
        if (start < 0 || start >= markdown.length() || markdown.charAt(start) != '[') return false;
        int childrenEnd = start + 1;
        for (Node child = link.getFirstChild(); child != null; child = child.getNext())
            for (SourceSpan span : child.getSourceSpans()) childrenEnd = Math.max(childrenEnd, span.getInputIndex() + span.getLength());
        int closing = -1;
        for (SourceSpan span : spans) {
            int from = Math.max(childrenEnd, span.getInputIndex());
            int end = Math.min(markdown.length(), span.getInputIndex() + span.getLength());
            for (int index = from; index < end; index++) if (markdown.charAt(index) == ']') { closing = index; break; }
            if (closing >= 0) break;
        }
        if (closing < 0) return false;
        replacements.add(new Replacement(start, start + 1, ""));
        boolean first = true;
        for (SourceSpan span : spans) {
            int from = Math.max(closing, span.getInputIndex());
            int end = span.getInputIndex() + span.getLength();
            if (from >= end) continue;
            replacements.add(new Replacement(from, end, first ? "（资源已删除）" : ""));
            first = false;
        }
        return !first;
    }

    private static boolean matches(String destination, String targetPath, URI site) {
        try {
            URI uri = URI.create(destination);
            if (uri.isAbsolute()) {
                if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                        || uri.getUserInfo() != null || site.getHost() == null
                        || !site.getHost().equalsIgnoreCase(uri.getHost()) || uri.getPort() != site.getPort()) return false;
            } else if (uri.getRawAuthority() != null) return false;
            return targetPath.equals(uri.getPath());
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private record Replacement(int start, int end, String text) { }
}
