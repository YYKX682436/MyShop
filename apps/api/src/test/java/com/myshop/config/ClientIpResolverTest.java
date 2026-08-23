package com.myshop.config;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver("172.30.0.2,127.0.0.1");

    @Test
    void trustedCaddyProducesDifferentPublicClientBuckets() {
        assertEquals("203.0.113.10", resolve("203.0.113.10", "172.30.0.2"));
        assertEquals("198.51.100.20", resolve("198.51.100.20", "172.30.0.2"));
    }

    @Test
    void untrustedSourceCannotSupplyForwardedHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.30");
        request.addHeader("X-Real-IP", "203.0.113.99");
        request.addHeader("X-Forwarded-For", "203.0.113.98");

        assertEquals("198.51.100.30", resolver.resolve(request));
    }

    @Test
    void trustedProxyIgnoresMalformedForwardedValue() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.30.0.2");
        request.addHeader("X-Real-IP", "not-an-ip");
        request.addHeader("X-Forwarded-For", "203.0.113.40, 172.30.0.2");

        assertEquals("203.0.113.40", resolver.resolve(request));
    }

    private String resolve(String publicIp, String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        request.addHeader("X-Real-IP", publicIp);
        return resolver.resolve(request);
    }
}
