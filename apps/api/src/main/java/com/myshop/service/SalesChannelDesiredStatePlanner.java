package com.myshop.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.constant.SalesChannelGenerationState;
import com.myshop.constant.SalesChannelListingDesiredState;
import com.myshop.constant.SalesChannelListingStatus;
import com.myshop.constant.SalesChannelOperationStatus;
import com.myshop.constant.SalesChannelOperationType;
import com.myshop.entity.Product;
import com.myshop.entity.SalesChannelAccount;
import com.myshop.entity.SalesChannelListing;
import com.myshop.entity.SalesChannelOperation;
import com.myshop.exception.BusinessException;
import com.myshop.constant.ErrorCode;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.SalesChannelAccountRepository;
import com.myshop.repository.SalesChannelListingGenerationRepository;
import com.myshop.repository.SalesChannelListingRepository;
import com.myshop.repository.SalesChannelOperationRepository;
import com.myshop.service.impl.SalesChannelOperationServiceImpl;
import com.myshop.saleschannel.convergence.DesiredListingSnapshotFingerprints;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.ListingSnapshot;
import com.myshop.saleschannel.xianyu.model.XianyuProviderConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Plans business intent and emits only frozen ENSURE_LISTING commands. */
@Service
@RequiredArgsConstructor
public class SalesChannelDesiredStatePlanner {

    private final ObjectMapper objectMapper;
    private final ProductRepository productRepository;
    private final SalesChannelAccountRepository accountRepository;
    private final SalesChannelListingRepository listingRepository;
    private final SalesChannelListingGenerationRepository generationRepository;
    private final SalesChannelOperationRepository operationRepository;
    private final SalesChannelOperationServiceImpl operationService;
    private final XianyuDesiredListingAssembler desiredListingAssembler;

    @Transactional
    public SalesChannelListing saveConfig(UUID listingId, XianyuProviderConfig config) {
        if (config == null) throw new IllegalArgumentException("Xianyu provider config is required");
        config.validateForSave();
        SalesChannelListing listing = lockListing(listingId);
        SalesChannelListingManagementPolicy.requireProviderWritesAllowed(listing);
        listing.setProviderConfig(objectMapper.convertValue(config, Map.class));
        return planLocked(listing, false);
    }

    @Transactional
    public SalesChannelListing setEnabled(UUID listingId, boolean enabled) {
        SalesChannelListing listing = lockListing(listingId);
        if (enabled) {
            SalesChannelListingManagementPolicy.requireProviderWritesAllowed(listing);
            preflightForEnable(listing);
        }
        listing.setEnabled(enabled);
        if (!SalesChannelListingManagementPolicy.providerWritesAllowed(listing)) {
            return listingRepository.saveAndFlush(listing);
        }
        return planLocked(listing, false);
    }

    @Transactional
    public SalesChannelListing refresh(UUID listingId) {
        SalesChannelListing listing = lockListing(listingId);
        if (!SalesChannelListingManagementPolicy.providerWritesAllowed(listing)) return listing;
        return planLocked(listing, false);
    }

    /** A retry reuses the desired revision but gives a terminal failure a new command identity. */
    @Transactional
    public void retry(UUID listingId) {
        SalesChannelListing listing = lockListing(listingId);
        SalesChannelListingManagementPolicy.requireProviderWritesAllowed(listing);
        planLocked(listing, true);
    }

