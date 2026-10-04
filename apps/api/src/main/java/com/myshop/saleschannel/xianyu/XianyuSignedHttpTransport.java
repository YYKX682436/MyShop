package com.myshop.saleschannel.xianyu;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Shared HMAC-authenticated HTTP transport for the Agent v1 and v2 clients. */
public final class XianyuSignedHttpTransport {

    public enum FailureBoundary { DEFINITE_PRE_SEND, MAY_HAVE_CROSSED_BOUNDARY }

    public record Response(int statusCode, byte[] body, String signature) { }

    public static final class TransportException extends Exception {
        private final FailureBoundary boundary;
        private final String code;

        private TransportException(FailureBoundary boundary, String code, String message) {
            super(message);
            this.boundary = boundary;
            this.code = code;
        }

        public FailureBoundary boundary() { return boundary; }
        public String code() { return code; }
    }

    private final String baseUrl;
    private final String apiRoot;
    private final String keyId;
    private final String secret;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final Clock clock;
    private final Supplier<String> nonceSupplier;

    public XianyuSignedHttpTransport(String baseUrl, String apiRoot, String keyId, String secret,
                                     long timeoutMillis, Clock clock, Supplier<String> nonceSupplier) {
        this.baseUrl = baseUrl;
        this.apiRoot = apiRoot;
        this.keyId = keyId;
        this.secret = secret;
        this.timeout = Duration.ofMillis(Math.max(250, Math.min(timeoutMillis, 60_000)));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.clock = Objects.requireNonNull(clock);
        this.nonceSupplier = Objects.requireNonNull(nonceSupplier);
    }

    public Response send(String method, String endpoint, byte[] rawBody, String contentType)
            throws TransportException {
        final URI uri;
        final String signature;
        final HttpRequest request;
        try {
            uri = configuredUri(endpoint);
            byte[] body = rawBody == null ? new byte[0] : rawBody;
            String timestamp = Long.toString(clock.instant().getEpochSecond());
            String nonce = nonceSupplier.get();
            String canonical = XianyuRequestSigner.canonicalString(
                    method, uri.getRawPath(), timestamp, nonce, body);
            signature = XianyuRequestSigner.signature(secret, canonical);
            HttpRequest.BodyPublisher publisher = method.equalsIgnoreCase("GET")
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body);
            request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .header("Content-Type", contentType == null ? "application/json" : contentType)
                    .header("X-MyShop-Key-Id", keyId)
                    .header("X-MyShop-Timestamp", timestamp)
                    .header("X-MyShop-Nonce", nonce)
                    .header("X-MyShop-Signature", signature)
                    .method(method.toUpperCase(java.util.Locale.ROOT), publisher)
                    .build();
        } catch (RuntimeException failure) {
            throw new TransportException(FailureBoundary.DEFINITE_PRE_SEND,
                    "REQUEST_BUILD_FAILED", "Unable to construct Xianyu Agent request");
        }

        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return new Response(response.statusCode(), response.body() == null ? new byte[0] : response.body(), signature);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new TransportException(FailureBoundary.MAY_HAVE_CROSSED_BOUNDARY,
                    "AGENT_REQUEST_INTERRUPTED", "Xianyu Agent request was interrupted");
        } catch (IOException | RuntimeException failure) {
            FailureBoundary boundary = definitelyBeforeConnection(failure)
                    ? FailureBoundary.DEFINITE_PRE_SEND
                    : FailureBoundary.MAY_HAVE_CROSSED_BOUNDARY;
            throw new TransportException(boundary,
                    boundary == FailureBoundary.DEFINITE_PRE_SEND ? "AGENT_UNREACHABLE" : "AGENT_UNAVAILABLE",
                    boundary == FailureBoundary.DEFINITE_PRE_SEND
                            ? "Xianyu Agent connection could not be established"
                            : "Xianyu Agent response could not be received");
        }
    }

    private URI configuredUri(String endpoint) throws TransportException {
        if (keyId == null || keyId.isBlank() || secret == null || secret.isBlank()
                || baseUrl == null || baseUrl.isBlank()) {
            throw new TransportException(FailureBoundary.DEFINITE_PRE_SEND,
                    "AGENT_CONFIGURATION_MISSING", "Xianyu Agent credentials or base URL are not configured");
        }
        try {
            URI base = URI.create(baseUrl.trim());
            if (!("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))
                    || base.getHost() == null || base.getUserInfo() != null
                    || base.getQuery() != null || base.getFragment() != null) {
                throw new IllegalArgumentException("invalid base URL");
            }
            String prefix = base.getRawPath() == null ? "" : base.getRawPath().replaceAll("/+$", "");
            String normalizedRoot = apiRoot.startsWith("/") ? apiRoot : "/" + apiRoot;
            normalizedRoot = normalizedRoot.replaceAll("/+$", "");
            String suffix = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
            return URI.create(base.getScheme() + "://" + base.getRawAuthority() + prefix + normalizedRoot + suffix);
        } catch (RuntimeException failure) {
            throw new TransportException(FailureBoundary.DEFINITE_PRE_SEND,
                    "AGENT_CONFIGURATION_INVALID", "Xianyu Agent base URL is invalid");
        }
    }

    private static boolean definitelyBeforeConnection(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof HttpConnectTimeoutException
                    || current instanceof ConnectException
                    || current instanceof UnknownHostException
                    || current instanceof UnresolvedAddressException) return true;
        }
        return false;
    }
}
