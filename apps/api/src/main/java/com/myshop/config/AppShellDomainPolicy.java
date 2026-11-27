package com.myshop.config;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Canonical HTTPS origin and one-label wildcard rules for Android app binding. */
public final class AppShellDomainPolicy {
    private AppShellDomainPolicy() {}

    public record Origin(String host, String canonicalOrigin) {}

    public static Origin parseOrigin(String input) {
        if (input == null || input.isBlank() || input.length() > 2048
                || input.indexOf('\\') >= 0 || input.chars().anyMatch(Character::isISOControl)
                || input.chars().anyMatch(Character::isWhitespace)) return null;
        try {
            URI uri = new URI(input);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawAuthority() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath()))) {
                return null;
            }
            String authority = uri.getRawAuthority();
            if (authority.indexOf('@') >= 0 || authority.indexOf('%') >= 0 || authority.indexOf('\\') >= 0
                    || authority.indexOf('/') >= 0 || authority.indexOf('?') >= 0 || authority.indexOf('#') >= 0
                    || authority.startsWith("[") || authority.endsWith("]")) return null;

            String rawHost = authority;
            int colon = authority.lastIndexOf(':');
            if (colon >= 0) {
                if (authority.indexOf(':') != colon || !"443".equals(authority.substring(colon + 1))) return null;
                rawHost = authority.substring(0, colon);
            }
            if (rawHost.isBlank() || rawHost.endsWith("..")) return null;
            if (rawHost.endsWith(".")) rawHost = rawHost.substring(0, rawHost.length() - 1);
            // Browser and Android clients canonicalize Unicode labels with UTS #46 before transport.
            // Require the resulting ASCII form here so the server never reinterprets a U-label using
            // the older JDK IDNA2003 mapping (notably for deviation characters such as sharp s).
            if (!rawHost.chars().allMatch(ch -> ch < 0x80)) return null;
            String host = IDN.toASCII(rawHost, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            if (!validHostname(host)) return null;
            return new Origin(host, "https://" + host + "/");
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }

    public static String normalizePattern(String input) {
        if (input == null || input.isBlank() || input.length() > 260) return null;
        String candidate = input.trim();
        boolean wildcard = candidate.startsWith("*.");
        String hostInput = wildcard ? candidate.substring(2) : candidate;
        if (hostInput.isEmpty() || hostInput.contains("*") || hostInput.contains(":")
                || hostInput.contains("/") || hostInput.contains("@") || hostInput.contains("\\")
                || hostInput.contains("%") || hostInput.contains("?") || hostInput.contains("#")) return null;
        if (hostInput.endsWith("..")) return null;
        if (hostInput.endsWith(".")) hostInput = hostInput.substring(0, hostInput.length() - 1);
        if (!hostInput.chars().allMatch(ch -> ch < 0x80)) return null;
        try {
            String host = IDN.toASCII(hostInput, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            if (!validHostname(host)) return null;
            return (wildcard ? "*." : "") + host;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static boolean matches(String normalizedPattern, String host) {
        if (normalizedPattern == null || host == null) return false;
        if (!normalizedPattern.startsWith("*.")) return normalizedPattern.equals(host);
        String suffix = normalizedPattern.substring(1); // includes the boundary dot
        if (!host.endsWith(suffix)) return false;
        String label = host.substring(0, host.length() - suffix.length());
        return !label.isEmpty() && label.indexOf('.') < 0;
    }

    public static String selectPattern(List<String> normalizedPatterns, String host) {
        if (normalizedPatterns == null || host == null) return null;
        String exact = normalizedPatterns.stream()
                .filter(pattern -> pattern != null && !pattern.startsWith("*."))
                .filter(pattern -> pattern.equals(host))
                .findFirst().orElse(null);
        if (exact != null) return exact;
        return normalizedPatterns.stream()
                .filter(pattern -> pattern != null && pattern.startsWith("*."))
                .filter(pattern -> matches(pattern, host))
                .max(Comparator.comparingInt(String::length))
                .orElse(null);
    }

    private static boolean validHostname(String host) {
        if (host.length() > 253 || host.isEmpty() || host.equals("localhost") || host.endsWith(".localhost")) return false;
        String[] labels = host.split("\\.", -1);
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63 || !label.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")) return false;
        }
        // Reject IPv4 and the abbreviated/octal/hex numeric host forms accepted by URL parsers.
        boolean numericAddress = true;
        for (String label : labels) {
            if (!label.matches("(?i)(?:[0-9]+|0x[0-9a-f]+|0[0-7]+)")) {
                numericAddress = false;
                break;
            }
        }
        return !numericAddress;
    }
}
