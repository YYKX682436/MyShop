package com.myshop.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.entity.SiteConfig;
import com.myshop.repository.SiteConfigRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeviceRateLimitFilterTest {

    @Test
    void deviceLimiterDefaultsToOff() throws Exception {
        SiteConfigRepository repository = mock(SiteConfigRepository.class);
        when(repository.findByConfigKey("device_rate_limit_enabled")).thenReturn(Optional.empty());
        DeviceRateLimitFilter filter = filter(repository);
        FilterChain chain = (request, response) -> { };

        for (int attempt = 0; attempt < 20; attempt++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request("POST", "/api/orders", "203.0.113.10", DEVICE_ID), response, chain);
            assertEquals(200, response.getStatus());
        }
    }

    @Test
    void creationLimitsAreIndependentAndOrderReadsDoNotConsumeOrderQuota() throws Exception {
        SiteConfigRepository repository = configuredRepository(1, 2, 1);
        DeviceRateLimitFilter filter = filter(repository);
        FilterChain chain = (request, response) -> { };

        for (int attempt = 0; attempt < 2; attempt++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request("POST", "/api/auth/login", "203.0.113.10", DEVICE_ID), response, chain);
            assertEquals(200, response.getStatus());
        }
        assertEquals(429, invoke(filter, "POST", "/api/auth/login", DEVICE_ID, chain).getStatus());

        // Registration and new-order creation use separate device buckets.
        assertEquals(200, invoke(filter, "POST", "/api/auth/register", DEVICE_ID, chain).getStatus());
        assertEquals(429, invoke(filter, "POST", "/api/auth/register", DEVICE_ID, chain).getStatus());

        for (String path : new String[] {
                "/api/orders/123",
                "/api/orders/123/refresh",
                "/api/orders/123/repay",
                "/api/orders/123/export",
        }) {
            String method = path.endsWith("/export") || path.equals("/api/orders/123") ? "GET" : "POST";
            assertEquals(200, invoke(filter, method, path, DEVICE_ID, chain).getStatus(), path);
        }

        assertEquals(200, invoke(filter, "POST", "/api/orders", DEVICE_ID, chain).getStatus());
        MockHttpServletResponse orderRejected = invoke(filter, "POST", "/api/orders", DEVICE_ID, chain);
        assertEquals(429, orderRejected.getStatus());
        long retryAfter = Long.parseLong(orderRejected.getHeader("Retry-After"));
        assertTrue(retryAfter > 0 && retryAfter <= 3600);
        assertTrue(orderRejected.getContentAsString().contains("设备操作过于频繁"));
    }

    @Test
    void missingDeviceIdFallsBackToIndependentClientIps() throws Exception {
        SiteConfigRepository repository = configuredRepository(1, 1, 1);
        DeviceRateLimitFilter filter = filter(repository);
        FilterChain chain = (request, response) -> { };

        assertEquals(200, invoke(filter, "POST", "/api/orders", null, "203.0.113.10", chain).getStatus());
        assertEquals(429, invoke(filter, "POST", "/api/orders", null, "203.0.113.10", chain).getStatus());
        assertEquals(200, invoke(filter, "POST", "/api/orders", null, "198.51.100.20", chain).getStatus());
    }

    private static final String DEVICE_ID = "a".repeat(64);

    private DeviceRateLimitFilter filter(SiteConfigRepository repository) {
        return new DeviceRateLimitFilter(repository, new ObjectMapper(), new ClientIpResolver("172.30.0.2"));
    }

    private SiteConfigRepository configuredRepository(int order, int login, int register) {
        SiteConfigRepository repository = mock(SiteConfigRepository.class);
        when(repository.findByConfigKey("device_rate_limit_enabled"))
                .thenReturn(Optional.of(config("true")));
        when(repository.findByConfigKey("device_order_limit_per_hour"))
                .thenReturn(Optional.of(config(Integer.toString(order))));
        when(repository.findByConfigKey("device_login_limit_per_hour"))
                .thenReturn(Optional.of(config(Integer.toString(login))));
        when(repository.findByConfigKey("device_register_limit_per_hour"))
                .thenReturn(Optional.of(config(Integer.toString(register))));
        return repository;
    }

    private MockHttpServletResponse invoke(DeviceRateLimitFilter filter, String method, String path,
                                           String deviceId, FilterChain chain) throws IOException, ServletException {
        return invoke(filter, method, path, deviceId, "203.0.113.10", chain);
    }

    private MockHttpServletResponse invoke(DeviceRateLimitFilter filter, String method, String path,
                                           String deviceId, String ip, FilterChain chain)
            throws IOException, ServletException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(method, path, ip, deviceId), response, chain);
        return response;
    }

    private MockHttpServletRequest request(String method, String path, String ip, String deviceId) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        request.setRemoteAddr("172.30.0.2");
        request.addHeader("X-Real-IP", ip);
        if (deviceId != null) request.addHeader("X-Device-Id", deviceId);
        return request;
    }

    private static SiteConfig config(String value) {
        SiteConfig config = new SiteConfig();
        config.setConfigValue(value);
        return config;
    }
}
