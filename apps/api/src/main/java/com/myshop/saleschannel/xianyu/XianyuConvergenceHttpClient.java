package com.myshop.saleschannel.xianyu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.saleschannel.SalesChannelProviderClientException;
import com.myshop.saleschannel.convergence.XianyuConvergenceClient;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.EnsureListingRequest;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.AdoptExistingItemRequest;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.SendDeliveryRequest;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.ConfirmDeliveryRequest;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.OperationError;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.EnsureListingOperationObservation;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.OperationObservation;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.OperationState;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.CutoverReadiness;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.CutoverInspection;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.CutoverInspectionRequest;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.RecoveryValidationRequest;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.RecoveryValidationResponse;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.RecoveryPrepareRequest;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.RecoveryArmRequest;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.RecoveryPermitObservation;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.RecoveryPermitProof;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.time.Clock;
import java.util.function.Supplier;

/** Signed HTTP implementation of the exact Agent integration v2 listing contract. */
@Component
public final class XianyuConvergenceHttpClient implements XianyuConvergenceClient {

    private static final String API_ROOT = "/api/integration/v2";
    private static final int MAX_JSON_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_CUTOVER_RESPONSE_BYTES = 512 * 1024;
    private static final int MAX_CUTOVER_REQUEST_BYTES = 8 * 1024;
    private static final Validator CONTRACT_VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private final ObjectMapper objectMapper;
    private final XianyuSignedHttpTransport transport;

    @Autowired
    public XianyuConvergenceHttpClient(
            ObjectMapper objectMapper,
            @Value("${xianyu.agent.base-url:}") String baseUrl,
            @Value("${xianyu.agent.key-id:}") String keyId,
            @Value("${xianyu.agent.secret:}") String secret,
            @Value("${xianyu.agent.timeout-ms:10000}") long timeoutMillis) {
        this(objectMapper, new XianyuSignedHttpTransport(baseUrl, API_ROOT, keyId, secret,
                timeoutMillis, Clock.systemUTC(), XianyuSalesChannelClient.secureNonceSupplier()));
    }

    XianyuConvergenceHttpClient(ObjectMapper objectMapper, XianyuSignedHttpTransport transport) {
        this.objectMapper = objectMapper;
        this.transport = transport;
    }

    @Override
    public EnsureListingOperationObservation ensureListing(EnsureListingRequest request) {
        return ensureListing(request, null);
    }

    @Override
    public EnsureListingOperationObservation ensureListing(EnsureListingRequest request,
                                                            RecoveryPermitProof recoveryProof) {
        if (request == null || request.requestId() == null || request.listingKey() == null
                || request.desiredState() == null
                || request.desiredState() == com.myshop.saleschannel.convergence.XianyuConvergenceContracts.DesiredListingState.ONLINE
                && request.desiredSnapshot() == null
                || request.desiredState() == com.myshop.saleschannel.convergence.XianyuConvergenceContracts.DesiredListingState.OFFLINE
                && request.desiredSnapshot() != null) {
            throw permanent("INVALID_ENSURE_LISTING", "The listing command is incomplete");
        }
        if (!CONTRACT_VALIDATOR.validate(request).isEmpty()) {
            throw permanent("INVALID_ENSURE_LISTING", "The listing command does not satisfy Agent snapshot v1");
        }
        final byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(request);
        } catch (IOException | RuntimeException failure) {
            throw permanent("INVALID_ENSURE_LISTING", "The listing command cannot be serialized");
        }

        XianyuSignedHttpTransport.Response response;
        try {
            response = transport.send("POST", "/listings/ensure", body, "application/json", recoveryProof);
        } catch (XianyuSignedHttpTransport.TransportException failure) {
            if ("AGENT_CONFIGURATION_MISSING".equals(failure.code())
                    || "AGENT_CONFIGURATION_INVALID".equals(failure.code())
                    || "REQUEST_BUILD_FAILED".equals(failure.code())) {
                throw permanent(failure.code(), "Xianyu Agent client configuration is incomplete");
            }
            if (failure.boundary() == XianyuSignedHttpTransport.FailureBoundary.DEFINITE_PRE_SEND) {
                throw retryable(failure.code(), "Xianyu Agent was not reached before the listing command was sent");
            }
            return applying(request.requestId(), request.listingKey(), request.revision(),
                    "AGENT_RESPONSE_UNCONFIRMED", "The command may have reached Agent; observe it by request_id");
        }

