package com.myshop.saleschannel.xianyu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.saleschannel.SalesChannelProviderClientException;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.EnsureListingRequest;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.RecoveryArmRequest;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.RecoveryPrepareRequest;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.RecoveryValidationRequest;
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
import java.util.function.Consumer;

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
    void offlineEnsureSerializesAnExplicitNullDesiredSnapshot() throws Exception {
        EnsureListingRequest online = request();
        EnsureListingRequest offline = new EnsureListingRequest(online.requestId(), online.listingKey(),
                online.accountId(), online.revision(), DesiredListingState.OFFLINE, null);
        var reply = new AtomicReference<>(new Reply(202, observation("APPLYING", offline.requestId(), null)));
        try (Fixture fixture = new Fixture(reply, 0)) {
            var result = client(fixture.baseUrl(), 2_000).ensureListing(offline);
            assertEquals(OperationState.APPLYING, result.state());
            Captured sent = fixture.requests().getFirst();
            JsonNode body = objectMapper.readTree(sent.body());
            assertEquals("OFFLINE", body.path("desired_state").asText());
            assertTrue(body.has("desired_snapshot"));
            assertTrue(body.path("desired_snapshot").isNull());
            assertSigned(sent);
        }
    }

    @Test
    void adoptionUsesExactV2PathAndSharedHmacSigner() throws Exception {
        var adoption = new com.myshop.saleschannel.convergence.XianyuConvergenceContracts.AdoptExistingItemRequest(
                "adopt-listing-1-digest", "listing-1", "account-123", "item-123");
        var response = objectMapper.createObjectNode();
        response.put("request_id", adoption.requestId());
        response.put("listing_key", adoption.listingKey());
        response.put("state", "SUCCEEDED");
        response.put("external_item_id", adoption.externalItemId());
        response.putNull("error");
        var reply = new AtomicReference<>(new Reply(200, objectMapper.writeValueAsBytes(response)));
        try (Fixture fixture = new Fixture(reply, 0)) {
            var result = client(fixture.baseUrl(), 2_000).adoptExistingItem(adoption);
            assertEquals(OperationState.SUCCEEDED, result.state());
            assertEquals(adoption.externalItemId(), result.externalItemId());
            Captured sent = fixture.requests().getFirst();
            assertEquals("POST", sent.method());
            assertEquals("/api/integration/v2/listings/adopt", sent.path());
            JsonNode body = objectMapper.readTree(sent.body());
            assertEquals(adoption.requestId(), body.path("request_id").asText());
            assertEquals(adoption.listingKey(), body.path("listing_key").asText());
            assertEquals(adoption.accountId(), body.path("account_id").asText());
            assertEquals(adoption.externalItemId(), body.path("external_item_id").asText());
            assertSigned(sent);
        }
    }

    @Test
    void adoptionApplyingAndLegacyReplayRequiredFailureRemainReplayable() throws Exception {
        var adoption = new com.myshop.saleschannel.convergence.XianyuConvergenceContracts.AdoptExistingItemRequest(
                "adopt-replayable-1", "listing-1", "account-123", "item-123");
        var applying = objectMapper.createObjectNode();
        applying.put("request_id", adoption.requestId());
        applying.put("listing_key", adoption.listingKey());
        applying.put("state", "APPLYING");
        applying.putNull("external_item_id");
        applying.putNull("error");
        var reply = new AtomicReference<>(new Reply(202, objectMapper.writeValueAsBytes(applying)));
        try (Fixture fixture = new Fixture(reply, 0)) {
            var client = client(fixture.baseUrl(), 2_000);
            assertEquals(OperationState.APPLYING, client.adoptExistingItem(adoption).state());

            var legacy = objectMapper.createObjectNode();
            legacy.put("request_id", adoption.requestId());
            legacy.put("listing_key", adoption.listingKey());
            legacy.put("state", "FAILED");
            legacy.putNull("external_item_id");
            legacy.putObject("error").put("code", "ADOPTION_STATE_REPLAY_REQUIRED")
                    .put("message", "replay exact DB state");
            reply.set(new Reply(503, objectMapper.writeValueAsBytes(legacy)));
            assertEquals(OperationState.APPLYING, client.adoptExistingItem(adoption).state());
            assertEquals(2, fixture.requests().size());
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

    @Test
    void readinessUsesSignedGetAndClosedGateOnlyContract() throws Exception {
        var reply = new AtomicReference<>(new Reply(200,
                "{\"schema_version\":1,\"v2_writes_enabled\":false,\"v2_adoption_enabled\":true}"));
        try (Fixture fixture = new Fixture(reply, 0)) {
            var readiness = client(fixture.baseUrl(), 2_000).agentReadiness();
            assertEquals(1, readiness.schemaVersion());
            assertFalse(readiness.v2WritesEnabled());
            assertTrue(readiness.v2AdoptionEnabled());
            Captured sent = fixture.requests().getFirst();
            assertEquals("GET", sent.method());
            assertEquals("/api/integration/v2/readiness", sent.path());
            assertArrayEquals(new byte[0], sent.body());
            assertSigned(sent);
        }
        reply.set(new Reply(200,
                "{\"schema_version\":1,\"v2_writes_enabled\":false,\"v2_adoption_enabled\":false,\"secret\":\"no\"}"));
        try (Fixture fixture = new Fixture(reply, 0)) {
            var failure = assertThrows(SalesChannelProviderClientException.class,
                    () -> client(fixture.baseUrl(), 2_000).agentReadiness());
            assertEquals(SalesChannelProviderClientException.Disposition.SAFE_RETRYABLE, failure.getDisposition());
        }
    }

    @Test
    void listingInspectionUsesSignedStructuredPostAndRejectsUnknownOrChangedIdentity() throws Exception {
        var request = new com.myshop.saleschannel.convergence.XianyuConvergenceContracts.CutoverInspectionRequest(
                "inspection-1", "account-1", "listing-1", "item-1");
        var root = objectMapper.createObjectNode();
        root.put("schema_version", 1);
        root.put("request_id", request.requestId());
        root.put("account_id", request.accountId());
        root.put("listing_key", request.listingKey());
        root.put("expected_external_item_id", request.expectedExternalItemId());
        root.put("v2_writes_enabled", false);
        root.put("v2_adoption_enabled", false);
        root.putNull("managed_item");
        root.putArray("bindings");
        root.putNull("lifecycle_head");
        root.putArray("nonterminal_operations");
        root.putObject("provider_observation").put("external_item_id", "item-1")
                .put("status", "ACTIVE").put("complete", true).put("observed_at", "2026-10-05T00:00:00Z");
        root.putNull("provider_observation_error_code");
        root.put("inspection_digest", "a".repeat(64));
        var reply = new AtomicReference<>(new Reply(200, objectMapper.writeValueAsBytes(root)));
        try (Fixture fixture = new Fixture(reply, 0)) {
            var inspection = client(fixture.baseUrl(), 2_000).inspectListing(request);
            assertEquals(request.requestId(), inspection.requestId());
            assertEquals("item-1", inspection.providerObservation().externalItemId());
            Captured sent = fixture.requests().getFirst();
            assertEquals("POST", sent.method());
            assertEquals("/api/integration/v2/listings/inspect", sent.path());
            JsonNode body = objectMapper.readTree(sent.body());
            assertEquals("account-1", body.path("account_id").asText());
            assertEquals("listing-1", body.path("listing_key").asText());
            assertEquals("item-1", body.path("expected_external_item_id").asText());
            assertSigned(sent);
        }

        root.put("extra", true);
        reply.set(new Reply(200, objectMapper.writeValueAsBytes(root)));
        try (Fixture fixture = new Fixture(reply, 0)) {
            var failure = assertThrows(SalesChannelProviderClientException.class,
                    () -> client(fixture.baseUrl(), 2_000).inspectListing(request));
            assertEquals(SalesChannelProviderClientException.Disposition.SAFE_RETRYABLE, failure.getDisposition());
        }

        root.remove("extra");
        root.put("listing_key", "another-listing");
        reply.set(new Reply(200, objectMapper.writeValueAsBytes(root)));
        try (Fixture fixture = new Fixture(reply, 0)) {
            var failure = assertThrows(SalesChannelProviderClientException.class,
                    () -> client(fixture.baseUrl(), 2_000).inspectListing(request));
            assertEquals("AGENT_INSPECTION_RESPONSE_INVALID", failure.getErrorCode());
        }
    }

    @Test
    void cancelledRecoveryPermitUsesTheAgentTwentyTwoFieldResponseContract() throws Exception {
        byte[] agentPermitPayload;
        try (var input = getClass().getResourceAsStream(
                "/contracts/recovery_permit_cancelled_agent_v2.json")) {
            assertNotNull(input);
            agentPermitPayload = input.readAllBytes();
        }
        assertEquals(22, objectMapper.readTree(agentPermitPayload).size());
        var reply = new AtomicReference<>(new Reply(200, agentPermitPayload));
        try (Fixture fixture = new Fixture(reply, 0)) {
            var observation = client(fixture.baseUrl(), 2_000)
                    .observeRecovery("b2c7bf6f-6ac9-4366-9d45-0fe2cb93d210");
            assertEquals("CANCELLED", observation.state());
            assertEquals(1L, observation.generation());
            assertEquals("CANCELLED", observation.executionOutcome());
            assertNull(observation.activatedAt());
            assertNull(observation.executionStartedAt());
            assertNull(observation.terminalAt());
            assertEquals(0L, observation.executionFence());
            assertEquals(0L, observation.myshopExecutionFence());
            Captured sent = fixture.requests().getFirst();
            assertEquals("GET", sent.method());
            assertEquals("/api/integration/v2/recovery/permits/b2c7bf6f-6ac9-4366-9d45-0fe2cb93d210",
                    sent.path());
            assertSigned(sent);
        }
    }

    @Test
    void allAgentRecoveryPermitStatesParseFromTheSignedGetEndpoint() throws Exception {
        for (String state : List.of("PREPARED", "ARMED", "ACTIVE", "EXECUTING",
                "RECOVERY_REQUIRED", "TERMINAL", "CANCELLED")) {
            byte[] payload = permitPayload(state);
            var reply = new AtomicReference<>(new Reply(200, payload));
            try (Fixture fixture = new Fixture(reply, 0)) {
                var observation = client(fixture.baseUrl(), 2_000)
                        .observeRecovery("b2c7bf6f-6ac9-4366-9d45-0fe2cb93d210");
                assertEquals(state, observation.state());
                assertEquals(1L, observation.generation());
                assertEquals("2215747465381", observation.accountId());
                assertEquals("c827bfa4-abfe-47f9-8d60-f2d1c6972fcd", observation.listingKey());
                assertEquals("7369460a-45a7-49d0-9cf5-85d98eff4199", observation.myshopOperationId());
                assertEquals(15L, observation.agentOperationId());
                assertEquals("0153b2a7-fbd7-48b8-b52c-0ea2379ddf26", observation.requestId());
                assertEquals(10L, observation.desiredRevision());
                assertEquals("def5d3cbd69714b2cd8b3e87e5fc472631e46150b7bcd2c778a23f59b7989446",
                        observation.desiredFingerprint());
                assertEquals("1084013851108", observation.expectedOldItemId());
                assertNotNull(observation.preconditions());
                assertNotNull(observation.operator());
                assertNotNull(observation.reason());
                var roundTrip = objectMapper.valueToTree(observation);
                var expectedFields = new java.util.HashSet<String>();
                var expectedFieldNames = permitPayloadNode(state).fieldNames();
                expectedFieldNames.forEachRemaining(expectedFields::add);
                var actualFields = new java.util.HashSet<String>();
                roundTrip.fieldNames().forEachRemaining(actualFields::add);
                assertEquals(expectedFields, actualFields,
                        "Java RecoveryPermitObservation must map the complete Agent response model");
                if ("CANCELLED".equals(state)) {
                    assertEquals("CANCELLED", observation.executionOutcome());
                    assertNull(observation.activatedAt());
                    assertNull(observation.executionStartedAt());
                    assertNull(observation.terminalAt());
                    assertEquals(0L, observation.executionFence());
                    assertEquals(0L, observation.myshopExecutionFence());
                }
                assertSigned(fixture.requests().getFirst());
            }
        }
    }

    @Test
    void everyRecoveryPermitMutationEndpointParsesTheAgentTwentyTwoFieldResponse() throws Exception {
        List<RecoveryMutation> mutations = List.of(
                new RecoveryMutation("/recovery/permits/prepare", "PREPARED",
                        client -> client.prepareRecovery(prepareRequest())),
                new RecoveryMutation("/recovery/permits/arm", "ARMED",
                        client -> client.armRecovery(armRequest())),
                new RecoveryMutation("/recovery/permits/activate", "ACTIVE",
                        client -> client.activateRecovery(armRequest())),
                new RecoveryMutation("/recovery/permits/cancel", "CANCELLED",
                        client -> client.cancelRecovery(armRequest())));
        for (RecoveryMutation mutation : mutations) {
            var reply = new AtomicReference<>(new Reply(200, permitPayload(mutation.state())));
            try (Fixture fixture = new Fixture(reply, 0)) {
                var observation = mutation.call().apply(client(fixture.baseUrl(), 2_000));
                assertEquals(mutation.state(), observation.state());
                Captured sent = fixture.requests().getFirst();
                assertEquals("POST", sent.method());
                assertEquals("/api/integration/v2" + mutation.path(), sent.path());
                assertSigned(sent);
            }
        }
    }

    @Test
    void recoveryPermitValidationUsesTheAgentFiveFieldResponseOverHttp() throws Exception {
        var response = objectMapper.createObjectNode();
        response.put("eligible", true);
        response.put("agent_operation_id", 15);
        response.put("request_id", "0153b2a7-fbd7-48b8-b52c-0ea2379ddf26");
        response.put("command_fingerprint", "a".repeat(64));
        response.putObject("preconditions").put("provider_attempt_count", 0);
        var reply = new AtomicReference<>(new Reply(200, objectMapper.writeValueAsBytes(response)));
        try (Fixture fixture = new Fixture(reply, 0)) {
            var result = client(fixture.baseUrl(), 2_000).validateRecovery(validationRequest());
            assertTrue(result.eligible());
            assertEquals(15L, result.agentOperationId());
            Captured sent = fixture.requests().getFirst();
            assertEquals("POST", sent.method());
            assertEquals("/api/integration/v2/recovery/validate", sent.path());
            assertSigned(sent);
        }
    }

    @Test
    void malformedRecoveryPermitContractFailsPermanentlyOverHttp() throws Exception {
        List<Consumer<com.fasterxml.jackson.databind.node.ObjectNode>> mutations = List.of(
                node -> node.remove("generation"),
                node -> node.put("undeclared_field", true),
                node -> node.put("generation", "1"),
                node -> node.put("execution_fence", 0.5),
                node -> node.put("prepared_at", 123),
                node -> node.put("preconditions", "not-an-object"));
        for (Consumer<com.fasterxml.jackson.databind.node.ObjectNode> mutation : mutations) {
            var root = permitPayloadNode("CANCELLED");
            mutation.accept(root);
            var reply = new AtomicReference<>(new Reply(200, objectMapper.writeValueAsBytes(root)));
            try (Fixture fixture = new Fixture(reply, 0)) {
                var failure = assertThrows(SalesChannelProviderClientException.class,
                        () -> client(fixture.baseUrl(), 2_000)
                                .observeRecovery("b2c7bf6f-6ac9-4366-9d45-0fe2cb93d210"));
                assertEquals("RECOVERY_PERMIT_RESPONSE_INVALID", failure.getErrorCode());
                assertEquals(SalesChannelProviderClientException.Disposition.SAFE_PERMANENT,
                        failure.getDisposition());
            }
        }
    }

    @Test
    void agentPermitBusinessRejectionsKeepTheirOwnSafeErrorCode() throws Exception {
        var reply = new AtomicReference<>(new Reply(409,
                "{\"error\":{\"code\":\"RECOVERY_PERMIT_STATE_MISMATCH\"}}"));
        try (Fixture fixture = new Fixture(reply, 0)) {
            var failure = assertThrows(SalesChannelProviderClientException.class,
                    () -> client(fixture.baseUrl(), 2_000)
                            .observeRecovery("b2c7bf6f-6ac9-4366-9d45-0fe2cb93d210"));
            assertEquals("RECOVERY_PERMIT_STATE_MISMATCH", failure.getErrorCode());
            assertEquals(SalesChannelProviderClientException.Disposition.SAFE_PERMANENT,
                    failure.getDisposition());
        }
    }

    @Test
    void recoveryMutationResponsesCannotChangePermitGenerationOrCommandScope() throws Exception {
        List<Consumer<com.fasterxml.jackson.databind.node.ObjectNode>> mutations = List.of(
                node -> node.put("generation", 2),
                node -> node.put("request_id", "different-request"),
                node -> node.put("account_id", "different-account"),
                node -> node.put("listing_key", "different-listing"),
                node -> node.put("myshop_operation_id", "00000000-0000-0000-0000-000000000000"),
                node -> node.put("agent_operation_id", 16),
                node -> node.put("desired_revision", 11),
                node -> node.put("expected_old_item_id", "different-item"),
                node -> node.put("desired_fingerprint", "b".repeat(64)));
        for (Consumer<com.fasterxml.jackson.databind.node.ObjectNode> mutation : mutations) {
            var root = permitPayloadNode("PREPARED");
            mutation.accept(root);
            var reply = new AtomicReference<>(new Reply(200, objectMapper.writeValueAsBytes(root)));
            try (Fixture fixture = new Fixture(reply, 0)) {
                var failure = assertThrows(SalesChannelProviderClientException.class,
                        () -> client(fixture.baseUrl(), 2_000).prepareRecovery(prepareRequest()));
                assertEquals("AGENT_RECOVERY_PERMIT_IDENTITY_MISMATCH", failure.getErrorCode());
                assertEquals(SalesChannelProviderClientException.Disposition.SAFE_PERMANENT,
                        failure.getDisposition());
            }
        }

        var wrongPermit = permitPayloadNode("PREPARED");
        wrongPermit.put("permit_id", "00000000-0000-0000-0000-000000000000");
        var reply = new AtomicReference<>(new Reply(200, objectMapper.writeValueAsBytes(wrongPermit)));
        try (Fixture fixture = new Fixture(reply, 0)) {
            var failure = assertThrows(SalesChannelProviderClientException.class,
                    () -> client(fixture.baseUrl(), 2_000).prepareRecovery(prepareRequest()));
            assertEquals("RECOVERY_PERMIT_IDENTITY_MISMATCH", failure.getErrorCode());
            assertEquals(SalesChannelProviderClientException.Disposition.SAFE_PERMANENT,
                    failure.getDisposition());
        }
    }

    @FunctionalInterface
    private interface RecoveryCall {
        com.myshop.saleschannel.convergence.XianyuConvergenceContracts.RecoveryPermitObservation apply(
                XianyuConvergenceHttpClient client);
    }

    private record RecoveryMutation(String path, String state, RecoveryCall call) { }

    private XianyuConvergenceHttpClient client(String baseUrl, long timeoutMillis) {
        var transport = new XianyuSignedHttpTransport(baseUrl, "/api/integration/v2", "test-key", SECRET,
                timeoutMillis, CLOCK, () -> "nonce-v2-test-00000001");
        return new XianyuConvergenceHttpClient(objectMapper, transport);
    }

    private byte[] permitPayload(String state) throws IOException {
        return objectMapper.writeValueAsBytes(permitPayloadNode(state));
    }

    private com.fasterxml.jackson.databind.node.ObjectNode permitPayloadNode(String state) throws IOException {
        try (var input = getClass().getResourceAsStream(
                "/contracts/recovery_permit_cancelled_agent_v2.json")) {
            assertNotNull(input);
            var root = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(input);
            root.put("state", state);
            if (!"CANCELLED".equals(state)) root.putNull("execution_outcome");
            return root;
        }
    }

    private RecoveryValidationRequest validationRequest() {
        return new RecoveryValidationRequest("2215747465381", "c827bfa4-abfe-47f9-8d60-f2d1c6972fcd",
                "7369460a-45a7-49d0-9cf5-85d98eff4199", "0153b2a7-fbd7-48b8-b52c-0ea2379ddf26",
                10L, "def5d3cbd69714b2cd8b3e87e5fc472631e46150b7bcd2c778a23f59b7989446",
                "1084013851108");
    }

    private RecoveryPrepareRequest prepareRequest() {
        return new RecoveryPrepareRequest("b2c7bf6f-6ac9-4366-9d45-0fe2cb93d210", 1L,
                "2215747465381", "c827bfa4-abfe-47f9-8d60-f2d1c6972fcd",
                "7369460a-45a7-49d0-9cf5-85d98eff4199", "0153b2a7-fbd7-48b8-b52c-0ea2379ddf26",
                10L, "def5d3cbd69714b2cd8b3e87e5fc472631e46150b7bcd2c778a23f59b7989446",
                "1084013851108", 15L, "test-operator", "wire-contract-test");
    }

    private RecoveryArmRequest armRequest() {
        return new RecoveryArmRequest("b2c7bf6f-6ac9-4366-9d45-0fe2cb93d210", 1L,
                "2215747465381", "c827bfa4-abfe-47f9-8d60-f2d1c6972fcd",
                "7369460a-45a7-49d0-9cf5-85d98eff4199", "0153b2a7-fbd7-48b8-b52c-0ea2379ddf26",
                10L, "def5d3cbd69714b2cd8b3e87e5fc472631e46150b7bcd2c778a23f59b7989446",
                "1084013851108", 15L, "test-operator", "wire-contract-test");
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
