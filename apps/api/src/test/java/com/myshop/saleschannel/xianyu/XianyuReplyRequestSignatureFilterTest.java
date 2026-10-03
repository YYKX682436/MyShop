package com.myshop.saleschannel.xianyu;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XianyuReplyRequestSignatureFilterTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-02T00:00:00Z"), ZoneOffset.UTC);
    private static final String BODY = "{\"message_id\":\"m-1\"}";

    @Test
    void validHmacPassesAndControllerCanReadTheOriginalBody() throws Exception {
        var filter = new XianyuReplyRequestSignatureFilter("MYSHOP", "phase-one-secret", CLOCK);
        MockHttpServletRequest request = request();
        sign(request);
        MockHttpServletResponse response = new MockHttpServletResponse();
        var called = new boolean[1];

        filter.doFilter(request, response, (wrapped, ignored) -> {
            called[0] = true;
            assertEquals(BODY, new String(wrapped.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        });

        assertTrue(called[0]);
        assertEquals(200, response.getStatus());
    }

    @Test
    void badSignatureIsRejectedBeforeController() throws Exception {
        var filter = new XianyuReplyRequestSignatureFilter("MYSHOP", "phase-one-secret", CLOCK);
        MockHttpServletRequest request = request();
        request.addHeader("X-MyShop-Key-Id", "MYSHOP");
        request.addHeader("X-MyShop-Timestamp", "1790899200");
        request.addHeader("X-MyShop-Nonce", "nonce-1");
        request.addHeader("X-MyShop-Signature", "00");
        MockHttpServletResponse response = new MockHttpServletResponse();
        var called = new boolean[1];

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> called[0] = true);

        assertFalse(called[0]);
        assertEquals(401, response.getStatus());
    }

    @Test
    void missingSharedSecretFailsClosed() throws Exception {
        var filter = new XianyuReplyRequestSignatureFilter("MYSHOP", "", CLOCK);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request(), response, (ignoredRequest, ignoredResponse) -> {
            throw new AssertionError("filter must reject while auth is not configured");
        });

        assertEquals(503, response.getStatus());
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(XianyuReplyRequestSignatureFilter.PATH);
        request.setMethod("POST");
        request.setContentType("application/json");
        request.setContent(BODY.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private void sign(MockHttpServletRequest request) {
        String timestamp = Long.toString(CLOCK.instant().getEpochSecond());
        String nonce = "nonce-1";
        String canonical = XianyuRequestSigner.canonicalString(
                "POST", XianyuReplyRequestSignatureFilter.PATH, timestamp, nonce,
                BODY.getBytes(StandardCharsets.UTF_8));
        request.addHeader("X-MyShop-Key-Id", "MYSHOP");
        request.addHeader("X-MyShop-Timestamp", timestamp);
        request.addHeader("X-MyShop-Nonce", nonce);
        request.addHeader("X-MyShop-Signature", XianyuRequestSigner.signature("phase-one-secret", canonical));
    }
}
