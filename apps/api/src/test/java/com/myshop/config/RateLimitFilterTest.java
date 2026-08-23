package com.myshop.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.repository.SiteConfigRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateLimitFilterTest {

    @Test
    void rateLimitBucketsUseResolvedPublicIp() throws ServletException, IOException {
        SiteConfigRepository siteConfigRepository = mock(SiteConfigRepository.class);
        when(siteConfigRepository.findByConfigKey("rate_limit_per_second")).thenReturn(Optional.empty());
        RateLimitFilter filter = new RateLimitFilter(siteConfigRepository, new ObjectMapper(),
                new ClientIpResolver("172.30.0.2"));
        FilterChain chain = (request, response) -> { };

        for (int attempt = 0; attempt < 20; attempt++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request("203.0.113.10"), response, chain);
            assertEquals(200, response.getStatus());
        }
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        filter.doFilter(request("203.0.113.10"), rejected, chain);
        assertEquals(429, rejected.getStatus());

        MockHttpServletResponse otherIp = new MockHttpServletResponse();
        filter.doFilter(request("198.51.100.20"), otherIp, chain);
        assertEquals(200, otherIp.getStatus());
    }

    private MockHttpServletRequest request(String publicIp) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/products");
        request.setRemoteAddr("172.30.0.2");
        request.addHeader("X-Real-IP", publicIp);
        return request;
    }
}
