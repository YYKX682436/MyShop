package com.myshop.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.common.ApiResponse;
import com.myshop.constant.ErrorCode;
import com.myshop.repository.SiteConfigRepository;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 设备指纹限流 Filter。
 * <p>
 * 以前端传递的 X-Device-Id 为维度进行滑动窗口限流。
 * X-Device-Id 缺失或格式异常时降级为 IP 限流（不拒绝请求）。
 * <p>
 * 开关：site_configs 表中 device_rate_limit_enabled（默认 false，需在后台手动启用）。
 */
@Slf4j
@Component
@Order(5)
@RequiredArgsConstructor
public class DeviceRateLimitFilter implements Filter {

    private final SiteConfigRepository siteConfigRepository;
    private final ObjectMapper objectMapper;
    private final ClientIpResolver clientIpResolver;

    private static final String DEVICE_ID_HEADER = "X-Device-Id";

    /** 合法 deviceId 格式：64 位十六进制（SHA-256 输出） */
    private static final Pattern DEVICE_ID_PATTERN = Pattern.compile("^[a-fA-F0-9]{64}$");


    // ── 限流桶存储 ──
    private final Map<String, SlidingWindowCounter> deviceBuckets = new ConcurrentHashMap<>();

    // ── 配置缓存 ──
    private volatile boolean cachedEnabled = false;
    private volatile int orderLimit = RiskConfigPolicy.runtimeInteger("device_order_limit_per_hour", null);
    private volatile int loginLimit = RiskConfigPolicy.runtimeInteger("device_login_limit_per_hour", null);
    private volatile int registerLimit = RiskConfigPolicy.runtimeInteger("device_register_limit_per_hour", null);
    private volatile long configCacheExpiry = 0;

    private static final String LOGIN_PATH = "/api/auth/login";
    private static final String REGISTER_PATH = "/api/auth/register";

    private enum Category {
        ORDER, LOGIN, REGISTER, NONE
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String path = httpRequest.getRequestURI();
        String method = httpRequest.getMethod();

        Category category = classify(path, method);
        if (category == Category.NONE) {
            chain.doFilter(request, response);
            return;
        }

        // 检查开关
        refreshConfig();
        if (!cachedEnabled) {
            chain.doFilter(request, response);
            return;
        }

        // 解析限流 key（优先 deviceId，降级为 IP）
        String rateLimitKey = resolveRateLimitKey(httpRequest);

        // 设备/IP 维度限流
        int limit = switch (category) {
            case ORDER -> orderLimit;
            case LOGIN -> loginLimit;
            case REGISTER -> registerLimit;
            default -> 20;
        };

        String bucketKey = "device:" + rateLimitKey + ":" + category.name();
        SlidingWindowCounter counter = deviceBuckets.computeIfAbsent(
                bucketKey, k -> new SlidingWindowCounter(3600_000L)); // 1 小时窗口

        if (!counter.tryIncrement(limit)) {
            log.info("Device rate limit hit: category={}, key={}, path={}", category, rateLimitKey, path);
            reject(response, counter.retryAfterSeconds());
            return;
        }

        chain.doFilter(request, response);

        // 定期清理
        long now = System.currentTimeMillis();
        if (deviceBuckets.size() > 10000) {
            deviceBuckets.entrySet().removeIf(e -> now - e.getValue().lastAccess > 3600_000L);
        }
    }

    private Category classify(String path, String method) {
        if (!"POST".equalsIgnoreCase(method)) return Category.NONE;

        if ("/api/orders".equals(path)) return Category.ORDER;
        if (LOGIN_PATH.equals(path)) return Category.LOGIN;
        if (REGISTER_PATH.equals(path)) return Category.REGISTER;
        return Category.NONE;
    }

    private String resolveRateLimitKey(HttpServletRequest request) {
        String deviceId = request.getHeader(DEVICE_ID_HEADER);
        if (StringUtils.hasText(deviceId) && DEVICE_ID_PATTERN.matcher(deviceId).matches()) {
            return deviceId;
        }
        // 降级为 IP
        return resolveClientIp(request);
    }

    private String resolveClientIp(HttpServletRequest request) {
        return clientIpResolver.resolve(request);
    }

    private void refreshConfig() {
        long now = System.currentTimeMillis();
        if (now > configCacheExpiry) {
            try {
                cachedEnabled = getConfigBool("device_rate_limit_enabled");
                orderLimit = getConfigInt("device_order_limit_per_hour");
                loginLimit = getConfigInt("device_login_limit_per_hour");
                registerLimit = getConfigInt("device_register_limit_per_hour");
            } catch (Exception exception) {
                // Keep the last valid snapshot; a cold start defaults to disabled.
            }
            configCacheExpiry = now + 5_000;
        }
    }

    private int getConfigInt(String key) {
        return siteConfigRepository.findByConfigKey(key)
                .map(config -> RiskConfigPolicy.runtimeInteger(key, config.getConfigValue()))
                .orElseGet(() -> RiskConfigPolicy.runtimeInteger(key, null));
    }

    private boolean getConfigBool(String key) {
        return siteConfigRepository.findByConfigKey(key)
                .map(config -> RiskConfigPolicy.runtimeBoolean(key, config.getConfigValue()))
                .orElse(false);
    }

    private void reject(ServletResponse response, long retryAfterSeconds) throws IOException {
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        httpResponse.setStatus(429);
        httpResponse.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        httpResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
        httpResponse.setCharacterEncoding("UTF-8");
        httpResponse.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.error(ErrorCode.DEVICE_RATE_LIMITED, "设备操作过于频繁，请稍后再试")));
    }

    @Override
    public void init(FilterConfig filterConfig) {
        log.info("Device rate limit filter initialized");
    }

    /**
     * 滑动窗口计数器。
     * 在 windowMs 时间窗口内计数请求次数，超过 limit 则拒绝。
     */
    private static class SlidingWindowCounter {
        private final long windowMs;
        private long windowStart;
        private int count;
        volatile long lastAccess;

        SlidingWindowCounter(long windowMs) {
            this.windowMs = windowMs;
            this.windowStart = System.currentTimeMillis();
            this.count = 0;
            this.lastAccess = System.currentTimeMillis();
        }

        synchronized boolean tryIncrement(int limit) {
            long now = System.currentTimeMillis();
            lastAccess = now;

            // 窗口已过期，重置
            if (now - windowStart >= windowMs) {
                windowStart = now;
                count = 0;
            }

            if (count >= limit) {
                return false;
            }
            count++;
            return true;
        }

        synchronized long retryAfterSeconds() {
            long remainingMs = Math.max(0, windowStart + windowMs - System.currentTimeMillis());
            return Math.max(1, (remainingMs + 999) / 1000);
        }
    }
}
