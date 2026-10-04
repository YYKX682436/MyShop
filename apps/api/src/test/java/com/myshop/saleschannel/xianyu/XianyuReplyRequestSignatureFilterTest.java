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


    @Test
    void wrongKeyExpiredFutureMissingAndOversizedNonceFailClosed() throws Exception {
        for (String[] change : new String[][] {
                {"X-MyShop-Key-Id", "OTHER"},
                {"X-MyShop-Timestamp", Long.toString(CLOCK.instant().getEpochSecond() - 301)},
                {"X-MyShop-Timestamp", Long.toString(CLOCK.instant().getEpochSecond() + 301)},
                {"X-MyShop-Timestamp", "9223372036854775807"},
                {"X-MyShop-Nonce", ""}, {"X-MyShop-Nonce", "n".repeat(129)}}) {
            var request = request();
            sign(request);
            request.removeHeader(change[0]);
            request.addHeader(change[0], change[1]);
            assertRejected(request, "phase-one-secret", 401);
        }
        var missing = request();
        sign(missing);
        missing.removeHeader("X-MyShop-Nonce");
        assertRejected(missing, "phase-one-secret", 401);
        assertRejected(request(), "   ", 503);
    }

    @Test
    void bodyLimitAndExactBoundaryAreEnforced() throws Exception {
        var oversized = request();
        oversized.setContent(new byte[256 * 1024 + 1]);
        assertRejected(oversized, "phase-one-secret", 413);
        var boundary = request();
        byte[] body = new byte[256 * 1024];
        boundary.setContent(body);
        sign(boundary, body, "nonce-1", CLOCK.instant().getEpochSecond());
        new XianyuReplyRequestSignatureFilter("MYSHOP", "phase-one-secret", CLOCK)
                .doFilter(boundary, new MockHttpServletResponse(), (wrapped, response) ->
                        assertEquals(body.length, wrapped.getInputStream().readAllBytes().length));
    }

    @Test
    void contextPathReplayAndRepeatedBodyReadsAreSafeForReadOnlyResolution() throws Exception {
        var filter = new XianyuReplyRequestSignatureFilter("MYSHOP", "phase-one-secret", CLOCK);
        for (int i = 0; i < 2; i++) {
            var request = request();
            request.setContextPath("/shop");
            request.setRequestURI("/shop" + XianyuReplyRequestSignatureFilter.PATH);
            sign(request);
            var called = new boolean[1];
            filter.doFilter(request, new MockHttpServletResponse(), (wrapped, response) -> {
                called[0] = true;
                assertEquals(BODY, new String(wrapped.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                assertEquals(BODY, new String(wrapped.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                assertEquals(BODY, wrapped.getReader().readLine());
            });
            assertTrue(called[0]);
        }
    }

    @Test
    void nonTargetPathsAndGetBypassOnlyThisFilter() throws Exception {
        for (String[] target : new String[][] {{"GET", XianyuReplyRequestSignatureFilter.PATH},
                {"POST", "/internal/other"}, {"POST", XianyuReplyRequestSignatureFilter.PATH + "/"}}) {
            var request = request();
            request.setMethod(target[0]);
            request.setRequestURI(target[1]);
            var called = new boolean[1];
            new XianyuReplyRequestSignatureFilter("MYSHOP", "", CLOCK)
                    .doFilter(request, new MockHttpServletResponse(), (req, res) -> called[0] = true);
            assertTrue(called[0]);
        }
    }

    @Test
    void rawBodyTamperingAndMissingSignatureAreRejected() throws Exception {
        var request = request();
        sign(request);
        request.setContent((BODY + " ").getBytes(StandardCharsets.UTF_8));
        assertRejected(request, "phase-one-secret", 401);
        request = request();
        sign(request);
        request.removeHeader("X-MyShop-Signature");
        assertRejected(request, "phase-one-secret", 401);
    }

    @Test
    void clockSkewInclusiveBoundariesAreAccepted() throws Exception {
        for (long delta : new long[] {-300, 300}) {
            var request = request();
            sign(request, BODY.getBytes(StandardCharsets.UTF_8), "nonce-1", CLOCK.instant().getEpochSecond() + delta);
            var called = new boolean[1];
            new XianyuReplyRequestSignatureFilter("MYSHOP", "phase-one-secret", CLOCK)
                    .doFilter(request, new MockHttpServletResponse(), (req, res) -> called[0] = true);
            assertTrue(called[0]);
        }
    }

    private void assertRejected(MockHttpServletRequest request, String secret, int status) throws Exception {
        var response = new MockHttpServletResponse();
        new XianyuReplyRequestSignatureFilter("MYSHOP", secret, CLOCK)
                .doFilter(request, response, (req, res) -> { throw new AssertionError("must reject"); });
        assertEquals(status, response.getStatus());
    }

    private void sign(MockHttpServletRequest request, byte[] body, String nonce, long seconds) {
        String timestamp = Long.toString(seconds);
        request.addHeader("X-MyShop-Key-Id", "MYSHOP");
        request.addHeader("X-MyShop-Timestamp", timestamp);
        request.addHeader("X-MyShop-Nonce", nonce);
        request.addHeader("X-MyShop-Signature", XianyuRequestSigner.signature("phase-one-secret",
                XianyuRequestSigner.canonicalString("POST", XianyuReplyRequestSignatureFilter.PATH,
                        timestamp, nonce, body)));
    }


    @Test
    void encodedTargetPathsCannotBypassSignatureAuthentication() throws Exception {
        var request = request();
        request.setRequestURI("/internal/xianyu/reply/%72esolve");
        assertRejected(request, "phase-one-secret", 401);
        request.setServletPath(XianyuReplyRequestSignatureFilter.PATH);
        assertRejected(request, "phase-one-secret", 401);
        request = request();
        request.setRequestURI("/internal/xianyu/reply/%72esolve");
        request.setServletPath(XianyuReplyRequestSignatureFilter.PATH);
        sign(request);
        var called = new boolean[1];
        new XianyuReplyRequestSignatureFilter("MYSHOP", "phase-one-secret", CLOCK)
                .doFilter(request, new MockHttpServletResponse(), (req, res) -> called[0] = true);
        assertTrue(called[0]);
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
