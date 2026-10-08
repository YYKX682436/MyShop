package com.myshop.service.impl;

import com.myshop.constant.SalesChannelGenerationState;
import com.myshop.constant.SalesChannelListingDesiredState;
import com.myshop.constant.SalesChannelListingStatus;
import com.myshop.constant.SalesChannelConvergenceCutoverState;
import com.myshop.constant.SalesChannelOperationStatus;
import com.myshop.constant.SalesChannelOperationType;
import com.myshop.entity.SalesChannelListing;
import com.myshop.entity.SalesChannelListingGeneration;
import com.myshop.entity.SalesChannelOperation;
import com.myshop.entity.SalesChannelRecoveryPermit;
import com.myshop.entity.SalesChannelAccount;
import com.myshop.repository.SalesChannelListingGenerationRepository;
import com.myshop.repository.SalesChannelListingRepository;
import com.myshop.repository.SalesChannelOperationRepository;
import com.myshop.repository.SalesChannelRecoveryPermitRepository;
import com.myshop.repository.SalesChannelAccountRepository;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.RecoveryPermitProof;
import com.myshop.service.SalesChannelListingManagementPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/** MyShop-side persistence boundary for ENSURE_LISTING only. */
@Service
@RequiredArgsConstructor
public class SalesChannelOperationServiceImpl {

    private final SalesChannelListingRepository listingRepository;
    private final SalesChannelListingGenerationRepository generationRepository;
    private final SalesChannelOperationRepository operationRepository;
    private final SalesChannelRecoveryPermitRepository recoveryPermitRepository;
    private final SalesChannelAccountRepository accountRepository;

    /** Persists one frozen desired-state command with a stable, path-safe Agent request id. */
    @Transactional
    public SalesChannelOperation requestEnsureListing(UUID listingId, Map<String, Object> payload) {
        SalesChannelListing listing = lockListing(listingId);
        // Validate the frozen wire payload before authorization checks. This
        // keeps malformed requests deterministic without allowing persistence.
        validateEnsurePayload(listingId, listing, payload);
        SalesChannelListingManagementPolicy.requireProviderWritesAllowed(listing);
        if (listing.getConvergenceCutoverState() != SalesChannelConvergenceCutoverState.ACTIVE) {
            throw new IllegalStateException("CUTOVER_NOT_ACTIVE");
        }
        if (generationRepository.findCandidateByListingId(listingId).isPresent()) {
            throw new IllegalStateException("LEGACY_CANDIDATE_UNRESOLVED");
        }
        String requestId = UUID.randomUUID().toString().toLowerCase();
        return operationRepository.saveAndFlush(new SalesChannelOperation(
                listingId, SalesChannelOperationType.ENSURE_LISTING, listing.getDesiredRevision(), requestId, payload));
    }

    /**
     * Deduplicate the exact, frozen first ENSURE created by certification.
     * This deliberately bypasses ACTIVE authorization: the command remains
     * unclaimable until activation.
     */
    @Transactional
    public SalesChannelOperation prepareCertifiedEnsure(UUID listingId, Map<String, Object> payload) {
        SalesChannelListing listing = lockListing(listingId);
        SalesChannelListingManagementPolicy.requireProviderWritesAllowed(listing);
        if (listing.getConvergenceCutoverState() != SalesChannelConvergenceCutoverState.UNCERTIFIED
                && listing.getConvergenceCutoverState() != SalesChannelConvergenceCutoverState.CERTIFIED) {
            throw new IllegalStateException("CUTOVER_NOT_UNCERTIFIED");
        }
        validateEnsurePayload(listingId, listing, payload);
        if (generationRepository.findCandidateByListingId(listingId).isPresent()) {
            throw new IllegalStateException("LEGACY_CANDIDATE_UNRESOLVED");
        }

        var operations = operationRepository.findBySalesChannelListingIdOrderByCreatedAtAsc(listingId);
        LocalDateTime now = LocalDateTime.now();
        SalesChannelOperation exact = null;
        for (SalesChannelOperation operation : operations) {
            if (operation.getOperationType() != SalesChannelOperationType.ENSURE_LISTING) continue;
            boolean live = operation.getStatus() == SalesChannelOperationStatus.PENDING
                    || operation.getStatus() == SalesChannelOperationStatus.RUNNING
                    || operation.getStatus() == SalesChannelOperationStatus.UNKNOWN;
            boolean neverSent = operation.getStatus() == SalesChannelOperationStatus.PENDING
                    && operation.getAttemptCount() == 0 && operation.getStartedAt() == null;
            if (live && !neverSent && operation.getAttemptCount() > 0) {
                throw new IllegalStateException("CONVERGENCE_OPERATION_IN_FLIGHT");
            }
            if (operation.getDesiredRevision() < listing.getDesiredRevision() && live) {
                if (!neverSent) throw new IllegalStateException("CONVERGENCE_OPERATION_IN_FLIGHT");
                operation.supersede(now);
                continue;
            }
            if (operation.getDesiredRevision() != listing.getDesiredRevision() || !neverSent) continue;
            if (exact == null && isSameFrozenPayload(operation.getPayload(), payload)) {
                exact = operation;
            } else {
                operation.supersede(now);
            }
        }
        if (exact != null) return exact;
        String requestId = UUID.randomUUID().toString().toLowerCase();
        return operationRepository.saveAndFlush(new SalesChannelOperation(
                listingId, SalesChannelOperationType.ENSURE_LISTING, listing.getDesiredRevision(), requestId, payload));
    }

