package com.myshop.saleschannel.xianyu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.saleschannel.SalesChannelProviderClientException;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.EnsureListingRequest;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.OperationState;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.DesiredListingState;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class XianyuConvergenceHttpClientTest {

    private static final String SECRET = "unit-test-only-secret";
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(1_800_000_000L), ZoneOffset.UTC);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void ensureUsesExactV2PathAndTheSharedHmacSigner() throws Exception {
        var reply = new AtomicReference<>(new Reply(202, observation("APPLYING", request().requestId(), null)));
        try (Fixture fixture = new Fixture(reply, 0)) {
            var client = client(fixture.baseUrl(), 2_000);
            var observation = client.ensureListing(request());
            assertEquals(OperationState.APPLYING, observation.state());
            assertEquals(request().requestId(), observation.requestId());

            Captured request = fixture.requests().getFirst();
            assertEquals("POST", request.method());
            assertEquals("/api/integration/v2/listings/ensure", request.path());
            JsonNode body = objectMapper.readTree(request.body());
            assertEquals(1, body.path("desired_snapshot").path("schema_version").asInt());
            assertEquals("listing-1", body.path("desired_snapshot").path("listing_key").asText());
            assertSigned(request);
        }
    }

    @Test
    void observeSignsGetWithTheHashOfEmptyBytes() throws Exception {
        String requestId = "a.b_c~d-e";
        var reply = new AtomicReference<>(new Reply(200, observation("SUCCEEDED", requestId, "item-1")));
        try (Fixture fixture = new Fixture(reply, 0)) {
            var client = client(fixture.baseUrl(), 2_000);
            var observation = client.observeOperation(requestId);
            assertEquals(OperationState.SUCCEEDED, observation.state());
            Captured request = fixture.requests().getFirst();
            assertEquals("GET", request.method());
            assertEquals("/api/integration/v2/operations/" + requestId, request.path());
            assertArrayEquals(new byte[0], request.body());
            assertSigned(request);
        }
    }

    @Test
    void definitePreSendConnectionFailureIsTypedRetryable() {
        var client = client("http://127.0.0.1:1", 500);
        var failure = assertThrows(SalesChannelProviderClientException.class,
                () -> client.ensureListing(request()));
        assertEquals(SalesChannelProviderClientException.Disposition.SAFE_RETRYABLE, failure.getDisposition());
        assertNotEquals(SalesChannelProviderClientException.Disposition.UNKNOWN, failure.getDisposition());
    }

    @Test
    void postTimeoutReturnsApplyingForLaterObservation() throws Exception {
        var reply = new AtomicReference<>(new Reply(200, observation("SUCCEEDED", request().requestId(), "item-1")));
        try (Fixture fixture = new Fixture(reply, 900)) {
            var client = client(fixture.baseUrl(), 250);
            var result = client.ensureListing(request());
            assertEquals(OperationState.APPLYING, result.state());
            assertEquals(request().requestId(), result.requestId());
            assertEquals("AGENT_RESPONSE_UNCONFIRMED", result.error().code());
        }
    }

    @Test
    void definiteAgentRejectionIsFailedAndInternalUnknownIsNeverAcceptedOnWire() throws Exception {
        String rejection = "{\"request_id\":null,\"outcome\":\"FAILED\",\"data\":null,"
                + "\"error\":{\"code\":\"VALIDATION_ERROR\",\"message\":\"invalid\",\"retryable\":false}}";
        var reply = new AtomicReference<>(new Reply(422, rejection.getBytes(StandardCharsets.UTF_8)));
        try (Fixture fixture = new Fixture(reply, 0)) {
            var response = client(fixture.baseUrl(), 2_000).ensureListing(request());
            assertEquals(OperationState.FAILED, response.state());
            assertEquals("VALIDATION_ERROR", response.error().code());
        }

        reply.set(new Reply(200, observation("UNKNOWN", request().requestId(), null)));
        try (Fixture fixture = new Fixture(reply, 0)) {
            var response = client(fixture.baseUrl(), 2_000).ensureListing(request());
            assertEquals(OperationState.APPLYING, response.state());
            assertNotEquals("UNKNOWN", response.state().name());
        }
    }

    @Test
    void operationRequestIdMismatchFailsClosed() throws Exception {
        var reply = new AtomicReference<>(new Reply(200, observation("SUCCEEDED", "different-command", "item-1")));
        try (Fixture fixture = new Fixture(reply, 0)) {
            var failure = assertThrows(SalesChannelProviderClientException.class,
                    () -> client(fixture.baseUrl(), 2_000).ensureListing(request()));
            assertEquals(SalesChannelProviderClientException.Disposition.SAFE_PERMANENT, failure.getDisposition());
            assertEquals("AGENT_REQUEST_ID_MISMATCH", failure.getErrorCode());
        }
    }

    @Test
    void observeNotFoundIsNotReinterpretedAsFailedOperation() throws Exception {
        var reply = new AtomicReference<>(new Reply(404,
                "{\"request_id\":\"missing\",\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"missing\"}}"
                        .getBytes(StandardCharsets.UTF_8)));
        try (Fixture fixture = new Fixture(reply, 0)) {
            var failure = assertThrows(SalesChannelProviderClientException.class,
                    () -> client(fixture.baseUrl(), 2_000).observeOperation("missing"));
            assertEquals("NOT_FOUND", failure.getErrorCode());
            assertEquals(SalesChannelProviderClientException.Disposition.SAFE_PERMANENT, failure.getDisposition());
        }
    }

    @Test
    void observeRejectsRequestIdsOutsideTheFrozenPathAlphabetBeforeSending() throws Exception {
        for (String invalid : List.of(" leading", "trailing ", "a/b", "a:b", "a%b", "商品", "x".repeat(256))) {
            try (Fixture fixture = new Fixture(new AtomicReference<>(new Reply(200,
                    observation("SUCCEEDED", "unused", "item-1"))), 0)) {
                var failure = assertThrows(SalesChannelProviderClientException.class,
                        () -> client(fixture.baseUrl(), 2_000).observeOperation(invalid));
                assertEquals("INVALID_REQUEST_ID", failure.getErrorCode(), invalid);
                assertTrue(fixture.requests().isEmpty(), invalid);
            }
        }
    }

    @Test
    void validWireStatesAreStrictlyAcceptedAndMalformedTypedObservationsFailClosed() throws Exception {
        for (String state : List.of("APPLYING", "SUCCEEDED", "FAILED")) {
            var root = objectMapper.readTree(observation(state, request().requestId(), null));
            if (state.equals("FAILED")) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) root).set("error",
                        objectMapper.valueToTree(Map.of("code", "PROVIDER_FAILED", "message", "known failure")));
            }
            var reply = new AtomicReference<>(new Reply(200, objectMapper.writeValueAsBytes(root)));
            try (Fixture fixture = new Fixture(reply, 0)) {
                assertEquals(OperationState.valueOf(state), client(fixture.baseUrl(), 2_000)
                        .ensureListing(request()).state());
            }
        }

        for (java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode> invalid : List.<java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode>>of(
                node -> node.put("request_id", "x".repeat(256)),
                node -> node.put("listing_key", "x".repeat(129)),
                node -> node.put("external_item_id", "x".repeat(129)),
                node -> node.set("error", objectMapper.valueToTree(Map.of("code", "", "message", "m"))),
                node -> node.set("error", objectMapper.valueToTree(Map.of("code", "x".repeat(129), "message", "m"))),
                node -> node.set("error", objectMapper.valueToTree(Map.of("code", "E", "message", ""))),
                node -> node.set("error", objectMapper.valueToTree(Map.of("code", "E", "message", "x".repeat(1001)))),
                node -> node.put("revision", -1),
                node -> node.put("unexpected", true))) {
            var node = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(
                    observation("APPLYING", request().requestId(), null));
            invalid.accept(node);
            byte[] malformed = objectMapper.writeValueAsBytes(node);
            var reply = new AtomicReference<>(new Reply(200, malformed));
            try (Fixture fixture = new Fixture(reply, 0)) {
                var ensure = client(fixture.baseUrl(), 2_000).ensureListing(request());
                assertEquals(OperationState.APPLYING, ensure.state());
                assertEquals("AGENT_RESPONSE_UNREADABLE", ensure.error().code());
            }
            try (Fixture fixture = new Fixture(reply, 0)) {
                var failure = assertThrows(SalesChannelProviderClientException.class,
                        () -> client(fixture.baseUrl(), 2_000).observeOperation(request().requestId()));
                assertEquals(SalesChannelProviderClientException.Disposition.SAFE_RETRYABLE, failure.getDisposition());
                assertEquals("AGENT_RESPONSE_INVALID", failure.getErrorCode());
            }
        }
    }

    private XianyuConvergenceHttpClient client(String baseUrl, long timeoutMillis) {
        var transport = new XianyuSignedHttpTransport(baseUrl, "/api/integration/v2", "test-key", SECRET,
                timeoutMillis, CLOCK, () -> "nonce-v2-test-00000001");
        return new XianyuConvergenceHttpClient(objectMapper, transport);
    }

    private EnsureListingRequest request() throws IOException {
        try (var input = getClass().getResourceAsStream("/contracts/ensure_listing_v2.json")) {
            return objectMapper.readValue(input, EnsureListingRequest.class);
        }
    }

    private byte[] observation(String state, String requestId, String itemId) throws IOException {
        var observation = objectMapper.createObjectNode();
        observation.put("request_id", requestId);
        observation.put("listing_key", "listing-1");
        observation.put("revision", 7);
        observation.put("state", state);
        if (itemId == null) observation.putNull("external_item_id");
        else observation.put("external_item_id", itemId);
        if (state.equals("APPLYING")) {
            observation.set("error", objectMapper.valueToTree(Map.of("code", "IN_PROGRESS", "message", "in progress")));
        } else {
            observation.putNull("error");
        }
        return objectMapper.writeValueAsBytes(observation);
    }

    private void assertSigned(Captured request) {
        String canonical = XianyuRequestSigner.canonicalString(request.method(), request.path(),
                request.header("X-MyShop-Timestamp"), request.header("X-MyShop-Nonce"), request.body());
        assertEquals(XianyuRequestSigner.signature(SECRET, canonical), request.header("X-MyShop-Signature"));
        assertEquals("test-key", request.header("X-MyShop-Key-Id"));
    }

    private record Reply(int statusCode, byte[] body) {
        Reply(int statusCode, String body) { this(statusCode, body.getBytes(StandardCharsets.UTF_8)); }
    }

    private record Captured(String method, String path, byte[] body, Map<String, String> headers) {
        String header(String name) { return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue).findFirst().orElse(null); }
    }

    private static final class Fixture implements AutoCloseable {
        private final HttpServer server;
        private final AtomicReference<Reply> reply;
        private final int delayMillis;
        private final List<Captured> requests = new CopyOnWriteArrayList<>();

        Fixture(AtomicReference<Reply> reply, int delayMillis) throws IOException {
            this.reply = reply;
            this.delayMillis = delayMillis;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        List<Captured> requests() { return List.copyOf(requests); }

        private void handle(HttpExchange exchange) throws IOException {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            Map<String, String> headers = new java.util.HashMap<>();
            exchange.getRequestHeaders().forEach((key, values) -> {
                if (!values.isEmpty()) headers.put(key.toLowerCase(java.util.Locale.ROOT), values.getFirst());
            });
            requests.add(new Captured(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(), requestBody, headers));
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            Reply current = reply.get();
            try {
                exchange.sendResponseHeaders(current.statusCode(), current.body().length);
                exchange.getResponseBody().write(current.body());
            } catch (IOException clientGone) {
                // The timeout test intentionally closes the client side first.
            } finally {
                exchange.close();
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
