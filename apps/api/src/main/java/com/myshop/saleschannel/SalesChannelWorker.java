package com.myshop.saleschannel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.constant.SalesChannelAccountStatus;
import com.myshop.constant.SalesChannelConvergenceCutoverState;
import com.myshop.constant.SalesChannelOperationStatus;
import com.myshop.constant.SalesChannelOperationType;
import com.myshop.entity.SalesChannelAccount;
import com.myshop.entity.SalesChannelListing;
import com.myshop.entity.SalesChannelListingGeneration;
import com.myshop.entity.SalesChannelOperation;
import com.myshop.repository.SalesChannelAccountRepository;
import com.myshop.repository.SalesChannelListingGenerationRepository;
import com.myshop.repository.SalesChannelListingRepository;
import com.myshop.repository.SalesChannelOperationRepository;
import com.myshop.saleschannel.convergence.XianyuConvergenceClient;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.AdoptExistingItemRequest;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.DesiredListingState;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.EnsureListingOperationObservation;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.EnsureListingRequest;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.ListingSnapshot;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.OperationObservation;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.OperationState;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.RecoveryPermitProof;
import com.myshop.service.SalesChannelDesiredStatePlanner;
import com.myshop.service.SalesChannelOperationClaimService;
import com.myshop.service.impl.SalesChannelOperationServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Executes only frozen desired-state commands; Agent owns all provider mechanics and uncertainty. */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "sales-channel.worker", name = "enabled", havingValue = "true")
public class SalesChannelWorker {

    private final SalesChannelOperationClaimService claimService;
    private final SalesChannelOperationRepository operationRepository;
    private final SalesChannelListingRepository listingRepository;
    private final SalesChannelListingGenerationRepository generationRepository;
    private final SalesChannelAccountRepository accountRepository;
    private final SalesChannelOperationServiceImpl operationService;
    private final XianyuConvergenceClient convergenceClient;
    private final SalesChannelDesiredStatePlanner planner;
    private final ObjectMapper objectMapper;
    private final int batchSize;
    private final long staleRunningMs;

    public SalesChannelWorker(SalesChannelOperationClaimService claimService,
                              SalesChannelOperationRepository operationRepository,
                              SalesChannelListingRepository listingRepository,
                              SalesChannelListingGenerationRepository generationRepository,
                              SalesChannelAccountRepository accountRepository,
                              SalesChannelOperationServiceImpl operationService,
                              XianyuConvergenceClient convergenceClient,
                              SalesChannelDesiredStatePlanner planner,
                              ObjectMapper objectMapper,
                              @Value("${sales-channel.worker.batch-size:10}") int batchSize,
                              @Value("${sales-channel.worker.stale-running-ms:300000}") long staleRunningMs) {
        this.claimService = claimService;
        this.operationRepository = operationRepository;
        this.listingRepository = listingRepository;
        this.generationRepository = generationRepository;
        this.accountRepository = accountRepository;
        this.operationService = operationService;
        this.convergenceClient = convergenceClient;
        this.planner = planner;
        this.objectMapper = objectMapper;
        this.batchSize = Math.max(1, Math.min(batchSize, 100));
        this.staleRunningMs = Math.max(60_000L, Math.min(staleRunningMs, 3_600_000L));
    }

    @Scheduled(fixedDelayString = "${sales-channel.worker.interval-ms:1000}")
    public void runBatch() {
        recoverStaleEnsureOperations();
        for (UUID operationId : claimService.selectReadyIds(LocalDateTime.now(), batchSize)) {
            processEnsure(operationId);
        }
    }

    private void recoverStaleEnsureOperations() {
        LocalDateTime staleBefore = LocalDateTime.now().minusNanos(staleRunningMs * 1_000_000L);
        for (SalesChannelOperation operation : operationRepository.findStaleEnsureRunningBefore(staleBefore, batchSize)) {
            // This changes only the local lease. The exact request id and frozen
            // payload remain intact so Agent can replay its own recovery ledger.
            operationService.recoverStaleEnsureAsReplay(operation.getId(), staleBefore);
        }
    }

