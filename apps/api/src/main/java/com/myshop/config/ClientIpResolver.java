package com.myshop.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Single source of truth for the client IP used by rate limits, order risk
 * checks, and audit logging. Proxy headers are accepted only from configured
 * proxy addresses.
 */
@Component
public class ClientIpResolver {

    private final Set<String> trustedProxies;

    public ClientIpResolver(@Value("${rate-limit.trusted-proxies:127.0.0.1,::1,0:0:0:0:0:0:0:1,172.30.0.2}") String trustedProxies) {
        this.trustedProxies = Arrays.stream(trustedProxies.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public String resolve(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        if (!trustedProxies.contains(remoteAddress)) return remoteAddress;

        String realIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(realIp) && isIpLiteral(realIp.trim())) return realIp.trim();

        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            String[] parts = forwarded.split(",");
            for (int index = parts.length - 1; index >= 0; index--) {
                String candidate = parts[index].trim();
                if (isIpLiteral(candidate) && !trustedProxies.contains(candidate)) return candidate;
            }
        }
        return remoteAddress;
    }

    private boolean isIpLiteral(String value) {
        // The proxy is trusted by exact source address, but its forwarded value
        // still has to be an IP literal. This prevents a malformed/header-based
        // hostname from becoming a rate-limit bucket or audit identity.
        return value != null && value.length() <=  ForwardedIp.MAX_LENGTH
                && value.matches("[0-9A-Fa-f:.]+")
                && (value.indexOf('.') >= 0 || value.indexOf(':') >= 0);
    }

    private static final class ForwardedIp {
        private static final int MAX_LENGTH = 45;
    }
}
