package com.myshop.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Single source of truth for the client IP used by rate limits, risk checks,
 * audit logging, and successful user-login records. Forwarded headers are
 * accepted only from the exact trusted Caddy source address.
 */
@Component
public class ClientIpResolver {

    private static final int MAX_LENGTH = 45;
    private final Set<String> trustedProxies;

    public ClientIpResolver(@Value("${rate-limit.trusted-proxies:127.0.0.1,::1,0:0:0:0:0:0:0:1,172.30.0.2}") String trustedProxies) {
        this.trustedProxies = Arrays.stream(trustedProxies.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public String resolve(HttpServletRequest request) {
        if (request == null) return null;
        String remoteAddress = normalize(request.getRemoteAddr());
        if (!trustedProxies.contains(remoteAddress)) return remoteAddress;

        String realIp = normalize(request.getHeader("X-Real-IP"));
        if (isIpLiteral(realIp)) return realIp;

        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            String[] parts = forwarded.split(",");
            for (int index = parts.length - 1; index >= 0; index--) {
                String candidate = normalize(parts[index]);
                if (isIpLiteral(candidate) && !trustedProxies.contains(candidate)) return candidate;
            }
        }
        return remoteAddress;
    }

    private String normalize(String value) {
        return value == null ? null : value.trim();
    }

    private boolean isIpLiteral(String value) {
        if (value == null || value.length() > MAX_LENGTH) return false;
        if (value.contains(".")) return isIpv4Literal(value);
        if (!value.contains(":") || !value.matches("[0-9A-Fa-f:]+")) return false;
        try {
            return InetAddress.getByName(value).getAddress().length == 16;
        } catch (Exception exception) {
            return false;
        }
    }

    private boolean isIpv4Literal(String value) {
        if (!value.matches("[0-9.]+")) return false;
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            try {
                if (part.isEmpty() || part.length() > 3 || Integer.parseInt(part) > 255) return false;
            } catch (NumberFormatException exception) {
                return false;
            }
        }
        return true;
    }
}