        if (response.body().length > MAX_JSON_RESPONSE_BYTES) {
            if (response.statusCode() >= 400 && response.statusCode() < 500) {
                throw permanent("AGENT_RESPONSE_TOO_LARGE", "Xianyu Agent returned an unreadable rejection");
            }
            return applying(request.requestId(), request.listingKey(), request.revision(),
                    "AGENT_RESPONSE_UNREADABLE", "The Agent response exceeded the client size limit");
        }

        JsonNode root = parseTree(response.body());
        if (root != null && root.has("state")) {
            if (!hasRequiredObservationFields(root)) {
                return applying(request.requestId(), request.listingKey(), request.revision(),
                        "AGENT_RESPONSE_UNREADABLE", "Agent response did not match the v2 operation contract");
            }
            EnsureListingOperationObservation observation = parseObservation(root);
            if (observation == null) {
                return applying(request.requestId(), request.listingKey(), request.revision(),
                        "AGENT_RESPONSE_UNREADABLE", "Agent response did not match the v2 operation contract");
            }
            verifyCommandIdentity(observation, request.requestId(), request.listingKey(), request.revision());
            return observation;
        }

        String rejectionCode = errorCode(root, "");
        if ("V2_WRITES_DISABLED".equals(rejectionCode)) {
            return failedFromRejection(root, request.requestId(), request.listingKey(), request.revision());
        }

