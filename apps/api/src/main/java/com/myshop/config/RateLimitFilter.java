package com.myshop.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.common.ApiResponse;
import com.myshop.constant.ErrorCode;
import com.myshop.repository.SiteConfigRepository;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Order(2)
@RequiredArgsConstructor
public class RateLimitFilter implements Filter {

    private final SiteConfigRepository siteConfigRepository;
    private final ObjectMapper objectMapper;
    private final ClientIpResolver clientIpResolver;

    /** General and action-specific per-IP token buckets. */
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final Map<String, TokenBucket> loginBuckets = new ConcurrentHashMap<>();
    private final Map<String, TokenBucket> registerBuckets = new ConcurrentHashMap<>();
    private final Map<String, TokenBucket> supportBuckets = new ConcurrentHashMap<>();

    private volatile int cachedRateLimit = RiskConfigPolicy.runtimeInteger("rate_limit_per_second", null);
    private volatile int cachedLoginLimit = RiskConfigPolicy.runtimeInteger("login_requests_per_minute", null);
    private volatile int cachedRegisterLimit = RiskConfigPolicy.runtimeInteger("register_requests_per_minute", null);
    private volatile long cacheExpiry = 0;

    private static final String LOGIN_PATH = "/api/auth/login";
    private static final String REGISTER_PATH = "/api/auth/register";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String path = httpRequest.getRequestURI();

        // Static uploads do not consume API request capacity.
        if (path.startsWith("/api/uploads")) {
            chain.doFilter(request, response);
            return;
        }

        String clientIp = resolveClientIp(httpRequest);
        refreshConfig();
        String method = httpRequest.getMethod();

        if ("POST".equalsIgnoreCase(method) && LOGIN_PATH.equals(path)) {
            TokenBucket bucket = loginBuckets.computeIfAbsent(clientIp,
                    key -> new TokenBucket(cachedLoginLimit, 60_000));
            if (!bucket.tryConsume(cachedLoginLimit)) {
                rejectTooManyRequests(response, "登录请求过于频繁，请稍后重试", bucket.retryAfterSeconds());
                return;
            }
        } else if ("POST".equalsIgnoreCase(method) && REGISTER_PATH.equals(path)) {
            TokenBucket bucket = registerBuckets.computeIfAbsent(clientIp,
                    key -> new TokenBucket(cachedRegisterLimit, 60_000));
            if (!bucket.tryConsume(cachedRegisterLimit)) {
                rejectTooManyRequests(response, "注册请求过于频繁，请稍后重试", bucket.retryAfterSeconds());
                return;
            }
        }

        if ("POST".equalsIgnoreCase(method)
                && (path.equals("/api/support/conversations/send") || path.equals("/api/admin/support/conversations/send")
                || path.matches("/api/(admin/)?support/conversations/[^/]+/after-sale-reply")
                || path.matches("/api/(user|admin)/orders/[^/]+/after-sale/messages"))) {
            TokenBucket supportBucket = supportBuckets.computeIfAbsent(clientIp, key -> new TokenBucket(30, 60_000));
            if (!supportBucket.tryConsume()) {
                rejectTooManyRequests(response, "消息发送过于频繁，请稍后再试", supportBucket.retryAfterSeconds());
                return;
            }
        }

        TokenBucket bucket = buckets.computeIfAbsent(clientIp,
                key -> new TokenBucket(cachedRateLimit));
        if (!bucket.tryConsume(cachedRateLimit)) {
            rejectTooManyRequests(response, "请求过于频繁，请稍后重试", bucket.retryAfterSeconds());
            return;
        }

        chain.doFilter(request, response);

        long now = System.currentTimeMillis();
        if (buckets.size() > 10000) {
            buckets.entrySet().removeIf(entry -> now - entry.getValue().lastAccess > 60_000);
        }
        if (loginBuckets.size() > 5000) {
            loginBuckets.entrySet().removeIf(entry -> now - entry.getValue().lastAccess > 120_000);
        }
        if (registerBuckets.size() > 5000) {
            registerBuckets.entrySet().removeIf(entry -> now - entry.getValue().lastAccess > 120_000);
        }
        if (supportBuckets.size() > 5000) {
            supportBuckets.entrySet().removeIf(entry -> now - entry.getValue().lastAccess > 120_000);
        }
    }

    /**
     * 解析客户端真实 IP。
     * 仅当直连 IP 在受信代理列表中时，才读取代理头。
     * 优先使用 X-Real-IP（Nginx 用 $remote_addr 覆写，客户端无法伪造），
     * X-Forwarded-For 的首项可被客户端注入，不适合用于安全决策。
     */
    private String resolveClientIp(HttpServletRequest request) {
        return clientIpResolver.resolve(request);
    }

    private void refreshConfig() {
        long now = System.currentTimeMillis();
        if (now > cacheExpiry) {
            try {
                cachedRateLimit = configuredInteger("rate_limit_per_second");
                cachedLoginLimit = configuredInteger("login_requests_per_minute");
                cachedRegisterLimit = configuredInteger("register_requests_per_minute");
            } catch (Exception exception) {
                // Database unavailable: keep the last valid configuration.
            }
            cacheExpiry = now + 5_000;
        }
    }

    private int configuredInteger(String key) {
        return siteConfigRepository.findByConfigKey(key)
                .map(config -> RiskConfigPolicy.runtimeInteger(key, config.getConfigValue()))
                .orElseGet(() -> RiskConfigPolicy.runtimeInteger(key, null));
    }

    private void rejectTooManyRequests(ServletResponse response, String message, long retryAfterSeconds)
            throws IOException {
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        httpResponse.setStatus(429);
        httpResponse.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        httpResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
        httpResponse.setCharacterEncoding("UTF-8");
        httpResponse.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.error(ErrorCode.TOO_MANY_REQUESTS, message)));
    }

    private static class TokenBucket {
        private int capacity;
        private final long refillIntervalMs;
        private long tokens;
        private long lastRefill;
        volatile long lastAccess;

        /** Default per-second request bucket. */
        TokenBucket(int capacity) {
            this(capacity, 1000);
        }

        /** Configurable refill interval used for minute-based limits. */
        TokenBucket(int capacity, long refillIntervalMs) {
            this.capacity = Math.max(1, capacity);
            this.refillIntervalMs = refillIntervalMs;
            this.tokens = this.capacity;
            this.lastRefill = System.currentTimeMillis();
            this.lastAccess = this.lastRefill;
        }

        synchronized boolean tryConsume() {
            return tryConsume(capacity);
        }

        synchronized boolean tryConsume(int requestedCapacity) {
            long now = System.currentTimeMillis();
            capacity = Math.max(1, requestedCapacity);
            tokens = Math.min(tokens, capacity);
            refill(now);
            lastAccess = now;
            if (tokens > 0) {
                tokens--;
                return true;
            }
            return false;
        }

        synchronized long retryAfterSeconds() {
            long remainingMs = Math.max(0, lastRefill + refillIntervalMs - System.currentTimeMillis());
            return Math.max(1, (remainingMs + 999) / 1000);
        }

        private void refill(long now) {
            long elapsed = now - lastRefill;
            if (elapsed >= refillIntervalMs) {
                long intervals = elapsed / refillIntervalMs;
                tokens = Math.min(capacity, tokens + intervals * capacity);
                lastRefill += intervals * refillIntervalMs;
            }
        }
    }
}