    private void processEnsure(UUID operationId) {
        SalesChannelOperation snapshot = operationRepository.findById(operationId).orElse(null);
        if (snapshot == null || snapshot.getStatus() != SalesChannelOperationStatus.PENDING
                || snapshot.getOperationType() != SalesChannelOperationType.ENSURE_LISTING) return;

        // Recompute the latest local intent before claiming an unstarted
        // command. Product, price, spec, and inventory writes are discovered
        // by the planner's drift scan; this claim-time refresh closes the race
        // where the worker could send an old frozen payload before that scan.
        // PAUSED rows are recovery-scoped and must retain their exact frozen
        // identity and permit evidence.
        SalesChannelListing currentListing = listingRepository.findById(
                snapshot.getSalesChannelListingId()).orElse(null);
        if (currentListing != null
                && currentListing.getConvergenceCutoverState() == SalesChannelConvergenceCutoverState.ACTIVE) {
            try {
                planner.refresh(currentListing.getId());
            } catch (RuntimeException exception) {
                log.warn("Desired-state pre-claim refresh failed for listing {}: {}",
                        currentListing.getId(), safeMessage(exception));
                return;
            }
            snapshot = operationRepository.findById(operationId).orElse(null);
            if (snapshot == null || snapshot.getStatus() != SalesChannelOperationStatus.PENDING
                    || snapshot.getOperationType() != SalesChannelOperationType.ENSURE_LISTING) return;
        }
        if (!operationService.markRunning(operationId)) return;
        RecoveryPermitProof recoveryProof = operationService.recoveryProofForOperation(operationId);

        SalesChannelOperation operation = operationRepository.findById(operationId).orElse(null);
        if (operation == null || operation.getOperationType() != SalesChannelOperationType.ENSURE_LISTING) return;
        SalesChannelListing listing = listingRepository.findById(operation.getSalesChannelListingId()).orElse(null);
        if (listing == null) {
            fail(operation, "LISTING_NOT_FOUND", "The desired listing no longer exists", recoveryProof);
            return;
        }
        SalesChannelAccount account = accountRepository.findById(listing.getSalesChannelAccountId()).orElse(null);
        if (account == null) {
            fail(operation, "ACCOUNT_NOT_FOUND", "The desired listing account no longer exists", recoveryProof);
            return;
        }

        if (blank(account.getProviderAccountId())) {
            fail(operation, "ACCOUNT_NOT_READY", "The Agent account identity is missing", recoveryProof);
            refreshAfterTerminal(operation.getSalesChannelListingId());
            return;
        }

        FrozenCommand frozen;
        try {
            frozen = frozenCommand(operation, listing, account);
        } catch (RuntimeException exception) {
            fail(operation, "ENSURE_COMMAND_INVALID", safeMessage(exception), recoveryProof);
            refreshAfterTerminal(operation.getSalesChannelListingId());
            return;
        }
        if (account.getStatus() != SalesChannelAccountStatus.CONNECTED
                || account.getLastVerifiedAt() == null || blank(account.getProviderAccountId())) {
            fail(operation, "ACCOUNT_AUTH_REQUIRED",
                    "The sales-channel account must be verified before Agent convergence can run", recoveryProof);
            refreshAfterTerminal(operation.getSalesChannelListingId());
            return;
        }

        try {
            if (recoveryProof == null && !adoptCurrentLegacyIdentity(operation, listing, account)) return;
        } catch (SalesChannelProviderClientException exception) {
            handleReplayableFailure(operation, exception, recoveryProof);
            return;
        } catch (RuntimeException exception) {
            // Adoption is a database-only Agent operation. If its response or
            // local marker cannot be recorded, the same deterministic request
            // is safe to POST again.
            reschedule(operation, "ADOPTION_REPLAY_REQUIRED", safeMessage(exception), recoveryProof);
            return;
        }

        try {
            EnsureListingOperationObservation observation = convergenceClient.ensureListing(
                    frozen.request(), recoveryProof);
            if (observation == null) {
                reschedule(operation, "AGENT_RESPONSE_UNCONFIRMED", "Agent returned no operation observation", recoveryProof);
                return;
            }
            if (!matchesCommand(observation, frozen.request())) {
                fail(operation, "AGENT_COMMAND_IDENTITY_MISMATCH",
                        "Agent returned a different listing command identity", recoveryProof);
                refreshAfterTerminal(operation.getSalesChannelListingId());
                return;
            }
            if (observation.state() == OperationState.APPLYING) {
                reschedule(operation,
                        observation.error() == null ? "AGENT_APPLYING" : observation.error().code(),
                        observation.error() == null ? "Agent is still applying the frozen desired state"
                                : observation.error().message(), recoveryProof);
                return;
            }
            if (observation.state() == OperationState.FAILED) {
                String code = observation.error() == null ? "AGENT_OPERATION_FAILED" : observation.error().code();
                String message = observation.error() == null ? "Agent failed the desired listing command"
                        : observation.error().message();
                fail(operation, code, message, recoveryProof);
                refreshAfterTerminal(operation.getSalesChannelListingId());
                return;
            }
            if (frozen.request().desiredState() == DesiredListingState.ONLINE
                    && blank(observation.externalItemId())) {
                fail(operation, "AGENT_RESULT_INCOMPLETE",
                        "ONLINE success must include the exact external_item_id", recoveryProof);
                refreshAfterTerminal(operation.getSalesChannelListingId());
                return;
            }
            if (observation.externalItemId() != null && observation.externalItemId().length() > 64) {
                fail(operation, "AGENT_RESULT_INVALID",
                        "external_item_id exceeds the Agent provider identity contract", recoveryProof);
                refreshAfterTerminal(operation.getSalesChannelListingId());
                return;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("external_item_id", observation.externalItemId());
            result.put("state", observation.state().name());
            try {
                boolean projected = operationService.markSucceeded(operationId, result, frozen.fingerprint(),
                        recoveryProof == null ? null : recoveryProof.executionFence());
                if (!projected) {
                    fail(operation, "CONVERGENCE_PROJECTION_CONFLICT",
                            "The listing could not accept the confirmed Agent result", recoveryProof);
                }
            } catch (RuntimeException exception) {
                // The provider command is already terminal in Agent. Fail
                // closed locally; an explicit retry reuses desired intent and
                // the identity projection conflict remains visible.
                fail(operation, "CONVERGENCE_PROJECTION_CONFLICT", safeMessage(exception), recoveryProof);
            }
            refreshAfterTerminal(operation.getSalesChannelListingId());
        } catch (SalesChannelProviderClientException exception) {
            handleReplayableFailure(operation, exception, recoveryProof);
        } catch (RuntimeException exception) {
            // Once POST may have started, the only safe recovery is to replay
            // the same local command and request_id.
            reschedule(operation, "AGENT_REPLAY_REQUIRED", safeMessage(exception), recoveryProof);
        }
    }

    private boolean adoptCurrentLegacyIdentity(SalesChannelOperation operation,
                                                SalesChannelListing listing,
                                                SalesChannelAccount account) {
        SalesChannelListingGeneration current = generationRepository.findCurrentByListingId(listing.getId())
                .orElse(null);
        if (current == null || current.getExternalItemId() == null
                || current.getExternalItemId().equals(listing.getConvergenceAdoptedExternalItemId())) return true;

        String requestId = adoptionRequestId(listing.getId(), current.getExternalItemId());
        AdoptExistingItemRequest request = new AdoptExistingItemRequest(
                requestId,
                listing.getId().toString().toLowerCase(),
                account.getProviderAccountId(),
                current.getExternalItemId());
        OperationObservation observation = convergenceClient.adoptExistingItem(request);
        if (observation == null || observation.state() == null) {
            reschedule(operation, "ADOPTION_RESPONSE_UNCONFIRMED", "Agent adoption returned no terminal result");
            return false;
        }
        if (!requestId.equals(observation.requestId())
                || !request.listingKey().equals(observation.listingKey())) {
            fail(operation, "AGENT_ADOPTION_IDENTITY_MISMATCH",
                    "Agent returned a different listing adoption identity");
            refreshAfterTerminal(operation.getSalesChannelListingId());
            return false;
        }
        if (observation.state() == OperationState.APPLYING) {
            reschedule(operation, "ADOPTION_APPLYING", "Agent adoption is being safely replayed");
            return false;
        }
        if (observation.state() == OperationState.FAILED) {
            String code = observation.error() == null ? "ADOPTION_FAILED" : observation.error().code();
            String message = observation.error() == null ? "Agent rejected exact identity adoption"
                    : observation.error().message();
            fail(operation, code, message);
            refreshAfterTerminal(operation.getSalesChannelListingId());
            return false;
        }
        if (!current.getExternalItemId().equals(observation.externalItemId())
                || !operationService.markConvergenceAdopted(listing.getId(), current.getExternalItemId())) {
            fail(operation, "ADOPTION_CURRENT_IDENTITY_CHANGED",
                    "The legacy current item changed while Agent adoption was in progress");
            refreshAfterTerminal(operation.getSalesChannelListingId());
            return false;
        }
        return true;
    }

    private FrozenCommand frozenCommand(SalesChannelOperation operation,
                                        SalesChannelListing listing,
                                        SalesChannelAccount account) {
        Map<String, Object> payload = operation.getPayload();
        String listingKey = listing.getId().toString().toLowerCase();
        if (!listingKey.equals(payload.get("listing_key"))
                || !account.getProviderAccountId().equals(payload.get("account_id"))
                || !Long.valueOf(operation.getDesiredRevision()).equals(asLong(payload.get("revision")))) {
            throw new IllegalArgumentException("The frozen ENSURE_LISTING account, listing, or revision does not match local scope");
        }
        String fingerprint = Objects.toString(payload.get("desired_fingerprint"), null);
        if (fingerprint == null || !fingerprint.matches("^[a-f0-9]{64}$")) {
            throw new IllegalArgumentException("The frozen desired_fingerprint is missing or invalid");
        }
        DesiredListingState state = DesiredListingState.valueOf(
                Objects.toString(payload.get("desired_state"), ""));
        Object rawSnapshot = payload.get("desired_snapshot");
        ListingSnapshot snapshot = rawSnapshot == null ? null : objectMapper.convertValue(rawSnapshot, ListingSnapshot.class);
        if ((state == DesiredListingState.ONLINE) != (snapshot != null)) {
            throw new IllegalArgumentException("The frozen snapshot presence does not match desired_state");
        }
        EnsureListingRequest request = new EnsureListingRequest(operation.getIdempotencyKey(), listingKey,
                account.getProviderAccountId(), operation.getDesiredRevision(), state, snapshot);
        return new FrozenCommand(request, fingerprint);
    }

    private void handleReplayableFailure(SalesChannelOperation operation,
                                         SalesChannelProviderClientException exception,
                                         RecoveryPermitProof recoveryProof) {
        if (exception.getDisposition() == SalesChannelProviderClientException.Disposition.SAFE_PERMANENT) {
            fail(operation, exception.getErrorCode(), exception.getMessage(), recoveryProof);
            refreshAfterTerminal(operation.getSalesChannelListingId());
        } else {
            reschedule(operation, exception.getErrorCode(), exception.getMessage(), recoveryProof);
        }
    }

    private void fail(SalesChannelOperation operation, String code, String message) {
        fail(operation, code, message, null);
    }

    private void fail(SalesChannelOperation operation, String code, String message,
                      RecoveryPermitProof recoveryProof) {
        operationService.markFailed(operation.getId(), code, message,
                recoveryProof == null ? null : recoveryProof.executionFence());
    }

    private void reschedule(SalesChannelOperation operation, String code, String message) {
        reschedule(operation, code, message, null);
    }

    private void reschedule(SalesChannelOperation operation, String code, String message,
                            RecoveryPermitProof recoveryProof) {
        int attempt = Math.max(1, operation.getAttemptCount());
        operationService.reschedule(operation.getId(), code, message,
                LocalDateTime.now().plus(backoff(attempt)),
                recoveryProof == null ? null : recoveryProof.executionFence());
    }

    private void refreshAfterTerminal(UUID listingId) {
        try {
            planner.refresh(listingId);
        } catch (RuntimeException exception) {
            log.warn("Desired-state refresh failed for listing {}: {}", listingId,
                    exception.getClass().getSimpleName());
        }
    }

    private String adoptionRequestId(UUID listingId, String externalItemId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(externalItemId.getBytes(StandardCharsets.UTF_8));
            return "adopt-" + listingId.toString().toLowerCase() + "-" + HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to derive stable adoption request identity", exception);
        }
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String string) {
            try { return Long.parseLong(string); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private boolean matchesCommand(EnsureListingOperationObservation observation, EnsureListingRequest request) {
        return request.requestId().equals(observation.requestId())
                && request.listingKey().equals(observation.listingKey())
                && Objects.equals(request.revision(), observation.revision());
    }

    private java.time.Duration backoff(int attempt) {
        long seconds = Math.min(900L, 1L << Math.min(9, Math.max(0, attempt - 1)));
        return java.time.Duration.ofSeconds(seconds);
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "The frozen convergence command could not be executed" : message;
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }

    private record FrozenCommand(EnsureListingRequest request, String fingerprint) { }
}
