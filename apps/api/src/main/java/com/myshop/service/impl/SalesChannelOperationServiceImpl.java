package com.myshop.service.impl;

import com.myshop.constant.SalesChannelListingDesiredState;
import com.myshop.constant.SalesChannelListingStatus;
import com.myshop.constant.SalesChannelOperationStatus;
import com.myshop.constant.SalesChannelOperationType;
import com.myshop.entity.SalesChannelAccount;
import com.myshop.entity.SalesChannelListing;
import com.myshop.entity.SalesChannelOperation;
import com.myshop.repository.SalesChannelAccountRepository;
import com.myshop.repository.SalesChannelListingRepository;
import com.myshop.repository.SalesChannelOperationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SalesChannelOperationServiceImpl {

    private final SalesChannelAccountRepository accountRepository;
    private final SalesChannelListingRepository listingRepository;
    private final SalesChannelOperationRepository operationRepository;

    @Transactional
    public SalesChannelOperation request(UUID listingId, SalesChannelOperationType operationType,
                                         Map<String, Object> payload) {
        Objects.requireNonNull(operationType, "operationType is required");
        SalesChannelListing listing = lockListing(listingId);
        SalesChannelAccount account = accountRepository.findById(listing.getSalesChannelAccountId())
                .orElseThrow(() -> new IllegalStateException("Listing account is missing"));
        String idempotencyKey = account.getProvider().name() + ":" + listing.getId() + ":"
                + operationType.name() + ":" + listing.getDesiredRevision();
        var existing = operationRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) return existing.get();
        if (operationType != SalesChannelOperationType.RECONCILE
                && "EXTERNAL_ITEM_ID_CONFLICT".equals(listing.getLastErrorCode())) {
            throw new IllegalStateException("External item identity conflict must be resolved before more provider writes");
        }
        if (!isCompatible(operationType, listing.getDesiredState())) {
            throw new IllegalStateException("Operation type is incompatible with the listing desired state");
        }
        SalesChannelOperation operation = new SalesChannelOperation(
                listing.getId(), operationType, listing.getDesiredRevision(), idempotencyKey, payload);
        return operationRepository.saveAndFlush(operation);
    }

    /** Starts a new read-only reconciliation episode for a target that remains UNKNOWN. */
    @Transactional
    public SalesChannelOperation requestReconciliation(UUID targetOperationId) {
        SalesChannelOperation targetSnapshot = findOperation(targetOperationId);
        SalesChannelListing listing = lockListing(targetSnapshot.getSalesChannelListingId());
        SalesChannelOperation target = lockOperation(targetOperationId);
        boolean identityConflict = target.getStatus() == SalesChannelOperationStatus.SUCCEEDED
                && listing.getStatus() == SalesChannelListingStatus.UNKNOWN
                && "EXTERNAL_ITEM_ID_CONFLICT".equals(listing.getLastErrorCode());
        if (target.getStatus() != SalesChannelOperationStatus.UNKNOWN && !identityConflict) {
            throw new IllegalStateException("Only an UNKNOWN operation can be reconciled");
        }
        List<SalesChannelOperation> operations = operationRepository
                .findBySalesChannelListingIdOrderByCreatedAtAsc(listing.getId());
        for (SalesChannelOperation operation : operations) {
            if (operation.getOperationType() == SalesChannelOperationType.RECONCILE
                    && targetOperationId.toString().equals(operation.getPayload().get("target_operation_id"))
                    && (operation.getStatus() == SalesChannelOperationStatus.PENDING
                    || operation.getStatus() == SalesChannelOperationStatus.RUNNING)) {
                return operation;
            }
        }
        SalesChannelAccount account = accountRepository.findById(listing.getSalesChannelAccountId())
                .orElseThrow(() -> new IllegalStateException("Listing account is missing"));
        long episode = operations.stream()
                .filter(operation -> operation.getOperationType() == SalesChannelOperationType.RECONCILE)
                .filter(operation -> targetOperationId.toString().equals(
                        operation.getPayload().get("target_operation_id")))
                .count() + 1;
        String idempotencyKey = account.getProvider().name() + ":" + listing.getId()
                + ":RECONCILE:" + targetOperationId + ":" + episode;
        Map<String, Object> payload = Map.of(
                "target_operation_id", targetOperationId.toString(),
                "target_request_id", target.getIdempotencyKey());
        return operationRepository.saveAndFlush(new SalesChannelOperation(listing.getId(),
                SalesChannelOperationType.RECONCILE, target.getDesiredRevision(), idempotencyKey, payload));
    }

    /** Returns false when the operation is terminal, already claimed, or stale. */
    @Transactional
    public boolean markRunning(UUID operationId) {
        SalesChannelOperation snapshot = findOperation(operationId);
        SalesChannelListing listing = lockListing(snapshot.getSalesChannelListingId());
        SalesChannelOperation operation = lockOperation(operationId);
        LocalDateTime now = LocalDateTime.now();
        if (operation.getStatus() != SalesChannelOperationStatus.PENDING) return false;
        if (operation.getNextAttemptAt() != null && operation.getNextAttemptAt().isAfter(now)) return false;
        if (operation.getOperationType() != SalesChannelOperationType.RECONCILE
                && "EXTERNAL_ITEM_ID_CONFLICT".equals(listing.getLastErrorCode())) return false;
        if (operation.getOperationType() != SalesChannelOperationType.RECONCILE
                && operation.getDesiredRevision() != listing.getDesiredRevision()) {
            operation.supersede(now);
            return false;
        }
        if (operation.getOperationType() != SalesChannelOperationType.RECONCILE
                && !isCompatible(operation.getOperationType(), listing.getDesiredState())) {
            operation.supersede(now);
            return false;
        }
        if (operation.getOperationType() == SalesChannelOperationType.PUBLISH
                && listing.getExternalItemId() != null) {
            operation.supersede(now);
            return false;
        }
        if (operationRepository.existsBySalesChannelListingIdAndStatus(
                listing.getId(), SalesChannelOperationStatus.RUNNING)) {
            return false;
        }
        if (operation.getOperationType() != SalesChannelOperationType.RECONCILE
                && operationRepository.hasUnresolvedUnknown(listing.getId())) {
            return false;
        }
        if (operation.getOperationType() == SalesChannelOperationType.SYNC
                && listing.getExternalItemId() == null) {
            boolean publishInFlight = operationRepository.existsBySalesChannelListingIdAndOperationTypeAndStatus(
                    listing.getId(), SalesChannelOperationType.PUBLISH, SalesChannelOperationStatus.PENDING)
                    || operationRepository.existsBySalesChannelListingIdAndOperationTypeAndStatus(
                    listing.getId(), SalesChannelOperationType.PUBLISH, SalesChannelOperationStatus.RUNNING)
                    || operationRepository.existsBySalesChannelListingIdAndOperationTypeAndStatus(
                    listing.getId(), SalesChannelOperationType.PUBLISH, SalesChannelOperationStatus.UNKNOWN);
            if (publishInFlight) return false;
            operation.supersede(now);
            return false;
        }
        if (operation.getOperationType() == SalesChannelOperationType.RECONCILE) {
            LocalDateTime latestUnknown = operationRepository
                    .findLatestUnknownFinishedAt(listing.getId(), operation.getDesiredRevision())
                    .orElse(null);
            if (latestUnknown != null && !now.isAfter(latestUnknown)) {
                now = latestUnknown.plusNanos(1_000);
            }
        }
        if (!operation.start(now)) return false;
        if (operation.getOperationType() != SalesChannelOperationType.RECONCILE) {
            listing.setStatus(runningStatus(operation.getOperationType()));
        }
        return true;
    }

    /** Converts a crashed side-effect attempt into UNKNOWN after its execution lease expires. */
    @Transactional
    public boolean recoverStaleRunningAsUnknown(UUID operationId, LocalDateTime staleBefore) {
        SalesChannelOperation snapshot = findOperation(operationId);
        if (snapshot.getOperationType() == SalesChannelOperationType.RECONCILE) {
            throw new IllegalArgumentException("Read-only reconciliation must use safe stale-run recovery");
        }
        SalesChannelListing listing = lockListing(snapshot.getSalesChannelListingId());
        SalesChannelOperation operation = lockOperation(operationId);
        if (operation.getStatus() != SalesChannelOperationStatus.RUNNING
                || operation.getStartedAt() == null || !operation.getStartedAt().isBefore(staleBefore)) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        if (!operation.markUnknown("WORKER_ATTEMPT_STALE",
                "The worker stopped before recording a definitive provider result", now)) return false;
        if (operation.getDesiredRevision() == listing.getDesiredRevision()) {
            listing.setStatus(SalesChannelListingStatus.UNKNOWN);
            listing.setLastErrorCode("WORKER_ATTEMPT_STALE");
            listing.setLastErrorMessage("The worker stopped before recording a definitive provider result");
        }
        return true;
    }

    @Transactional
    public boolean markSucceeded(UUID operationId, Map<String, Object> result, String appliedFingerprint) {
        SalesChannelOperation snapshot = findOperation(operationId);
        SalesChannelListing listing = lockListing(snapshot.getSalesChannelListingId());
        SalesChannelOperation operation = lockOperation(operationId);
        if (operation.getOperationType() == SalesChannelOperationType.RECONCILE
                && operationRepository.hasUnresolvedUnknown(listing.getId())) {
            throw new IllegalStateException("A reconciliation cannot succeed until its UNKNOWN target is resolved");
        }
        LocalDateTime now = LocalDateTime.now();
        if (!operation.succeed(result, now)) return false;
        if (operation.getOperationType() != SalesChannelOperationType.RECONCILE) {
            String externalItemId = extractExternalItemId(result);
            if (operation.getOperationType() == SalesChannelOperationType.PUBLISH && externalItemId == null) {
                throw new IllegalArgumentException("A successful PUBLISH result must include item_id");
            }
            if (externalItemId != null && listing.bindExternalItemId(externalItemId)
                    == SalesChannelListing.ExternalItemBindingResult.CONFLICT) return true;
        }
        if (operation.getDesiredRevision() != listing.getDesiredRevision()) return true;

        listing.advanceAppliedRevision(operation.getDesiredRevision());
        listing.setStatus(succeededStatus(operation, listing));
        listing.setAppliedFingerprint(appliedFingerprint);
        listing.setLastSyncedAt(now);
        listing.setLastErrorCode(null);
        listing.setLastErrorMessage(null);
        return true;
    }

    /** Resolves a target UNKNOWN operation only while its same-listing reconciliation is RUNNING. */
    @Transactional
    public boolean resolveUnknownAsSucceeded(UUID reconciliationOperationId, UUID targetOperationId,
                                             Map<String, Object> targetResult,
                                             Map<String, Object> reconciliationResult,
                                             String appliedFingerprint) {
        SalesChannelOperation targetSnapshot = findOperation(targetOperationId);
        SalesChannelListing listing = lockListing(targetSnapshot.getSalesChannelListingId());
        SalesChannelOperation target = lockOperation(targetOperationId);
        SalesChannelOperation reconciliation = lockOperation(reconciliationOperationId);
        validateReconciliationTarget(reconciliation, target);
        requireOutcome(reconciliationResult, "SUCCEEDED");
        LocalDateTime now = LocalDateTime.now();
        String externalItemId = extractExternalItemId(targetResult);
        if (target.getOperationType() == SalesChannelOperationType.PUBLISH && externalItemId == null) {
            throw new IllegalArgumentException("A reconciled successful PUBLISH result must include item_id");
        }
        if (target.getStatus() == SalesChannelOperationStatus.SUCCEEDED) {
            String savedItemId = extractExternalItemId(target.getResult());
            if (externalItemId != null && savedItemId != null && !externalItemId.equals(savedItemId)) {
                throw new IllegalStateException("Reconciliation conflicts with the target operation result");
            }
            if (!reconciliation.succeed(reconciliationResult, now)) {
                throw new IllegalStateException("Reconciliation operation is no longer RUNNING");
            }
            return true;
        }
        if (!target.resolveUnknownAsSucceeded(targetResult, now)) return false;
        if (target.getOperationType() != SalesChannelOperationType.RECONCILE
                && externalItemId != null
                && listing.bindExternalItemId(externalItemId)
                == SalesChannelListing.ExternalItemBindingResult.CONFLICT) {
            if (!reconciliation.succeed(reconciliationResult, now)) {
                throw new IllegalStateException("Reconciliation operation is no longer RUNNING");
            }
            return true;
        }
        if (target.getDesiredRevision() == listing.getDesiredRevision()) {
            listing.advanceAppliedRevision(target.getDesiredRevision());
            listing.setStatus(succeededStatus(target, listing));
            listing.setAppliedFingerprint(appliedFingerprint);
            listing.setLastSyncedAt(now);
            listing.setLastErrorCode(null);
            listing.setLastErrorMessage(null);
        }
        if (!reconciliation.succeed(reconciliationResult, now)) {
            throw new IllegalStateException("Reconciliation operation is no longer RUNNING");
        }
        return true;
    }

    /** Reopens a target only when reconciliation explicitly proves a retryable non-effect. */
    @Transactional
    public boolean resolveUnknownAsRetryable(UUID reconciliationOperationId, UUID targetOperationId,
                                             Map<String, Object> reconciliationResult,
                                             String errorCode, String errorMessage,
                                             LocalDateTime nextAttemptAt) {
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt is required");
        SalesChannelOperation targetSnapshot = findOperation(targetOperationId);
        SalesChannelListing listing = lockListing(targetSnapshot.getSalesChannelListingId());
        SalesChannelOperation target = lockOperation(targetOperationId);
        SalesChannelOperation reconciliation = lockOperation(reconciliationOperationId);
        validateReconciliationTarget(reconciliation, target);
        requireOutcome(reconciliationResult, "FAILED");
        if (!Boolean.TRUE.equals(reconciliationResult.get("target_retryable"))) {
            throw new IllegalArgumentException("Reconciliation must confirm target_retryable=true");
        }
        LocalDateTime now = LocalDateTime.now();
        if (!target.resolveUnknownAsRetryable(errorCode, errorMessage, nextAttemptAt)) return false;
        if (target.getDesiredRevision() == listing.getDesiredRevision()) {
            listing.setLastErrorCode(null);
            listing.setLastErrorMessage(null);
            listing.setStatus(retryableStatus(listing));
        }
        if (!reconciliation.succeed(reconciliationResult, now)) {
            throw new IllegalStateException("Reconciliation operation is no longer RUNNING");
        }
        return true;
    }

    /** Resolves an unknown target as permanently failed when the Agent proves non-execution but disallows retry. */
    @Transactional
    public boolean resolveUnknownAsFailed(UUID reconciliationOperationId, UUID targetOperationId,
                                          Map<String, Object> targetResult,
                                          Map<String, Object> reconciliationResult,
                                          String errorCode, String errorMessage) {
        SalesChannelOperation targetSnapshot = findOperation(targetOperationId);
        SalesChannelListing listing = lockListing(targetSnapshot.getSalesChannelListingId());
        SalesChannelOperation target = lockOperation(targetOperationId);
        SalesChannelOperation reconciliation = lockOperation(reconciliationOperationId);
        validateReconciliationTarget(reconciliation, target);
        requireOutcome(reconciliationResult, "FAILED");
        if (!Boolean.FALSE.equals(reconciliationResult.get("target_retryable"))) {
            throw new IllegalArgumentException("Reconciliation must confirm target_retryable=false");
        }
        LocalDateTime now = LocalDateTime.now();
        if (!target.resolveUnknownAsFailed(targetResult, errorCode, errorMessage, now)) return false;
        if (target.getDesiredRevision() == listing.getDesiredRevision()) {
            listing.setStatus(SalesChannelListingStatus.ERROR);
            listing.setLastErrorCode(errorCode);
            listing.setLastErrorMessage(errorMessage);
        }
        if (!reconciliation.succeed(reconciliationResult, now)) {
            throw new IllegalStateException("Reconciliation operation is no longer RUNNING");
        }
        return true;
    }

    @Transactional
    public boolean reschedule(UUID operationId, String errorCode, String errorMessage,
                              LocalDateTime nextAttemptAt) {
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt is required");
        SalesChannelOperation snapshot = findOperation(operationId);
        lockListing(snapshot.getSalesChannelListingId());
        SalesChannelOperation operation = lockOperation(operationId);
        return operation.reschedule(errorCode, errorMessage, nextAttemptAt);
    }

    @Transactional
    public boolean markFailed(UUID operationId, String errorCode, String errorMessage) {
        SalesChannelOperation snapshot = findOperation(operationId);
        SalesChannelListing listing = lockListing(snapshot.getSalesChannelListingId());
        SalesChannelOperation operation = lockOperation(operationId);
        LocalDateTime now = LocalDateTime.now();
        if (!operation.fail(errorCode, errorMessage, now)) return false;
        if (operation.getOperationType() == SalesChannelOperationType.RECONCILE) {
            if ("EXTERNAL_ITEM_ID_CONFLICT".equals(listing.getLastErrorCode())) return true;
            SalesChannelOperation unresolvedUnknown = operationRepository
                    .findLatestUnresolvedUnknown(listing.getId())
                    .orElse(null);
            if (unresolvedUnknown != null) {
                listing.setStatus(SalesChannelListingStatus.UNKNOWN);
                listing.setLastErrorCode(unresolvedUnknown.getLastErrorCode());
                listing.setLastErrorMessage(unresolvedUnknown.getLastErrorMessage());
                return true;
            }
        }
        if (operation.getDesiredRevision() == listing.getDesiredRevision()) {
            listing.setStatus(SalesChannelListingStatus.ERROR);
            listing.setLastErrorCode(errorCode);
            listing.setLastErrorMessage(errorMessage);
        }
        return true;
    }

    @Transactional
    public boolean markUnknown(UUID operationId, String errorCode, String errorMessage) {
        SalesChannelOperation snapshot = findOperation(operationId);
        SalesChannelListing listing = lockListing(snapshot.getSalesChannelListingId());
        SalesChannelOperation operation = lockOperation(operationId);
        LocalDateTime now = LocalDateTime.now();
        if (!operation.markUnknown(errorCode, errorMessage, now)) return false;
        if (operation.getDesiredRevision() == listing.getDesiredRevision()) {
            listing.setStatus(SalesChannelListingStatus.UNKNOWN);
            listing.setLastErrorCode(errorCode);
            listing.setLastErrorMessage(errorMessage);
        }
        return true;
    }

    @Transactional(readOnly = true)
    public List<SalesChannelOperation> findReadyPending(LocalDateTime now, int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        return operationRepository.findReadyPendingAt(now, limit);
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

    private void validateReconciliationTarget(SalesChannelOperation reconciliation, SalesChannelOperation target) {
        if (reconciliation.getOperationType() != SalesChannelOperationType.RECONCILE
                || reconciliation.getStatus() != SalesChannelOperationStatus.RUNNING
                || !reconciliation.getSalesChannelListingId().equals(target.getSalesChannelListingId())
                || reconciliation.getDesiredRevision() < target.getDesiredRevision()) {
            throw new IllegalArgumentException("A RUNNING reconciliation for the target listing is required");
        }
    }

    private void requireOutcome(Map<String, Object> reconciliationResult, String expectedOutcome) {
        if (reconciliationResult == null
                || !expectedOutcome.equals(reconciliationResult.get("target_outcome"))) {
            throw new IllegalArgumentException("Reconciliation result target_outcome must be " + expectedOutcome);
        }
    }

    private String extractExternalItemId(Map<String, Object> result) {
        if (result == null) return null;
        Object value = result.containsKey("item_id") ? result.get("item_id") : result.get("external_item_id");
        if (value == null) return null;
        String itemId = value.toString();
        return itemId.isBlank() ? null : itemId;
    }

    private SalesChannelListingStatus retryableStatus(SalesChannelListing listing) {
        if (listing.getDesiredState() == SalesChannelListingDesiredState.OFFLINE) {
            return SalesChannelListingStatus.OFFLINE;
        }
        return listing.getExternalItemId() == null
                ? SalesChannelListingStatus.DRAFT : SalesChannelListingStatus.ONLINE;
    }

    private SalesChannelListingStatus runningStatus(SalesChannelOperationType type) {
        return switch (type) {
            case PUBLISH -> SalesChannelListingStatus.PUBLISHING;
            case OFFLINE, DELETE -> SalesChannelListingStatus.OFFLINING;
            case SYNC, RECONCILE -> SalesChannelListingStatus.SYNCING;
        };
    }

    private SalesChannelListingStatus succeededStatus(SalesChannelOperation operation, SalesChannelListing listing) {
        return switch (operation.getOperationType()) {
            case PUBLISH -> SalesChannelListingStatus.ONLINE;
            case SYNC -> listing.getDesiredState() == SalesChannelListingDesiredState.ONLINE
                    ? SalesChannelListingStatus.ONLINE : SalesChannelListingStatus.OFFLINE;
            case OFFLINE, DELETE -> SalesChannelListingStatus.OFFLINE;
            case RECONCILE -> listing.getDesiredState() == SalesChannelListingDesiredState.ONLINE
                    ? SalesChannelListingStatus.ONLINE : SalesChannelListingStatus.OFFLINE;
        };
    }

    private boolean isCompatible(SalesChannelOperationType type, SalesChannelListingDesiredState desiredState) {
        return switch (type) {
            case PUBLISH -> desiredState == SalesChannelListingDesiredState.ONLINE;
            case OFFLINE, DELETE -> desiredState == SalesChannelListingDesiredState.OFFLINE;
            case SYNC, RECONCILE -> true;
        };
    }
}
