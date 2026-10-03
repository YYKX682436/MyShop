package com.myshop.saleschannel.xianyu;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** HMAC gate for the read-only Agent message-reply resolution endpoint. */
@Component
public class XianyuReplyRequestSignatureFilter extends OncePerRequestFilter {

    static final String PATH = "/internal/xianyu/reply/resolve";
    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);
    private static final int MAX_BODY_BYTES = 256 * 1024;

    private final String expectedKeyId;
    private final String secret;
    private final Clock clock;

    public XianyuReplyRequestSignatureFilter(
            @Value("${xianyu.agent.key-id:MYSHOP}") String expectedKeyId,
            @Value("${xianyu.agent.secret:}") String secret) {
        this(expectedKeyId, secret, Clock.systemUTC());
    }

    XianyuReplyRequestSignatureFilter(String expectedKeyId, String secret, Clock clock) {
        this.expectedKeyId = expectedKeyId == null ? "" : expectedKeyId;
        this.secret = secret == null ? "" : secret;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !PATH.equals(pathWithoutContext(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (secret.isBlank() || expectedKeyId.isBlank()) {
            reject(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "INTEGRATION_AUTH_NOT_CONFIGURED");
            return;
        }

        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            reject(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "INTEGRATION_BODY_TOO_LARGE");
            return;
        }
        String keyId = request.getHeader("X-MyShop-Key-Id");
        String timestamp = request.getHeader("X-MyShop-Timestamp");
        String nonce = request.getHeader("X-MyShop-Nonce");
        String suppliedSignature = request.getHeader("X-MyShop-Signature");
        if (!expectedKeyId.equals(keyId) || !validTimestamp(timestamp) || nonce == null
                || nonce.isBlank() || nonce.length() > 128 || suppliedSignature == null) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "INVALID_INTEGRATION_SIGNATURE");
            return;
        }

        String canonical = XianyuRequestSigner.canonicalString("POST", PATH, timestamp, nonce, body);
        byte[] expectedSignature = XianyuRequestSigner.signature(secret, canonical)
                .getBytes(StandardCharsets.US_ASCII);
        byte[] actualSignature = suppliedSignature.toLowerCase(java.util.Locale.ROOT)
                .getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expectedSignature, actualSignature)) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "INVALID_INTEGRATION_SIGNATURE");
            return;
        }

        // This endpoint resolves a message into an action; it never sends
        // messages or mutates business state. Agent must dedupe execution by
        // message_id before performing any later provider-side action.
        filterChain.doFilter(new ReplayableBodyRequest(request, body), response);
    }

    private boolean validTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) return false;
        try {
            Instant supplied = Instant.ofEpochSecond(Long.parseLong(timestamp));
            return Duration.between(clock.instant(), supplied).abs().compareTo(MAX_CLOCK_SKEW) <= 0;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static String pathWithoutContext(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return context != null && !context.isEmpty() && uri.startsWith(context)
                ? uri.substring(context.length()) : uri;
    }

    private static void reject(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"" + code + "\"}");
    }

    private static final class ReplayableBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private ReplayableBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body.clone();
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException("Synchronous request body only");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            java.nio.charset.Charset charset = getCharacterEncoding() == null
                    ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(getCharacterEncoding());
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }
}
