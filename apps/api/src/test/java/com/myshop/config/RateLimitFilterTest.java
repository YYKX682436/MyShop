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
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateLimitFilterTest {

    @Test
    void generalRateLimitUsesResolvedPublicIpAndSafeDefault() throws ServletException, IOException {
        SiteConfigRepository repository = mock(SiteConfigRepository.class);
        when(repository.findByConfigKey("rate_limit_per_second")).thenReturn(Optional.empty());
        RateLimitFilter filter = new RateLimitFilter(repository, new ObjectMapper(),
                new ClientIpResolver("172.30.0.2"));
        FilterChain chain = (request, response) -> { };

        for (int attempt = 0; attempt < 25; attempt++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request("GET", "/api/products", "203.0.113.10"), response, chain);
            assertEquals(200, response.getStatus());
        }
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        filter.doFilter(request("GET", "/api/products", "203.0.113.10"), rejected, chain);
        assertEquals(429, rejected.getStatus());
        assertEquals("1", rejected.getHeader("Retry-After"));

        MockHttpServletResponse otherIp = new MockHttpServletResponse();
        filter.doFilter(request("GET", "/api/products", "198.51.100.20"), otherIp, chain);
        assertEquals(200, otherIp.getStatus());
    }

    @Test
    void savedLoginAndRegistrationLimitsHaveIndependentPerIpBuckets() throws Exception {
        SiteConfigRepository repository = mock(SiteConfigRepository.class);
        when(repository.findByConfigKey("rate_limit_per_second")).thenReturn(Optional.of(config("1000")));
        when(repository.findByConfigKey("login_requests_per_minute")).thenReturn(Optional.of(config("15")));
        when(repository.findByConfigKey("register_requests_per_minute")).thenReturn(Optional.of(config("5")));
        RateLimitFilter filter = new RateLimitFilter(repository, new ObjectMapper(),
                new ClientIpResolver("172.30.0.2"));
        FilterChain chain = (request, response) -> { };

        for (int attempt = 0; attempt < 15; attempt++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request("POST", "/api/auth/login", "203.0.113.10"), response, chain);
            assertEquals(200, response.getStatus());
        }
        MockHttpServletResponse loginRejected = new MockHttpServletResponse();
        filter.doFilter(request("POST", "/api/auth/login", "203.0.113.10"), loginRejected, chain);
        assertEquals(429, loginRejected.getStatus());
        long loginRetryAfter = Long.parseLong(loginRejected.getHeader("Retry-After"));
        assertTrue(loginRetryAfter > 0 && loginRetryAfter <= 60);
        assertEquals(200, invoke(filter, "POST", "/api/auth/login", "198.51.100.20", chain).getStatus());

        for (int attempt = 0; attempt < 5; attempt++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request("POST", "/api/auth/register", "203.0.113.10"), response, chain);
            assertEquals(200, response.getStatus());
        }
        MockHttpServletResponse registerRejected = new MockHttpServletResponse();
        filter.doFilter(request("POST", "/api/auth/register", "203.0.113.10"), registerRejected, chain);
        assertEquals(429, registerRejected.getStatus());
        long registerRetryAfter = Long.parseLong(registerRejected.getHeader("Retry-After"));
        assertTrue(registerRetryAfter > 0 && registerRetryAfter <= 60);

        @SuppressWarnings("unchecked")
        Map<String, Object> loginBuckets = (Map<String, Object>)
                org.springframework.test.util.ReflectionTestUtils.getField(filter, "loginBuckets");
        Object exhaustedBucket = loginBuckets.get("203.0.113.10");
        org.springframework.test.util.ReflectionTestUtils.setField(exhaustedBucket, "lastRefill",
                System.currentTimeMillis() - 60_001);
        assertEquals(200, invoke(filter, "POST", "/api/auth/login", "203.0.113.10", chain).getStatus());
    }

    @Test
    void invalidStoredGlobalRateFallsBackInsteadOfBlockingAllRequests() throws Exception {
        SiteConfigRepository repository = mock(SiteConfigRepository.class);
        when(repository.findByConfigKey("rate_limit_per_second")).thenReturn(Optional.of(config("0")));
        RateLimitFilter filter = new RateLimitFilter(repository, new ObjectMapper(), new ClientIpResolver(""));
        FilterChain chain = (request, response) -> { };

        for (int attempt = 0; attempt < 25; attempt++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request("GET", "/api/products", "203.0.113.10"), response, chain);
            assertEquals(200, response.getStatus());
        }
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        filter.doFilter(request("GET", "/api/products", "203.0.113.10"), rejected, chain);
        assertEquals(429, rejected.getStatus());
    }

    @Test
    void supportWritesHaveASeparateMinuteLimit() throws Exception {
        SiteConfigRepository repository = mock(SiteConfigRepository.class);
        when(repository.findByConfigKey("rate_limit_per_second")).thenReturn(Optional.of(config("1000")));
        RateLimitFilter filter = new RateLimitFilter(repository, new ObjectMapper(), new ClientIpResolver(""));
        FilterChain chain = (request, response) -> { };

        for (int attempt = 0; attempt < 30; attempt++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request("POST", "/api/support/conversations/send", "203.0.113.10"), response, chain);
            assertEquals(200, response.getStatus());
        }
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        filter.doFilter(request("POST", "/api/support/conversations/send", "203.0.113.10"), rejected, chain);
        assertEquals(429, rejected.getStatus());
        assertTrue(rejected.getContentAsString().contains("消息发送过于频繁"));
    }

    private MockHttpServletRequest request(String method, String path, String publicIp) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        request.setRemoteAddr("172.30.0.2");
        request.addHeader("X-Real-IP", publicIp);
        return request;
    }

    private MockHttpServletResponse invoke(RateLimitFilter filter, String method, String path,
                                           String publicIp, FilterChain chain) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(method, path, publicIp), response, chain);
        return response;
    }

    private static SiteConfig config(String value) {
        SiteConfig config = new SiteConfig();
        config.setConfigValue(value);
        return config;
    }
}