    private void preflightForEnable(SalesChannelListing listing) {
        Product product = productRepository.findById(listing.getProductId()).orElse(null);
        if (product == null || !product.isEnabled() || !Integer.valueOf(0).equals(product.getIsDeleted())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "商品当前不可上架");
        }
        try {
            desiredListingAssembler.assemble(listing, product);
        } catch (IllegalArgumentException exception) {
            BusinessException failure = new BusinessException(ErrorCode.BAD_REQUEST, exception.getMessage());
            failure.initCause(exception);
            throw failure;
        }
    }

    private SalesChannelListing planLocked(SalesChannelListing listing, boolean explicitRetry) {
        SalesChannelAccount account = accountRepository.findById(listing.getSalesChannelAccountId()).orElse(null);
        Product product = productRepository.findById(listing.getProductId()).orElse(null);
        SalesChannelListingDesiredState desiredState = effectiveDesiredState(listing, account, product);

        ListingSnapshot snapshot = null;
        String nextFingerprint;
        if (desiredState == SalesChannelListingDesiredState.ONLINE) {
            try {
                snapshot = desiredListingAssembler.assemble(listing, product);
                nextFingerprint = DesiredListingSnapshotFingerprints.online(objectMapper, snapshot);
            } catch (IllegalArgumentException exception) {
                listing.setDesiredState(desiredState);
                listing.setStatus(SalesChannelListingStatus.ERROR);
                listing.setLastErrorCode("DESIRED_LISTING_INVALID");
                listing.setLastErrorMessage(safeMessage(exception));
                supersedeNeverSentEnsures(listing);
                return listingRepository.saveAndFlush(listing);
            }
        } else {
            nextFingerprint = DesiredListingSnapshotFingerprints.offline(
                    objectMapper, listing.getId().toString().toLowerCase());
        }

        boolean changed = listing.getDesiredState() != desiredState
                || !nextFingerprint.equals(listing.getDesiredFingerprint());
        if (changed) {
            listing.setDesiredState(desiredState);
            listing.setDesiredFingerprint(nextFingerprint);
            listing.incrementDesiredRevision();
            supersedeUnstartedOlderEnsures(listing);
        }
        listingRepository.saveAndFlush(listing);

        if (!SalesChannelListingManagementPolicy.providerWritesAllowed(listing)) return listing;
        String blocker = quarantineLegacyExecution(listing);
        if (blocker != null) {
            setCutoverBlocker(listing, blocker);
            return listingRepository.saveAndFlush(listing);
        }
        if ("LEGACY_OPERATION_UNRESOLVED".equals(listing.getLastErrorCode())
                || "LEGACY_CANDIDATE_UNRESOLVED".equals(listing.getLastErrorCode())) {
            listing.setLastErrorCode(null);
            listing.setLastErrorMessage(null);
        }

        List<SalesChannelOperation> operations = operationRepository
                .findBySalesChannelListingIdOrderByCreatedAtAsc(listing.getId());
        boolean olderEnsureLive = operations.stream()
                .filter(operation -> operation.getOperationType() == SalesChannelOperationType.ENSURE_LISTING)
                .filter(operation -> operation.getDesiredRevision() < listing.getDesiredRevision())
                .anyMatch(operation -> operation.getStatus() == SalesChannelOperationStatus.PENDING
                        || operation.getStatus() == SalesChannelOperationStatus.RUNNING
                        || operation.getStatus() == SalesChannelOperationStatus.UNKNOWN);
        if (olderEnsureLive) return listing;

        List<SalesChannelOperation> sameRevision = operations.stream()
                .filter(operation -> operation.getOperationType() == SalesChannelOperationType.ENSURE_LISTING)
                .filter(operation -> operation.getDesiredRevision() == listing.getDesiredRevision())
                .toList();
        boolean live = sameRevision.stream().anyMatch(operation ->
                operation.getStatus() == SalesChannelOperationStatus.PENDING
                        || operation.getStatus() == SalesChannelOperationStatus.RUNNING);
        if (live) return listing;

        if (!changed && listing.getAppliedRevision() >= listing.getDesiredRevision()
                && nextFingerprint.equals(listing.getAppliedFingerprint())) return listing;
        boolean latestFailed = !sameRevision.isEmpty()
                && sameRevision.get(sameRevision.size() - 1).getStatus() == SalesChannelOperationStatus.FAILED;
        if (latestFailed && !explicitRetry) return listing;

        enqueueEnsure(listing, account, desiredState, snapshot, nextFingerprint);
        return listing;
    }

    /**
     * Legacy rows can be discarded only when the database proves no attempt
     * started. All uncertain provider history and candidates stop the cutover.
     */
    private String quarantineLegacyExecution(SalesChannelListing listing) {
        if (generationRepository.findCandidateByListingId(listing.getId()).isPresent()
                || listing.getStatus() == SalesChannelListingStatus.REPLACEMENT_PENDING) {
            return "LEGACY_CANDIDATE_UNRESOLVED";
        }
        LocalDateTime now = LocalDateTime.now();
        for (SalesChannelOperation operation : operationRepository
                .findBySalesChannelListingIdOrderByCreatedAtAsc(listing.getId())) {
            if (operation.getOperationType() == SalesChannelOperationType.ENSURE_LISTING) continue;
            SalesChannelOperationStatus status = operation.getStatus();
            if (operation.getOperationType() == SalesChannelOperationType.RECONCILE) {
                if (status == SalesChannelOperationStatus.PENDING
                        || status == SalesChannelOperationStatus.RUNNING
                        || status == SalesChannelOperationStatus.UNKNOWN) return "LEGACY_OPERATION_UNRESOLVED";
                continue;
            }
            if (status == SalesChannelOperationStatus.PENDING
                    && operation.getAttemptCount() == 0 && operation.getStartedAt() == null) {
                operation.supersede(now);
            } else if (status == SalesChannelOperationStatus.RUNNING
                    || status == SalesChannelOperationStatus.UNKNOWN
                    || status == SalesChannelOperationStatus.PENDING) {
                return "LEGACY_OPERATION_UNRESOLVED";
            }
        }
        return null;
    }

    private void supersedeUnstartedOlderEnsures(SalesChannelListing listing) {
        LocalDateTime now = LocalDateTime.now();
        operationRepository.findBySalesChannelListingIdOrderByCreatedAtAsc(listing.getId()).stream()
                .filter(operation -> operation.getOperationType() == SalesChannelOperationType.ENSURE_LISTING)
                .filter(operation -> operation.getDesiredRevision() < listing.getDesiredRevision())
                .filter(operation -> operation.getStatus() == SalesChannelOperationStatus.PENDING)
                .filter(operation -> operation.getAttemptCount() == 0 && operation.getStartedAt() == null)
                .forEach(operation -> operation.supersede(now));
    }

    private void supersedeNeverSentEnsures(SalesChannelListing listing) {
        LocalDateTime now = LocalDateTime.now();
        operationRepository.findBySalesChannelListingIdOrderByCreatedAtAsc(listing.getId()).stream()
                .filter(operation -> operation.getOperationType() == SalesChannelOperationType.ENSURE_LISTING)
                .filter(operation -> operation.getStatus() == SalesChannelOperationStatus.PENDING)
                .filter(operation -> operation.getAttemptCount() == 0 && operation.getStartedAt() == null)
                .forEach(operation -> operation.supersede(now));
    }

    private void enqueueEnsure(SalesChannelListing listing, SalesChannelAccount account,
                               SalesChannelListingDesiredState desiredState,
                               ListingSnapshot snapshot, String fingerprint) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("account_id", account == null ? null : account.getProviderAccountId());
        payload.put("listing_key", listing.getId().toString().toLowerCase());
        payload.put("revision", listing.getDesiredRevision());
        payload.put("desired_state", desiredState.name());
        payload.put("desired_snapshot", snapshot == null ? null : objectMapper.convertValue(snapshot, Map.class));
        payload.put("desired_fingerprint", fingerprint);
        // The operation service freezes the safe request identity and enforces
        // the null generation-FK semantics for this command type.
        operationService.requestEnsureListing(listing.getId(), payload);
    }

    private SalesChannelListingDesiredState effectiveDesiredState(SalesChannelListing listing,
                                                                   SalesChannelAccount account, Product product) {
        return listing.isEnabled() && account != null && account.isEnabled()
                && product != null && product.isEnabled() && Integer.valueOf(0).equals(product.getIsDeleted())
                ? SalesChannelListingDesiredState.ONLINE : SalesChannelListingDesiredState.OFFLINE;
    }

    private void setCutoverBlocker(SalesChannelListing listing, String code) {
        listing.setStatus(SalesChannelListingStatus.ERROR);
        listing.setLastErrorCode(code);
        listing.setLastErrorMessage(code.equals("LEGACY_CANDIDATE_UNRESOLVED")
                ? "An unresolved legacy candidate generation requires manual review before Agent convergence"
                : "An unresolved legacy provider operation requires manual review before Agent convergence");
    }

    private SalesChannelListing lockListing(UUID listingId) {
        return listingRepository.findByIdForUpdate(listingId)
                .orElseThrow(() -> new IllegalArgumentException("Sales channel listing does not exist"));
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "The exact desired listing snapshot could not be assembled" : message;
    }
}
