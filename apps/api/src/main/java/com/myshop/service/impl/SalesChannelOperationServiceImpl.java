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
        if (!isCompatible(operationType, listing.getDesiredState())) {
            throw new IllegalStateException("Operation type is incompatible with the listing desired state");
        }
        if (operationType != SalesChannelOperationType.RECONCILE
                && operationRepository.hasUnresolvedUnknown(listingId)) {
            throw new IllegalStateException("An UNKNOWN operation must be reconciled before another operation");
        }
        SalesChannelOperation operation = new SalesChannelOperation(
                listing.getId(), operationType, listing.getDesiredRevision(), idempotencyKey, payload);
        return operationRepository.saveAndFlush(operation);
    }

    /** Returns false when the operation is terminal, already claimed, or stale. */
    @Transactional
    public boolean markRunning(UUID operationId) {
        SalesChannelOperation snapshot = findOperation(operationId);
        SalesChannelListing listing = lockListing(snapshot.getSalesChannelListingId());
        SalesChannelOperation operation = lockOperation(operationId);
        LocalDateTime now = LocalDateTime.now();
        if (operation.getStatus() != SalesChannelOperationStatus.PENDING) return false;
        if (operation.getDesiredRevision() != listing.getDesiredRevision()) {
            operation.supersede(now);
            return false;
        }
        if (!isCompatible(operation.getOperationType(), listing.getDesiredState())) {
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
        if (operation.getOperationType() == SalesChannelOperationType.RECONCILE) {
            LocalDateTime latestUnknown = operationRepository
                    .findLatestUnknownFinishedAt(listing.getId(), operation.getDesiredRevision())
                    .orElse(null);
            if (latestUnknown != null && !now.isAfter(latestUnknown)) {
                now = latestUnknown.plusNanos(1_000);
            }
        }
        if (!operation.start(now)) return false;
        listing.setStatus(runningStatus(operation.getOperationType()));
        return true;
    }

    @Transactional
    public boolean markSucceeded(UUID operationId, Map<String, Object> result, String appliedFingerprint) {
        SalesChannelOperation snapshot = findOperation(operationId);
        SalesChannelListing listing = lockListing(snapshot.getSalesChannelListingId());
        SalesChannelOperation operation = lockOperation(operationId);
        LocalDateTime now = LocalDateTime.now();
        if (!operation.succeed(result, now)) return false;
        if (operation.getDesiredRevision() != listing.getDesiredRevision()) return true;

        listing.advanceAppliedRevision(operation.getDesiredRevision());
        listing.setStatus(succeededStatus(operation, listing));
        listing.setAppliedFingerprint(appliedFingerprint);
        listing.setLastSyncedAt(now);
        listing.setLastErrorCode(null);
        listing.setLastErrorMessage(null);
        return true;
    }

    @Transactional
    public boolean markFailed(UUID operationId, String errorCode, String errorMessage) {
        SalesChannelOperation snapshot = findOperation(operationId);
        SalesChannelListing listing = lockListing(snapshot.getSalesChannelListingId());
        SalesChannelOperation operation = lockOperation(operationId);
        LocalDateTime now = LocalDateTime.now();
        if (!operation.fail(errorCode, errorMessage, now)) return false;
        if (operation.getOperationType() == SalesChannelOperationType.RECONCILE) {
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
