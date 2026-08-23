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

    public ClientIpResolver(@Value("${rate-limit.trusted-proxies:127.0.0.1,::1,0:0:0:0:0:0:0:1}") String trustedProxies) {
        this.trustedProxies = Arrays.stream(trustedProxies.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public String resolve(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        if (!trustedProxies.contains(remoteAddress)) return remoteAddress;

        String realIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(realIp)) return realIp.trim();

        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            String[] parts = forwarded.split(",");
            for (int index = parts.length - 1; index >= 0; index--) {
                String candidate = parts[index].trim();
                if (!candidate.isEmpty() && !trustedProxies.contains(candidate)) return candidate;
            }
        }
        return remoteAddress;
    }
}