    public boolean isSameFrozenPayload(Map<String, Object> left, Map<String, Object> right) {
        return Objects.equals(normalizeJsonValue(left), normalizeJsonValue(right));
    }

    private Object normalizeJsonValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new TreeMap<>();
            map.forEach((key, nested) -> normalized.put(Objects.toString(key), normalizeJsonValue(nested)));
            return normalized;
        }
        if (value instanceof Collection<?> collection) {
            ArrayList<Object> normalized = new ArrayList<>(collection.size());
            collection.forEach(nested -> normalized.add(normalizeJsonValue(nested)));
            return normalized;
        }
        if (value instanceof Number number) {
            try {
                return new BigDecimal(number.toString()).stripTrailingZeros();
            } catch (NumberFormatException ignored) {
                return number;
            }
        }
        return value;
    }

    private void validateEnsurePayload(UUID listingId, SalesChannelListing listing,
                                       Map<String, Object> payload) {
        Object desiredState = payload == null ? null : payload.get("desired_state");
        if (payload == null
                || !listingId.toString().equalsIgnoreCase(Objects.toString(payload.get("listing_key"), ""))
                || !Objects.equals(payload.get("revision"), listing.getDesiredRevision())
                || !Objects.equals(desiredState, listing.getDesiredState().name())
                || !"ONLINE".equals(desiredState) && !"OFFLINE".equals(desiredState)
                || !payload.containsKey("desired_snapshot")
                || ("ONLINE".equals(desiredState) && payload.get("desired_snapshot") == null)
                || ("OFFLINE".equals(desiredState) && payload.get("desired_snapshot") != null)
                || Objects.toString(payload.get("desired_fingerprint"), "").isBlank()) {
            throw new IllegalArgumentException("ENSURE_LISTING requires a complete frozen desired-state payload");
        }
    }

    /** Persist the exact legacy identity only while it remains the current local projection. */
    @Transactional
    public boolean markConvergenceAdopted(UUID listingId, String externalItemId) {
        if (externalItemId == null || externalItemId.isBlank()) return false;
        SalesChannelListing listing = lockListing(listingId);
        SalesChannelListingGeneration current = generationRepository.lockCurrentGeneration(listingId).orElse(null);
        if (current == null || !externalItemId.equals(current.getExternalItemId())) return false;
        listing.setConvergenceAdoptedExternalItemId(externalItemId);
        listing.setConvergenceAdoptedAt(LocalDateTime.now());
        return true;
    }

    /** Claims only ENSURE_LISTING and quarantines any uncertain legacy write history. */
    @Transactional
    public boolean markRunning(UUID operationId) {
        SalesChannelOperation snapshot = findOperation(operationId);
        SalesChannelListing listing = lockListing(snapshot.getSalesChannelListingId());
        SalesChannelOperation operation = lockOperation(operationId);
        LocalDateTime now = LocalDateTime.now();
        if (operation.getOperationType() != SalesChannelOperationType.ENSURE_LISTING) return false;
        SalesChannelRecoveryPermit recoveryPermit = recoveryPermitRepository
                .findByOperationIdForUpdate(operationId).orElse(null);
        if (recoveryPermit != null) {
            if (!matchesActiveRecoveryScope(recoveryPermit, listing, operation)) return false;
        } else if (!recoveryPermitRepository.findByStateInForUpdate(
                SalesChannelRecoveryPermitRepository.SCOPED_STATES).isEmpty()
                && operation.getAttemptCount() == 0) {
            // Candidate selection is not the fence: once scoped mode exists,
            // no unattempted A/C (or other) operation may be claimed even if
            // another scheduler selected it before the GO transaction.
            return false;
        }
        boolean neverAttempted = operation.getStatus() == SalesChannelOperationStatus.PENDING
                && operation.getAttemptCount() == 0;
        boolean neverSent = operation.getStatus() == SalesChannelOperationStatus.PENDING
                && operation.getAttemptCount() == 0 && operation.getStartedAt() == null;
        boolean attempted = operation.getAttemptCount() > 0;
        if (neverAttempted && listing.getConvergenceCutoverState() != SalesChannelConvergenceCutoverState.ACTIVE) {
            // A certification bootstrap command waits intact for activation.
            return false;
        }
        if (!SalesChannelListingManagementPolicy.providerWritesAllowed(listing)) {
            if (neverSent) {
                operation.supersede(now);
                return false;
            }
            // An attempted command retains its recovery privilege. Replaying
            // its frozen request_id resolves uncertainty; it is not a new intent.
            if (!attempted) return false;
        }
        if (operation.getStatus() != SalesChannelOperationStatus.PENDING
                || operation.getNextAttemptAt() != null && operation.getNextAttemptAt().isAfter(now)) return false;
        if ("EXTERNAL_ITEM_ID_CONFLICT".equals(listing.getLastErrorCode())) return false;
        if ("DESIRED_LISTING_INVALID".equals(listing.getLastErrorCode()) && !attempted) {
            if (neverSent) operation.supersede(now);
            return false;
        }
        if (operation.getDesiredRevision() != listing.getDesiredRevision() && !attempted) {
            operation.supersede(now);
            return false;
        }
        if (operation.getTargetGenerationId() != null || operation.getResultGenerationId() != null
                || generationRepository.findCandidateByListingId(listing.getId()).isPresent()
                || listing.getStatus() == SalesChannelListingStatus.REPLACEMENT_PENDING) return false;
        if (quarantineLegacyRows(listing.getId(), now)) return false;
        if (operationRepository.existsBySalesChannelListingIdAndStatus(
                listing.getId(), SalesChannelOperationStatus.RUNNING)) return false;
        if (!operation.start(now)) return false;
        if (recoveryPermit != null) recoveryPermit.beginExecution(now);
        if (SalesChannelListingManagementPolicy.providerWritesAllowed(listing)
                && !"DESIRED_LISTING_INVALID".equals(listing.getLastErrorCode())) {
            listing.setStatus(SalesChannelListingStatus.APPLYING);
        }
        return true;
    }

    /** A stale local lease replays the same frozen Agent command; MyShop never creates UNKNOWN. */
    @Transactional
    public boolean recoverStaleEnsureAsReplay(UUID operationId, LocalDateTime staleBefore) {
        SalesChannelOperation snapshot = findOperation(operationId);
        if (snapshot.getOperationType() != SalesChannelOperationType.ENSURE_LISTING) return false;
        SalesChannelListing listing = lockListing(snapshot.getSalesChannelListingId());
        SalesChannelOperation operation = lockOperation(operationId);
        if (operation.getStatus() != SalesChannelOperationStatus.RUNNING
                || operation.getStartedAt() == null || !operation.getStartedAt().isBefore(staleBefore)) return false;
        boolean rescheduled = operation.reschedule("ENSURE_REPLAY_REQUIRED",
                "The Agent owns recovery for this possibly-applied command; replay its frozen request id",
                nextReplayAt(operation.getAttemptCount()));
        SalesChannelRecoveryPermit permit = recoveryPermitRepository
                .findByOperationIdForUpdate(operationId).orElse(null);
        if (rescheduled && permit != null && "EXECUTING".equals(permit.getState())) {
            permit.recoveryRequired("STALE_LOCAL_LEASE_REPLAY");
        }
        return rescheduled;
    }

    /** Applies an Agent terminal result and its identity compatibility projection atomically. */
    @Transactional
    public boolean markSucceeded(UUID operationId, Map<String, Object> result, String appliedFingerprint) {
        return markSucceeded(operationId, result, appliedFingerprint, null);
    }

    @Transactional
    public boolean markSucceeded(UUID operationId, Map<String, Object> result, String appliedFingerprint,
                                 Long expectedRecoveryFence) {
        SalesChannelOperation snapshot = findOperation(operationId);
        SalesChannelListing listing = lockListing(snapshot.getSalesChannelListingId());
        SalesChannelOperation operation = lockOperation(operationId);
        SalesChannelRecoveryPermit recoveryPermit = recoveryPermitRepository
                .findByOperationIdForUpdate(operationId).orElse(null);
        if (!matchesRecoveryFence(recoveryPermit, expectedRecoveryFence)) return false;
        if (operation.getOperationType() != SalesChannelOperationType.ENSURE_LISTING
                || !SalesChannelListingManagementPolicy.providerWritesAllowed(listing)
                && operation.getAttemptCount() == 0) return false;

        String desiredState = Objects.toString(operation.getPayload().get("desired_state"), "");
        String externalItemId = extractExternalItemId(result);
        if (!"ONLINE".equals(desiredState) && !"OFFLINE".equals(desiredState)) {
            throw new IllegalArgumentException("ENSURE_LISTING has an invalid desired_state");
        }
        if ("ONLINE".equals(desiredState) && (externalItemId == null || externalItemId.length() > 64)) {
            throw new IllegalArgumentException("AGENT_RESULT_INCOMPLETE: ONLINE success must include external_item_id");
        }
        if (externalItemId != null && externalItemId.length() > 64) {
            throw new IllegalArgumentException("AGENT_RESULT_INVALID: external_item_id exceeds the Agent contract");
        }
        if (result == null || !"SUCCEEDED".equals(result.get("state"))) {
            throw new IllegalArgumentException("ENSURE_LISTING result must be an Agent SUCCEEDED observation");
        }
        if (!operation.succeed(result, LocalDateTime.now())) return false;

        LocalDateTime now = LocalDateTime.now();
        if (recoveryPermit != null) recoveryPermit.terminal("SUCCEEDED", now);
        if (externalItemId != null) projectConvergenceIdentity(listing, externalItemId, now);
        String frozenFingerprint = Objects.toString(operation.getPayload().get("desired_fingerprint"), null);
        if (externalItemId != null) {
            listing.setConvergenceAdoptedExternalItemId(externalItemId);
            listing.setConvergenceAdoptedAt(now);
        }
        if (operation.getDesiredRevision() == listing.getDesiredRevision()
                && SalesChannelListingManagementPolicy.providerWritesAllowed(listing)
                && !"DESIRED_LISTING_INVALID".equals(listing.getLastErrorCode())) {
            listing.advanceAppliedRevision(operation.getDesiredRevision());
            listing.setStatus("OFFLINE".equals(desiredState)
                    ? SalesChannelListingStatus.OFFLINE : SalesChannelListingStatus.ONLINE);
            listing.setAppliedFingerprint(frozenFingerprint);
            listing.setLastSyncedAt(now);
            listing.setLastErrorCode(null);
            listing.setLastErrorMessage(null);
        }
        return true;
    }

    /** APPLYING, transport loss, and other replay-safe outcomes retain command identity indefinitely. */
    @Transactional
    public boolean reschedule(UUID operationId, String errorCode, String errorMessage,
                              LocalDateTime nextAttemptAt) {
        return reschedule(operationId, errorCode, errorMessage, nextAttemptAt, null);
    }

    @Transactional
    public boolean reschedule(UUID operationId, String errorCode, String errorMessage,
                              LocalDateTime nextAttemptAt, Long expectedRecoveryFence) {
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt is required");
        SalesChannelOperation snapshot = findOperation(operationId);
        lockListing(snapshot.getSalesChannelListingId());
        SalesChannelOperation operation = lockOperation(operationId);
        SalesChannelRecoveryPermit permit = recoveryPermitRepository
                .findByOperationIdForUpdate(operationId).orElse(null);
        if (!matchesRecoveryFence(permit, expectedRecoveryFence)) return false;
        if (operation.getOperationType() != SalesChannelOperationType.ENSURE_LISTING) return false;
        boolean rescheduled = operation.reschedule(errorCode, errorMessage, nextAttemptAt);
        if (rescheduled && permit != null && "EXECUTING".equals(permit.getState())) {
            permit.recoveryRequired(errorCode);
        }
        return rescheduled;
    }

    @Transactional
    public boolean markFailed(UUID operationId, String errorCode, String errorMessage) {
        return markFailed(operationId, errorCode, errorMessage, null);
    }

    @Transactional
    public boolean markFailed(UUID operationId, String errorCode, String errorMessage,
                              Long expectedRecoveryFence) {
        SalesChannelOperation snapshot = findOperation(operationId);
        SalesChannelListing listing = lockListing(snapshot.getSalesChannelListingId());
        SalesChannelOperation operation = lockOperation(operationId);
        SalesChannelRecoveryPermit permit = recoveryPermitRepository
                .findByOperationIdForUpdate(operationId).orElse(null);
        if (!matchesRecoveryFence(permit, expectedRecoveryFence)) return false;
        if (operation.getOperationType() != SalesChannelOperationType.ENSURE_LISTING
                || !operation.fail(errorCode, errorMessage, LocalDateTime.now())) return false;
        LocalDateTime now = LocalDateTime.now();
        if (permit != null) permit.terminal("FAILED_NO_AUTOMATIC_RETRY:" + errorCode, now);
        if (operation.getDesiredRevision() == listing.getDesiredRevision()
                && !"DESIRED_LISTING_INVALID".equals(listing.getLastErrorCode())) {
            listing.setStatus(SalesChannelListingStatus.ERROR);
            listing.setLastErrorCode(errorCode);
            listing.setLastErrorMessage(errorMessage);
        }
        return true;
    }

    private boolean matchesRecoveryFence(SalesChannelRecoveryPermit permit, Long expectedFence) {
        if (permit == null) return expectedFence == null;
        return expectedFence != null && "EXECUTING".equals(permit.getState())
                && permit.getExecutionFence() == expectedFence;
    }

    @Transactional(readOnly = true)
    public RecoveryPermitProof recoveryProofForOperation(UUID operationId) {
        SalesChannelRecoveryPermit permit = recoveryPermitRepository.findByMyshopOperationId(operationId).orElse(null);
        if (permit == null || !"EXECUTING".equals(permit.getState())) return null;
        return new RecoveryPermitProof(
                permit.getId().toString().toLowerCase(), permit.getGeneration(), permit.getAccountId(),
                permit.getSalesChannelListingId().toString().toLowerCase(),
                permit.getMyshopOperationId().toString().toLowerCase(), permit.getAgentOperationId(),
                permit.getRequestId(), permit.getDesiredRevision(), permit.getDesiredFingerprint(),
                permit.getExpectedOldItemId(), permit.getExecutionFence());
    }

    private boolean matchesActiveRecoveryScope(SalesChannelRecoveryPermit permit,
                                               SalesChannelListing listing,
                                               SalesChannelOperation operation) {
        if (!("ACTIVE".equals(permit.getState()) || "RECOVERY_REQUIRED".equals(permit.getState()))
                || !permit.getSalesChannelListingId().equals(listing.getId())
                || !permit.getMyshopOperationId().equals(operation.getId())
                || !permit.getRequestId().equals(operation.getIdempotencyKey())
                || permit.getDesiredRevision() != operation.getDesiredRevision()
                || permit.getDesiredRevision() != listing.getDesiredRevision()
                || !Objects.equals(permit.getDesiredFingerprint(), listing.getDesiredFingerprint())
                || listing.getConvergenceCutoverState() != SalesChannelConvergenceCutoverState.PAUSED) {
            return false;
        }
        Map<String, Object> payload = operation.getPayload();
        if (!permit.getRequestId().equals(Objects.toString(payload.get("request_id"), ""))
                || !permit.getAccountId().equals(Objects.toString(payload.get("account_id"), ""))
                || !permit.getDesiredFingerprint().equals(Objects.toString(payload.get("desired_fingerprint"), ""))
                || !listing.getId().toString().equalsIgnoreCase(Objects.toString(payload.get("listing_key"), ""))
                || !Objects.equals("ONLINE", payload.get("desired_state"))
                || payload.get("desired_snapshot") == null
                || !Objects.equals(asLong(payload.get("revision")), permit.getDesiredRevision())) return false;
        SalesChannelAccount account = accountRepository.findById(listing.getSalesChannelAccountId()).orElse(null);
        if (account == null || !permit.getAccountId().equals(account.getProviderAccountId())) return false;
        SalesChannelListingGeneration current = generationRepository.lockCurrentGeneration(listing.getId()).orElse(null);
        return current != null && permit.getExpectedOldItemId().equals(current.getExternalItemId())
                && generationRepository.findCandidateByListingId(listing.getId()).isEmpty();
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String string) {
            try {
                return Long.parseLong(string);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private boolean quarantineLegacyRows(UUID listingId, LocalDateTime now) {
        boolean blocked = false;
        for (SalesChannelOperation legacy : operationRepository
                .findBySalesChannelListingIdOrderByCreatedAtAsc(listingId)) {
            if (legacy.getOperationType() == SalesChannelOperationType.ENSURE_LISTING) continue;
            SalesChannelOperationStatus status = legacy.getStatus();
            if (legacy.getOperationType() == SalesChannelOperationType.RECONCILE
                    && (status == SalesChannelOperationStatus.PENDING
                    || status == SalesChannelOperationStatus.RUNNING
                    || status == SalesChannelOperationStatus.UNKNOWN)) {
                blocked = true;
            } else if (status == SalesChannelOperationStatus.PENDING
                    && legacy.getAttemptCount() == 0 && legacy.getStartedAt() == null) {
                legacy.supersede(now);
            } else if (status == SalesChannelOperationStatus.PENDING
                    || status == SalesChannelOperationStatus.RUNNING
                    || status == SalesChannelOperationStatus.UNKNOWN) {
                blocked = true;
            }
        }
        return blocked;
    }

    private String extractExternalItemId(Map<String, Object> result) {
        if (result == null) return null;
        Object value = result.get("external_item_id");
        String itemId = value == null ? "" : String.valueOf(value).trim();
        return itemId.isBlank() ? null : itemId;
    }

    private void projectConvergenceIdentity(SalesChannelListing listing, String externalItemId,
                                            LocalDateTime now) {
        SalesChannelListingGeneration current = generationRepository.lockCurrentGeneration(listing.getId()).orElse(null);
        if (current != null && externalItemId.equals(current.getExternalItemId())) return;

        SalesChannelListingGeneration mapped = generationRepository
                .findBySalesChannelAccountIdAndExternalItemId(listing.getSalesChannelAccountId(), externalItemId)
                .orElse(null);
        if (mapped != null && (!mapped.getSalesChannelListingId().equals(listing.getId())
                || mapped.getGenerationState() != SalesChannelGenerationState.CURRENT)) {
            throw new IllegalStateException("CONVERGENCE_GENERATION_IDENTITY_CONFLICT");
        }
        if (current != null) {
            current.retire(now);
            generationRepository.save(current);
        }
        SalesChannelListingGeneration projected = new SalesChannelListingGeneration(
                listing.getId(), listing.getSalesChannelAccountId(),
                generationRepository.nextGenerationNumber(listing.getId()), externalItemId,
                SalesChannelGenerationState.CURRENT, "CONVERGENCE");
        projected.setActivatedAt(now);
        if (current != null) projected.setReplacesGenerationId(current.getId());
        generationRepository.saveAndFlush(projected);
    }

    private LocalDateTime nextReplayAt(int attemptCount) {
        long seconds = Math.min(900L, 1L << Math.min(9, Math.max(0, attemptCount - 1)));
        return LocalDateTime.now().plusSeconds(seconds);
    }

    private SalesChannelOperation findOperation(UUID operationId) {
        return operationRepository.findById(operationId)
                .orElseThrow(() -> new IllegalArgumentException("Sales channel operation does not exist"));
    }

    private SalesChannelOperation lockOperation(UUID operationId) {
        return operationRepository.findByIdForUpdate(operationId)
                .orElseThrow(() -> new IllegalArgumentException("Sales channel operation does not exist"));
    }

    private SalesChannelListing lockListing(UUID listingId) {
        return listingRepository.findByIdForUpdate(listingId)
                .orElseThrow(() -> new IllegalArgumentException("Sales channel listing does not exist"));
    }
}
