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

    @Test
    void trustedProxyAcceptsLiteralIpv4AndIpv6Only() {
        MockHttpServletRequest ipv4 = new MockHttpServletRequest();
        ipv4.setRemoteAddr("172.30.0.2");
        ipv4.addHeader("X-Real-IP", "203.0.113.41");
        assertEquals("203.0.113.41", resolver.resolve(ipv4));

        MockHttpServletRequest ipv6 = new MockHttpServletRequest();
        ipv6.setRemoteAddr("172.30.0.2");
        ipv6.addHeader("X-Real-IP", "2001:4860:4860::8888");
        assertEquals("2001:4860:4860::8888", resolver.resolve(ipv6));

        MockHttpServletRequest malformed = new MockHttpServletRequest();
        malformed.setRemoteAddr("172.30.0.2");
        malformed.addHeader("X-Real-IP", "203.0.113.999");
        malformed.addHeader("X-Forwarded-For", "2001:db8:::1");
        assertEquals("172.30.0.2", resolver.resolve(malformed));
    }

    private String resolve(String publicIp, String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        request.addHeader("X-Real-IP", publicIp);
        return resolver.resolve(request);
    }
}