        if (response.statusCode() >= 400 && response.statusCode() < 500) {
            return failedFromRejection(root, request.requestId(), request.listingKey(), request.revision());
        }
        return applying(request.requestId(), request.listingKey(), request.revision(),
                "AGENT_RESPONSE_UNCONFIRMED", "The command may have reached Agent; observe it by request_id");
    }

    @Override
    public EnsureListingOperationObservation observeOperation(String requestId) {
        if (!validRequestId(requestId)) {
            throw permanent("INVALID_REQUEST_ID", "request_id must match [A-Za-z0-9._~-]{1,255}");
        }
        final XianyuSignedHttpTransport.Response response;
        try {
            // The frozen request_id alphabet is URL-unreserved ASCII, so using
            // it directly keeps Agent and Java HMAC canonical paths identical.
            response = transport.send("GET", "/operations/" + requestId,
                    new byte[0], "application/json");
        } catch (XianyuSignedHttpTransport.TransportException failure) {
            throw retryable(failure.code(), "Xianyu Agent operation observation could not be completed");
        }

        if (response.body().length > MAX_JSON_RESPONSE_BYTES) {
            throw retryable("AGENT_RESPONSE_TOO_LARGE", "Xianyu Agent observation exceeded the client size limit");
        }
        JsonNode root = parseTree(response.body());
        if (response.statusCode() == 404) {
            throw permanent(errorCode(root, "NOT_FOUND"), errorMessage(root, "No v2 ENSURE_LISTING command was found"));
        }
        if (response.statusCode() >= 400 && response.statusCode() < 500) {
            throw permanent(errorCode(root, "AGENT_REQUEST_REJECTED"),
                    errorMessage(root, "Xianyu Agent rejected the observation request"));
        }
        if (response.statusCode() >= 500 || root == null) {
            throw retryable("AGENT_OBSERVATION_UNAVAILABLE", "Xianyu Agent observation returned no usable result");
        }
        EnsureListingOperationObservation observation = parseObservation(root);
        if (observation == null) {
            throw retryable("AGENT_RESPONSE_INVALID", "Xianyu Agent returned an invalid operation observation");
        }
        verifyRequestId(observation, requestId);
        return observation;
    }

    @Override
    public OperationObservation adoptExistingItem(AdoptExistingItemRequest request) {
        if (request == null || !CONTRACT_VALIDATOR.validate(request).isEmpty()) {
            throw permanent("INVALID_ADOPTION_REQUEST", "The exact listing adoption request is invalid");
        }
        final byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(request);
        } catch (IOException | RuntimeException failure) {
            throw permanent("INVALID_ADOPTION_REQUEST", "The exact listing adoption request cannot be serialized");
        }

        final XianyuSignedHttpTransport.Response response;
        try {
            response = transport.send("POST", "/listings/adopt", body, "application/json");
        } catch (XianyuSignedHttpTransport.TransportException failure) {
            if ("AGENT_CONFIGURATION_MISSING".equals(failure.code())
                    || "AGENT_CONFIGURATION_INVALID".equals(failure.code())
                    || "REQUEST_BUILD_FAILED".equals(failure.code())) {
                throw permanent(failure.code(), "Xianyu Agent client configuration is incomplete");
            }
            // Adoption has no provider-side effect. Even an uncertain HTTP
            // outcome can be retried with the same exact request_id.
            throw retryable(failure.code(), "The exact identity adoption can be safely replayed");
        }
        if (response.body().length > MAX_JSON_RESPONSE_BYTES) {
            throw retryable("AGENT_ADOPTION_RESPONSE_TOO_LARGE", "Xianyu Agent adoption response was unreadable");
        }
        JsonNode root = parseTree(response.body());
        if (root != null && hasRequiredAdoptionObservationFields(root)) {
            OperationObservation observation = parseAdoptionObservation(root);
            if (observation == null) {
                throw retryable("AGENT_ADOPTION_RESPONSE_INVALID", "Xianyu Agent returned an invalid adoption response");
            }
            verifyAdoptionIdentity(observation, request);
            if (observation.state() == OperationState.FAILED
                    && observation.error() != null
                    && "ADOPTION_STATE_REPLAY_REQUIRED".equals(observation.error().code())) {
                // Compatibility with the short-lived S2.3 wire shape: this
                // code represented an uncertain DB transaction, not a
                // terminal business conflict.
                return replayableAdoption(request);
            }
            return observation;
        }

        String code = errorCode(root, "AGENT_ADOPTION_RESPONSE_UNCONFIRMED");
        String message = errorMessage(root, "Xianyu Agent adoption response could not be confirmed");
        if ("V2_ADOPTION_DISABLED".equals(code)) {
            return failedAdoption(request, code, message);
        }
        if (response.statusCode() >= 400 && response.statusCode() < 500) {
            return failedAdoption(request, code, message);
        }
        // No provider action is involved, so unknown 5xx/timeout responses
        // remain safe exact replays instead of becoming UNKNOWN.
        throw retryable(code, message);
    }

    @Override
    public OperationObservation sendDelivery(SendDeliveryRequest request) {
        throw new UnsupportedOperationException("Delivery is not wired in S2.1");
    }

    @Override
    public OperationObservation confirmDelivery(ConfirmDeliveryRequest request) {
        throw new UnsupportedOperationException("Delivery is not wired in S2.1");
    }

    @Override
    public CutoverReadiness agentReadiness() {
        final XianyuSignedHttpTransport.Response response;
        try {
            response = transport.send("GET", "/readiness", new byte[0], "application/json");
        } catch (XianyuSignedHttpTransport.TransportException failure) {
            throw readOnlyRetryable("AGENT_READINESS_UNAVAILABLE");
        }
        if (response.body().length > MAX_CUTOVER_RESPONSE_BYTES || response.statusCode() < 200
                || response.statusCode() >= 300) {
            throw readOnlyRetryable("AGENT_READINESS_UNAVAILABLE");
        }
        JsonNode root = parseTree(response.body());
        if (!hasExactFields(root, "schema_version", "v2_writes_enabled", "v2_adoption_enabled")) {
            throw readOnlyRetryable("AGENT_READINESS_RESPONSE_INVALID");
        }
        try {
            CutoverReadiness readiness = objectMapper.treeToValue(root, CutoverReadiness.class);
            if (!CONTRACT_VALIDATOR.validate(readiness).isEmpty()) {
                throw readOnlyRetryable("AGENT_READINESS_RESPONSE_INVALID");
            }
            return readiness;
        } catch (IOException | RuntimeException invalid) {
            throw readOnlyRetryable("AGENT_READINESS_RESPONSE_INVALID");
        }
    }

    @Override
    public CutoverInspection inspectListing(CutoverInspectionRequest request) {
        if (request == null || !CONTRACT_VALIDATOR.validate(request).isEmpty()
                || !validRequestId(request.requestId()) || !validListingKey(request.listingKey())
                || !validExactIdentity(request.accountId())
                || request.expectedExternalItemId() != null
                && !validExactIdentity(request.expectedExternalItemId(), 64)) {
            throw readOnlyRetryable("AGENT_INSPECTION_REQUEST_INVALID");
        }
        final byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(request);
        } catch (IOException | RuntimeException invalid) {
            throw readOnlyRetryable("AGENT_INSPECTION_REQUEST_INVALID");
        }
        if (body.length > MAX_CUTOVER_REQUEST_BYTES) {
            throw readOnlyRetryable("AGENT_INSPECTION_REQUEST_TOO_LARGE");
        }
        final XianyuSignedHttpTransport.Response response;
        try {
            response = transport.send("POST", "/listings/inspect", body, "application/json");
        } catch (XianyuSignedHttpTransport.TransportException failure) {
            throw readOnlyRetryable("AGENT_INSPECTION_UNAVAILABLE");
        }
        if (response.body().length > MAX_CUTOVER_RESPONSE_BYTES || response.statusCode() < 200
                || response.statusCode() >= 300) {
            throw readOnlyRetryable("AGENT_INSPECTION_UNAVAILABLE");
        }
        JsonNode root = parseTree(response.body());
        if (!hasExactFields(root, "schema_version", "request_id", "account_id", "listing_key",
                "expected_external_item_id", "v2_writes_enabled", "v2_adoption_enabled", "managed_item",
                "bindings", "lifecycle_head", "nonterminal_operations", "provider_observation",
                "provider_observation_error_code", "inspection_digest")) {
            throw readOnlyRetryable("AGENT_INSPECTION_RESPONSE_INVALID");
        }
        try {
            CutoverInspection inspection = objectMapper.treeToValue(root, CutoverInspection.class);
            if (!CONTRACT_VALIDATOR.validate(inspection).isEmpty()
                    || !request.requestId().equals(inspection.requestId())
                    || !request.accountId().equals(inspection.accountId())
                    || !request.listingKey().equals(inspection.listingKey())
                    || !java.util.Objects.equals(request.expectedExternalItemId(), inspection.expectedExternalItemId())
                    || inspection.providerObservationErrorCode() != null
                    && !"PROVIDER_OBSERVATION_UNAVAILABLE".equals(inspection.providerObservationErrorCode())) {
                throw readOnlyRetryable("AGENT_INSPECTION_RESPONSE_INVALID");
            }
            return inspection;
        } catch (IOException | RuntimeException invalid) {
            throw readOnlyRetryable("AGENT_INSPECTION_RESPONSE_INVALID");
        }
    }

    @Override
    public RecoveryValidationResponse validateRecovery(RecoveryValidationRequest request) {
        if (request == null || !CONTRACT_VALIDATOR.validate(request).isEmpty()) {
            throw permanent("RECOVERY_PLAN_INVALID", "The exact recovery scope is invalid");
        }
        JsonNode root = postRecovery("/recovery/validate", request, 16 * 1024);
        if (!hasExactFields(root, "eligible", "agent_operation_id", "request_id",
                "command_fingerprint", "preconditions")) {
            throw recoveryRejection(root, "RECOVERY_PLAN_UNAVAILABLE");
        }
        try {
            RecoveryValidationResponse response = objectMapper.treeToValue(root, RecoveryValidationResponse.class);
            if (!response.eligible() || response.agentOperationId() == null
                    || !request.requestId().equals(response.requestId())
                    || response.commandFingerprint() == null
                    || !response.commandFingerprint().matches("^[a-f0-9]{64}$")) {
                throw permanent("RECOVERY_PLAN_INVALID", "Agent returned a different recovery scope");
            }
            return response;
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof SalesChannelProviderClientException providerFailure) throw providerFailure;
            throw permanent("RECOVERY_PLAN_INVALID", "Agent recovery plan did not match its contract");
        }
    }

    @Override
    public RecoveryPermitObservation prepareRecovery(RecoveryPrepareRequest request) {
        if (request == null || !CONTRACT_VALIDATOR.validate(request).isEmpty()) {
            throw permanent("RECOVERY_PREPARE_INVALID", "The exact recovery permit is invalid");
        }
        return parseRecoveryPermit(postRecovery("/recovery/permits/prepare", request, 16 * 1024),
                RecoveryPermitExpectedIdentity.from(request));
    }

    @Override
    public RecoveryPermitObservation armRecovery(RecoveryArmRequest request) {
        if (request == null || !CONTRACT_VALIDATOR.validate(request).isEmpty()) {
            throw permanent("RECOVERY_ARM_INVALID", "The exact recovery permit is invalid");
        }
        return parseRecoveryPermit(postRecovery("/recovery/permits/arm", request, 16 * 1024),
                RecoveryPermitExpectedIdentity.from(request));
    }

    @Override
    public RecoveryPermitObservation activateRecovery(RecoveryArmRequest request) {
        if (request == null || !CONTRACT_VALIDATOR.validate(request).isEmpty()) {
            throw permanent("RECOVERY_GO_INVALID", "The exact recovery permit is invalid");
        }
        return parseRecoveryPermit(postRecovery("/recovery/permits/activate", request, 16 * 1024),
                RecoveryPermitExpectedIdentity.from(request));
    }

    @Override
    public RecoveryPermitObservation cancelRecovery(RecoveryArmRequest request) {
        if (request == null || !CONTRACT_VALIDATOR.validate(request).isEmpty()) {
            throw permanent("RECOVERY_CANCEL_INVALID", "The exact recovery permit is invalid");
        }
        return parseRecoveryPermit(postRecovery("/recovery/permits/cancel", request, 16 * 1024),
                RecoveryPermitExpectedIdentity.from(request));
    }

    @Override
    public RecoveryPermitObservation observeRecovery(String permitId) {
        if (permitId == null || !permitId.matches("^[0-9a-f-]{36}$")) {
            throw permanent("RECOVERY_PERMIT_ID_INVALID", "The recovery permit id is invalid");
        }
        final XianyuSignedHttpTransport.Response response;
        try {
            response = transport.send("GET", "/recovery/permits/" + permitId,
                    new byte[0], "application/json");
        } catch (XianyuSignedHttpTransport.TransportException failure) {
            throw retryable(failure.code(), "Agent recovery permit state could not be observed");
        }
        JsonNode root = parseTree(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw recoveryRejection(root, "RECOVERY_PERMIT_UNAVAILABLE");
        }
        return parseRecoveryPermit(root, permitId);
    }

    private JsonNode postRecovery(String endpoint, Object request, int maxRequestBytes) {
        final byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(request);
        } catch (IOException | RuntimeException invalid) {
            throw permanent("RECOVERY_REQUEST_INVALID", "The exact recovery request cannot be serialized");
        }
        if (body.length > maxRequestBytes) {
            throw permanent("RECOVERY_REQUEST_TOO_LARGE", "The exact recovery request exceeds its size limit");
        }
        final XianyuSignedHttpTransport.Response response;
        try {
            response = transport.send("POST", endpoint, body, "application/json");
        } catch (XianyuSignedHttpTransport.TransportException failure) {
            throw retryable(failure.code(), "Agent recovery control plane could not be reached");
        }
        JsonNode root = parseTree(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw recoveryRejection(root, "RECOVERY_REQUEST_REJECTED");
        }
        return root;
    }

    private RecoveryPermitObservation parseRecoveryPermit(JsonNode root, String expectedPermitId) {
        if (!hasExactFields(root, "permit_id", "generation", "state", "account_id", "listing_key",
                "myshop_operation_id", "agent_operation_id", "request_id", "desired_revision",
                "desired_fingerprint", "expected_old_item_id", "operator", "reason", "preconditions",
                "execution_outcome", "prepared_at", "armed_at", "activated_at", "execution_started_at",
                "terminal_at", "execution_fence", "myshop_execution_fence")
                || !hasValidRecoveryPermitTypes(root)) {
            throw permanent("RECOVERY_PERMIT_RESPONSE_INVALID", "Agent returned an invalid recovery permit");
        }
        try {
            RecoveryPermitObservation observation = objectMapper.treeToValue(root, RecoveryPermitObservation.class);
            if (!expectedPermitId.equals(observation.permitId()) || observation.generation() == null
                    || observation.agentOperationId() == null || observation.requestId() == null) {
                throw permanent("RECOVERY_PERMIT_IDENTITY_MISMATCH", "Agent returned a different recovery permit");
            }
            return observation;
        } catch (IOException | RuntimeException invalid) {
            if (invalid instanceof SalesChannelProviderClientException providerFailure) throw providerFailure;
            throw permanent("RECOVERY_PERMIT_RESPONSE_INVALID", "Agent returned an invalid recovery permit");
        }
    }

    private RecoveryPermitObservation parseRecoveryPermit(
            JsonNode root, RecoveryPermitExpectedIdentity expected) {
        RecoveryPermitObservation observation = parseRecoveryPermit(root, expected.permitId());
        if (observation.generation() != expected.generation()
                || !expected.accountId().equals(observation.accountId())
                || !expected.listingKey().equals(observation.listingKey())
                || !expected.myshopOperationId().equals(observation.myshopOperationId())
                || !expected.requestId().equals(observation.requestId())
                || observation.desiredRevision() == null
                || observation.desiredRevision() != expected.desiredRevision()
                || !expected.desiredFingerprint().equals(observation.desiredFingerprint())
                || !expected.expectedOldItemId().equals(observation.expectedOldItemId())
                || observation.agentOperationId() != expected.agentOperationId()) {
            throw permanent("AGENT_RECOVERY_PERMIT_IDENTITY_MISMATCH",
                    "Agent returned a recovery permit for a different command");
        }
        return observation;
    }

    private static boolean hasValidRecoveryPermitTypes(JsonNode root) {
        if (!textual(root, "permit_id") || !integerAtLeast(root, "generation", 1)
                || !textual(root, "state") || !textual(root, "account_id")
                || !textual(root, "listing_key") || !textual(root, "myshop_operation_id")
                || !integerAtLeast(root, "agent_operation_id", 1) || !textual(root, "request_id")
                || !integerAtLeast(root, "desired_revision", 0) || !textual(root, "desired_fingerprint")
                || !textual(root, "expected_old_item_id") || !textual(root, "operator")
                || !textual(root, "reason") || !root.path("preconditions").isObject()
                || !nullableText(root, "execution_outcome")
                || !nullableTimestamp(root, "prepared_at") || !nullableTimestamp(root, "armed_at")
                || !nullableTimestamp(root, "activated_at") || !nullableTimestamp(root, "execution_started_at")
                || !nullableTimestamp(root, "terminal_at") || !integerAtLeast(root, "execution_fence", 0)
                || !integerAtLeast(root, "myshop_execution_fence", 0)) {
            return false;
        }
        return Set.of("PREPARED", "ARMED", "ACTIVE", "EXECUTING", "RECOVERY_REQUIRED", "TERMINAL", "CANCELLED")
                .contains(root.path("state").asText());
    }

    private static boolean textual(JsonNode root, String field) {
        return root.path(field).isTextual();
    }

    private static boolean nullableText(JsonNode root, String field) {
        JsonNode value = root.path(field);
        return value.isNull() || value.isTextual();
    }

    private static boolean nullableTimestamp(JsonNode root, String field) {
        JsonNode value = root.path(field);
        if (value.isNull()) return true;
        if (!value.isTextual()) return false;
        try {
            DateTimeFormatter.ISO_DATE_TIME.parse(value.asText());
            return true;
        } catch (DateTimeParseException invalid) {
            return false;
        }
    }

    private static boolean integerAtLeast(JsonNode root, String field, long minimum) {
        JsonNode value = root.path(field);
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= minimum;
    }

    private record RecoveryPermitExpectedIdentity(
            String permitId, long generation, String accountId, String listingKey,
            String myshopOperationId, long agentOperationId, String requestId,
            long desiredRevision, String desiredFingerprint, String expectedOldItemId) {
        static RecoveryPermitExpectedIdentity from(RecoveryPrepareRequest request) {
            return new RecoveryPermitExpectedIdentity(request.permitId(), request.generation(),
                    request.accountId(), request.listingKey(), request.myshopOperationId(),
                    request.agentOperationId(), request.requestId(), request.desiredRevision(),
                    request.desiredFingerprint(), request.expectedOldItemId());
        }

        static RecoveryPermitExpectedIdentity from(RecoveryArmRequest request) {
            return new RecoveryPermitExpectedIdentity(request.permitId(), request.generation(),
                    request.accountId(), request.listingKey(), request.myshopOperationId(),
                    request.agentOperationId(), request.requestId(), request.desiredRevision(),
                    request.desiredFingerprint(), request.expectedOldItemId());
        }
    }

    private SalesChannelProviderClientException recoveryRejection(JsonNode root, String fallback) {
        String code = errorCode(root, fallback);
        String message = errorMessage(root, "Agent rejected the exact recovery command");
        return permanent(code, message);
    }

    private static boolean hasExactFields(JsonNode object, String... required) {
        if (object == null || !object.isObject()) return false;
        Set<String> expected = Set.of(required);
        java.util.Set<String> actual = new java.util.HashSet<>();
        object.fieldNames().forEachRemaining(actual::add);
        return expected.equals(actual);
    }

    private static boolean validListingKey(String value) {
        return value != null && !value.isBlank() && value.length() <= 128
                && value.equals(value.strip())
                && java.text.Normalizer.isNormalized(value, java.text.Normalizer.Form.NFC);
    }

    private static boolean validExactIdentity(String value) {
        return validExactIdentity(value, 80);
    }

    private static boolean validExactIdentity(String value, int maxLength) {
        return value != null && !value.isBlank() && value.length() <= maxLength && value.equals(value.strip());
    }

    private static SalesChannelProviderClientException readOnlyRetryable(String code) {
        return retryable(code, "Xianyu Agent read-only inspection could not be completed");
    }

    private EnsureListingOperationObservation parseObservation(JsonNode root) {
        if (!hasRequiredObservationFields(root)) return null;
        try {
            EnsureListingOperationObservation observation =
                    objectMapper.treeToValue(root, EnsureListingOperationObservation.class);
            return CONTRACT_VALIDATOR.validate(observation).isEmpty() ? observation : null;
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    private OperationObservation parseAdoptionObservation(JsonNode root) {
        try {
            OperationObservation observation = objectMapper.treeToValue(root, OperationObservation.class);
            return CONTRACT_VALIDATOR.validate(observation).isEmpty() ? observation : null;
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    private static boolean hasRequiredAdoptionObservationFields(JsonNode root) {
        if (root == null || !root.isObject()
                || !root.path("request_id").isTextual() || !root.path("listing_key").isTextual()
                || !root.path("state").isTextual() || !root.has("external_item_id") || !root.has("error")) {
            return false;
        }
        String state = root.path("state").asText();
        if (!state.equals("APPLYING") && !state.equals("SUCCEEDED") && !state.equals("FAILED")) return false;
        JsonNode itemId = root.get("external_item_id");
        if (!itemId.isNull() && !itemId.isTextual()) return false;
        JsonNode error = root.get("error");
        if (state.equals("FAILED")) {
            return error.isObject() && error.path("code").isTextual() && !error.path("code").asText().isBlank()
                    && error.path("message").isTextual() && !error.path("message").asText().isBlank();
        }
        return error.isNull();
    }

    private static void verifyAdoptionIdentity(OperationObservation observation, AdoptExistingItemRequest request) {
        if (!request.requestId().equals(observation.requestId())
                || !request.listingKey().equals(observation.listingKey())) {
            throw permanent("AGENT_ADOPTION_IDENTITY_MISMATCH", "Xianyu Agent returned another adoption identity");
        }
        if (observation.state() == OperationState.SUCCEEDED
                && !request.externalItemId().equals(observation.externalItemId())) {
            throw permanent("AGENT_ADOPTION_RESULT_MISMATCH", "Xianyu Agent adopted another provider item");
        }
    }

    private static OperationObservation failedAdoption(AdoptExistingItemRequest request, String code, String message) {
        return new OperationObservation(request.requestId(), request.listingKey(), OperationState.FAILED,
                null, null, null, null, new OperationError(code, message));
    }

    private static OperationObservation replayableAdoption(AdoptExistingItemRequest request) {
        return new OperationObservation(request.requestId(), request.listingKey(), OperationState.APPLYING,
                null, null, null, null, null);
    }

    private static boolean hasRequiredObservationFields(JsonNode root) {
        if (root == null || !root.isObject()
                || !root.path("request_id").isTextual() || !root.path("listing_key").isTextual()
                || !root.path("revision").isIntegralNumber() || root.path("revision").longValue() < 0
                || !root.path("state").isTextual() || !root.has("external_item_id") || !root.has("error")) {
            return false;
        }
        String state = root.path("state").asText();
        if (!state.equals("APPLYING") && !state.equals("SUCCEEDED") && !state.equals("FAILED")) return false;
        JsonNode itemId = root.get("external_item_id");
        if (!itemId.isNull() && !itemId.isTextual()) return false;
        JsonNode error = root.get("error");
        if (error.isNull()) return true;
        return error.isObject() && error.path("code").isTextual() && !error.path("code").asText().isBlank()
                && error.path("message").isTextual() && !error.path("message").asText().isBlank();
    }

    private EnsureListingOperationObservation failedFromRejection(JsonNode root, String requestId, String listingKey, long revision) {
        String code = errorCode(root, "AGENT_REQUEST_REJECTED");
        String message = errorMessage(root, "Xianyu Agent rejected the listing command before execution");
        return new EnsureListingOperationObservation(requestId, listingKey, revision, OperationState.FAILED, null,
                new OperationError(code, message));
    }

    private static EnsureListingOperationObservation applying(String requestId, String listingKey, long revision,
                                                String code, String message) {
        return new EnsureListingOperationObservation(requestId, listingKey, Math.max(0, revision), OperationState.APPLYING,
                null, new OperationError(code, message));
    }

    private static void verifyCommandIdentity(EnsureListingOperationObservation observation, String requestId,
                                              String listingKey, long revision) {
        verifyRequestId(observation, requestId);
        if (!listingKey.equals(observation.listingKey()) || observation.revision() == null
                || observation.revision() != revision) {
            throw permanent("AGENT_COMMAND_IDENTITY_MISMATCH", "Xianyu Agent returned another listing revision");
        }
    }

    private static void verifyRequestId(EnsureListingOperationObservation observation, String requestId) {
        if (observation == null || !requestId.equals(observation.requestId())) {
            throw permanent("AGENT_REQUEST_ID_MISMATCH", "Xianyu Agent response request_id did not match");
        }
    }

    private JsonNode parseTree(byte[] body) {
        try {
            return objectMapper.readTree(body);
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    private static String errorCode(JsonNode root, String fallback) {
        JsonNode value = root == null ? null : root.path("error").path("code");
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : fallback;
    }

    private static String errorMessage(JsonNode root, String fallback) {
        JsonNode value = root == null ? null : root.path("error").path("message");
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : fallback;
    }

    private static boolean validRequestId(String value) {
        return value != null && value.matches("^[A-Za-z0-9._~-]{1,255}$");
    }

    private static SalesChannelProviderClientException retryable(String code, String message) {
        return new SalesChannelProviderClientException(
                SalesChannelProviderClientException.Disposition.SAFE_RETRYABLE, code, message);
    }

    private static SalesChannelProviderClientException permanent(String code, String message) {
        return new SalesChannelProviderClientException(
                SalesChannelProviderClientException.Disposition.SAFE_PERMANENT, code, message);
    }
}
