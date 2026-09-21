package com.myshop.saleschannel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.constant.SalesChannelAccountStatus;
import com.myshop.constant.SalesChannelOperationStatus;
import com.myshop.constant.SalesChannelOperationType;
import com.myshop.entity.SalesChannelAccount;
import com.myshop.entity.SalesChannelListing;
import com.myshop.entity.SalesChannelOperation;
import com.myshop.repository.SalesChannelAccountRepository;
import com.myshop.repository.SalesChannelListingRepository;
import com.myshop.repository.SalesChannelOperationRepository;
import com.myshop.saleschannel.model.DesiredListingSnapshot;
import com.myshop.saleschannel.model.SalesChannelDtos.IntegrationEnvelope;
import com.myshop.saleschannel.model.SalesChannelDtos.IntegrationError;
import com.myshop.saleschannel.model.SalesChannelDtos.ListingOperationData;
import com.myshop.saleschannel.model.SalesChannelDtos.ListingSnapshot;
import com.myshop.saleschannel.model.SalesChannelDtos.MediaType;
import com.myshop.saleschannel.model.SalesChannelDtos.Outcome;
import com.myshop.saleschannel.model.SalesChannelDtos.PublishCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.ReconcileCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.ReconcileData;
import com.myshop.saleschannel.model.SalesChannelDtos.SkuRow;
import com.myshop.saleschannel.model.SalesChannelDtos.UpdateCommand;
import com.myshop.saleschannel.SalesChannelProviderClientException;
import com.myshop.saleschannel.xianyu.XianyuMediaMaterializer;
import com.myshop.saleschannel.xianyu.XianyuMediaMaterializer.MaterializedImage;
import com.myshop.service.SalesChannelDesiredStatePlanner;
import com.myshop.service.SalesChannelOperationClaimService;
import com.myshop.service.impl.SalesChannelOperationServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Durable operation executor. Provider calls are always outside database transactions. */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "sales-channel.worker", name = "enabled", havingValue = "true")
public class SalesChannelWorker {

    private final SalesChannelOperationClaimService claimService;
    private final SalesChannelOperationRepository operationRepository;
    private final SalesChannelListingRepository listingRepository;
    private final SalesChannelAccountRepository accountRepository;
    private final SalesChannelOperationServiceImpl operationService;
    private final SalesChannelProviderClient providerClient;
    private final SalesChannelDesiredStatePlanner planner;
    private final XianyuMediaMaterializer mediaMaterializer;
    private final ObjectMapper objectMapper;
    private final int batchSize;
    private final int maxAttempts;
    private final long staleRunningMs;

    public SalesChannelWorker(SalesChannelOperationClaimService claimService,
                              SalesChannelOperationRepository operationRepository,
                              SalesChannelListingRepository listingRepository,
                              SalesChannelAccountRepository accountRepository,
                              SalesChannelOperationServiceImpl operationService,
                              SalesChannelProviderClient providerClient,
                              SalesChannelDesiredStatePlanner planner,
                              XianyuMediaMaterializer mediaMaterializer,
                              ObjectMapper objectMapper,
                              @Value("${sales-channel.worker.batch-size:10}") int batchSize,
                              @Value("${sales-channel.worker.max-attempts:8}") int maxAttempts,
                              @Value("${sales-channel.worker.stale-running-ms:300000}") long staleRunningMs) {
        this.claimService = claimService;
        this.operationRepository = operationRepository;
        this.listingRepository = listingRepository;
        this.accountRepository = accountRepository;
        this.operationService = operationService;
        this.providerClient = providerClient;
        this.planner = planner;
        this.mediaMaterializer = mediaMaterializer;
        this.objectMapper = objectMapper;
        this.batchSize = Math.max(1, Math.min(batchSize, 100));
        this.maxAttempts = Math.max(1, Math.min(maxAttempts, 32));
        this.staleRunningMs = Math.max(60_000L, Math.min(staleRunningMs, 3_600_000L));
    }

    @Scheduled(fixedDelayString = "${sales-channel.worker.interval-ms:1000}")
    public void runBatch() {
        recoverStaleRunningOperations();
        List<UUID> ids = claimService.selectReadyIds(LocalDateTime.now(), batchSize);
        for (UUID id : ids) process(id);
    }

    private void recoverStaleRunningOperations() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime staleBefore = now.minusNanos(staleRunningMs * 1_000_000L);
        for (SalesChannelOperation stale : operationRepository.findStaleRunningBefore(staleBefore, batchSize)) {
            if (stale.getOperationType() == SalesChannelOperationType.RECONCILE) {
                if (stale.getAttemptCount() >= maxAttempts) {
                    operationService.markFailed(stale.getId(), "RECONCILE_MAX_ATTEMPTS",
                            "Read-only reconciliation worker lease expired at the retry limit");
                } else {
                    operationService.reschedule(stale.getId(), "RECONCILE_WORKER_STALE",
                            "The read-only reconciliation worker stopped before recording a result",
                            now.plus(backoff(stale.getAttemptCount())));
                }
                continue;
            }
            if (operationService.recoverStaleRunningAsUnknown(stale.getId(), staleBefore)) {
                ensureReconciliation(stale);
            }
        }
    }

    private void process(UUID operationId) {
        SalesChannelOperation candidate = operationRepository.findById(operationId).orElse(null);
        if (candidate == null || candidate.getStatus() != SalesChannelOperationStatus.PENDING) return;
        if (!operationService.markRunning(operationId)) {
            if (candidate.getOperationType() == SalesChannelOperationType.PUBLISH) {
                SalesChannelListing listing = listingRepository.findById(candidate.getSalesChannelListingId()).orElse(null);
                if (listing != null && listing.getExternalItemId() != null) refreshAfterOutcome(listing.getId());
            }
            return;
        }

        SalesChannelOperation operation = operationRepository.findById(operationId).orElse(null);
        if (operation == null) return;
        if (operation.getAttemptCount() > maxAttempts) {
            operationService.markFailed(operationId, "MAX_ATTEMPTS_EXCEEDED",
                    "Maximum provider attempt count was exceeded");
            return;
        }

        SalesChannelListing listing = listingRepository.findById(operation.getSalesChannelListingId()).orElse(null);
        if (listing == null) {
            operationService.markFailed(operationId, "LISTING_NOT_FOUND", "Sales channel listing no longer exists");
            return;
        }
        SalesChannelAccount account = accountRepository.findById(listing.getSalesChannelAccountId()).orElse(null);
        if (account == null) {
            operationService.markFailed(operationId, "ACCOUNT_NOT_FOUND", "Sales channel account no longer exists");
            return;
        }
        if (account.getStatus() != SalesChannelAccountStatus.CONNECTED) {
            operationService.markFailed(operationId, "ACCOUNT_AUTH_REQUIRED",
                    "The sales channel account must be verified before operations can run");
            return;
        }

        boolean sideEffectCallStarted = false;
        try {
            switch (operation.getOperationType()) {
                case RECONCILE -> executeReconciliation(operation, listing, account);
                case PUBLISH, SYNC, OFFLINE, DELETE -> {
                    DesiredListingSnapshot snapshot = desiredSnapshot(operation);
                    String fingerprint = string(operation.getPayload().get("desired_fingerprint"));
                    if (operation.getOperationType() == SalesChannelOperationType.PUBLISH
                            || operation.getOperationType() == SalesChannelOperationType.SYNC) {
                        if (operation.getOperationType() == SalesChannelOperationType.SYNC
                                && "PERSONAL".equals(snapshot.accountType())) {
                            operationService.markFailed(operationId, "UNSUPPORTED_ACCOUNT_CAPABILITY",
                                    "Personal accounts do not support automatic listing edits or price and stock sync");
                            return;
                        }
                        ListingSnapshot materialized = materialize(snapshot, account);
                        UpdateCommand command = new UpdateCommand(operation.getIdempotencyKey(),
                                account.getProviderAccountId(), operation.getDesiredRevision(), materialized);
                        if (operation.getOperationType() == SalesChannelOperationType.PUBLISH) {
                            sideEffectCallStarted = true;
                            handleListingEnvelope(operation, listing, providerClient.publish(new PublishCommand(
                                    command.requestId(), command.accountId(), command.desiredRevision(), command.listing())),
                                    fingerprint);
                        } else {
                            if (listing.getExternalItemId() == null) {
                                operationService.markFailed(operationId, "PUBLISH_REQUIRED",
                                        "A SYNC requires a bound external item id");
                                return;
                            }
                            sideEffectCallStarted = true;
                            handleListingEnvelope(operation, listing,
                                    providerClient.update(listing.getExternalItemId(), command), fingerprint);
                        }
                    } else {
                        UpdateCommand command = new UpdateCommand(operation.getIdempotencyKey(),
                                account.getProviderAccountId(), operation.getDesiredRevision(), null);
                        if (listing.getExternalItemId() == null) {
                            operationService.markSucceeded(operationId, Map.of("no_external_item", true), fingerprint);
                            return;
                        }
                        sideEffectCallStarted = true;
                        IntegrationEnvelope<ListingOperationData> envelope = operation.getOperationType()
                                == SalesChannelOperationType.OFFLINE
                                ? providerClient.offline(listing.getExternalItemId(), command)
                                : providerClient.delete(listing.getExternalItemId(), command);
                        handleListingEnvelope(operation, listing, envelope, fingerprint);
                    }
                }
            }
        } catch (SalesChannelProviderClientException exception) {
            if (operation.getOperationType() == SalesChannelOperationType.RECONCILE
                    && exception.getDisposition() == SalesChannelProviderClientException.Disposition.UNKNOWN) {
                retryReadOnly(operation, null);
            } else {
                handleClientFailure(operation, exception);
            }
        } catch (RuntimeException exception) {
            if (operation.getOperationType() == SalesChannelOperationType.RECONCILE) {
                retryReadOnly(operation, null);
                log.warn("Sales channel reconciliation {} failed in worker: {}", operationId,
                        exception.getClass().getSimpleName());
                return;
            }
            String code = "WORKER_EXECUTION_FAILED";
            if (sideEffectCallStarted) {
                if (operationService.markUnknown(operationId, code,
                        "Provider result could not be safely recorded; reconciliation is required")) {
                    ensureReconciliation(operation);
                }
            } else {
                operationService.markFailed(operationId, code, "Sales channel operation could not be prepared");
            }
            log.warn("Sales channel operation {} failed in worker: {}", operationId,
                    exception.getClass().getSimpleName());
        }
    }

    private ListingSnapshot materialize(DesiredListingSnapshot desired, SalesChannelAccount account) {
        List<String> mediaIds = new ArrayList<>();
        for (String imageUrl : desired.sourceImageUrls()) {
            MaterializedImage image = mediaMaterializer.readImage(imageUrl);
            var command = new com.myshop.saleschannel.model.SalesChannelDtos.MediaUploadCommand(
                    account.getProviderAccountId(), MediaType.IMAGE, image.filename(),
                    image.contentType(), image.content());
            var envelope = providerClient.uploadMedia(command);
            if (envelope.outcome() == Outcome.FAILED) {
                IntegrationError error = envelope.error();
                boolean retryable = error != null && Boolean.TRUE.equals(error.retryable());
                throw new SalesChannelProviderClientException(retryable
                        ? SalesChannelProviderClientException.Disposition.SAFE_RETRYABLE
                        : SalesChannelProviderClientException.Disposition.SAFE_PERMANENT,
                        error == null ? "MEDIA_UPLOAD_FAILED" : error.code(),
                        error == null ? "Xianyu media upload failed" : error.message());
            }
            if (envelope.outcome() != Outcome.SUCCEEDED || envelope.data() == null
                    || blank(envelope.data().mediaId())) {
                throw new SalesChannelProviderClientException(
                        SalesChannelProviderClientException.Disposition.SAFE_RETRYABLE,
                        "MEDIA_UPLOAD_UNCONFIRMED", "Xianyu media upload did not return a confirmed media id");
            }
            mediaIds.add(envelope.data().mediaId());
        }
        if (mediaIds.isEmpty()) {
            throw new SalesChannelProviderClientException(
                    SalesChannelProviderClientException.Disposition.SAFE_PERMANENT,
                    "IMAGE_REQUIRED", "At least one valid product image is required");
        }
        List<SkuRow> skuRows = desired.skuRows();
        if ("PERSONAL".equals(desired.accountType())) {
            skuRows = List.of();
        }
        return new ListingSnapshot(desired.title(), desired.description(), desired.price(), desired.originalPrice(),
                mediaIds, List.of(), desired.platformCategoryId(), desired.platformCategoryName(),
                desired.platformChannelCategoryId(), desired.platformChannelCategoryName(),
                desired.platformLeafId(), desired.platformTbCategoryId(), desired.platformAttributes(),
                "PERSONAL".equals(desired.accountType()) ? 1 : desired.quantity(),
                "PERSONAL".equals(desired.accountType()) ? List.of() : desired.specifications(), skuRows,
                desired.address(), desired.addressExpectedText(), desired.deliveryMethod(), desired.shippingMethod(),
                desired.supportPickup(), desired.postage());
    }

    private void executeReconciliation(SalesChannelOperation reconciliation, SalesChannelListing listing,
                                      SalesChannelAccount account) {
        Map<String, Object> payload = reconciliation.getPayload();
        UUID targetId = UUID.fromString(string(payload.get("target_operation_id")));
        SalesChannelOperation target = operationRepository.findById(targetId)
                .orElseThrow(() -> new IllegalStateException("Reconciliation target is missing"));
        IntegrationEnvelope<ReconcileData> envelope = providerClient.reconcile(new ReconcileCommand(
                reconciliation.getIdempotencyKey(), account.getProviderAccountId(), target.getIdempotencyKey(),
                listing.getExternalItemId(), reconciliation.getDesiredRevision()));

        if (envelope.outcome() != Outcome.SUCCEEDED || envelope.data() == null
                || envelope.data().targetOutcome() == null) {
            retryReadOnly(reconciliation, envelope.error());
            return;
        }
        ReconcileData data = envelope.data();
        if (!target.getIdempotencyKey().equals(data.targetRequestId())) {
            retryReadOnly(reconciliation, new IntegrationError("RECONCILE_TARGET_MISMATCH",
                    "Agent reconciliation response did not match the requested target", true));
            return;
        }
        if (target.getStatus() == SalesChannelOperationStatus.SUCCEEDED
                && "EXTERNAL_ITEM_ID_CONFLICT".equals(listing.getLastErrorCode())) {
            Object expectedItemId = target.getResult() == null ? null : target.getResult().get("item_id");
            if (data.targetOutcome() != Outcome.SUCCEEDED
                    || expectedItemId == null || !expectedItemId.toString().equals(data.itemId())) {
                retryReadOnly(reconciliation, new IntegrationError("IDENTITY_CONFLICT_UNCONFIRMED",
                        "Agent could not confirm the conflicting item identity", true));
                return;
            }
        }
        Map<String, Object> reconciliationResult = reconcileResult(envelope, data);
        switch (data.targetOutcome()) {
            case UNKNOWN -> retryReadOnly(reconciliation, envelope.error());
            case SUCCEEDED -> {
                Map<String, Object> targetResult = targetResult(data);
                String fingerprint = string(target.getPayload().get("desired_fingerprint"));
                operationService.resolveUnknownAsSucceeded(reconciliation.getId(), target.getId(), targetResult,
                        reconciliationResult, fingerprint);
                refreshAfterOutcome(listing.getId());
            }
            case FAILED -> {
                if (data.targetRetryable() == null) {
                    retryReadOnly(reconciliation, null);
                    return;
                }
                if (Boolean.TRUE.equals(data.targetRetryable())) {
                    LocalDateTime nextAttempt = LocalDateTime.now().plus(backoff(target.getAttemptCount()));
                    operationService.resolveUnknownAsRetryable(reconciliation.getId(), target.getId(),
                            reconciliationResult, "RECONCILED_NO_SIDE_EFFECT", "Agent proved the operation did not execute",
                            nextAttempt);
                } else {
                    Map<String, Object> targetResult = targetResult(data);
                    operationService.resolveUnknownAsFailed(reconciliation.getId(), target.getId(), targetResult,
                            reconciliationResult, "RECONCILED_PERMANENT_FAILURE",
                            "Agent proved the operation did not execute and retry is not allowed");
                }
                refreshAfterOutcome(listing.getId());
            }
        }
    }

    private Map<String, Object> reconcileResult(IntegrationEnvelope<ReconcileData> envelope, ReconcileData data) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("request_id", envelope.requestId());
        result.put("outcome", "SUCCEEDED");
        result.put("target_request_id", data.targetRequestId());
        result.put("target_outcome", data.targetOutcome().name());
        result.put("operation_type", data.operationType());
        result.put("item_id", data.itemId());
        result.put("desired_revision", data.desiredRevision());
        result.put("result", data.result());
        result.put("target_retryable", data.targetRetryable());
        return result;
    }

    private Map<String, Object> targetResult(ReconcileData data) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (data.result() != null) result.putAll(data.result());
        if (data.itemId() != null) result.put("item_id", data.itemId());
        if (data.desiredRevision() != null) result.put("desired_revision", data.desiredRevision());
        return result;
    }

    private void retryReadOnly(SalesChannelOperation operation, IntegrationError error) {
        if (operation.getAttemptCount() >= maxAttempts) {
            operationService.markFailed(operation.getId(),
                    error == null || blank(error.code()) ? "RECONCILE_MAX_ATTEMPTS" : error.code(),
                    "Reconciliation could not determine the target result within the retry limit");
            return;
        }
        operationService.reschedule(operation.getId(),
                error == null || blank(error.code()) ? "RECONCILE_STILL_UNKNOWN" : error.code(),
                "Read-only reconciliation did not produce a final target outcome",
                LocalDateTime.now().plus(backoff(operation.getAttemptCount())));
    }

    private void handleListingEnvelope(SalesChannelOperation operation, SalesChannelListing listing,
                                       IntegrationEnvelope<ListingOperationData> envelope, String fingerprint) {
        if (envelope.outcome() == Outcome.UNKNOWN) {
            String code = envelope.error() == null ? "AGENT_OUTCOME_UNKNOWN" : envelope.error().code();
            String message = envelope.error() == null ? "Agent outcome is unknown" : envelope.error().message();
            if (operationService.markUnknown(operation.getId(), code, message)) ensureReconciliation(operation);
            return;
        }
        if (envelope.outcome() == Outcome.FAILED) {
            IntegrationError error = envelope.error();
            boolean retryable = error != null && Boolean.TRUE.equals(error.retryable());
            handleKnownFailure(operation, error == null ? "AGENT_OPERATION_FAILED" : error.code(),
                    error == null ? "Xianyu Agent operation failed" : error.message(), retryable);
            return;
        }
        ListingOperationData data = envelope.data();
        if (data == null || operation.getOperationType() == SalesChannelOperationType.PUBLISH
                && blank(data.itemId())) {
            if (operationService.markUnknown(operation.getId(), "AGENT_RESULT_INCOMPLETE",
                    "Agent reported success without the external item identity")) ensureReconciliation(operation);
            return;
        }
        operationService.markSucceeded(operation.getId(), listingResult(data), fingerprint);
        SalesChannelListing updatedListing = listingRepository.findById(listing.getId()).orElse(null);
        if (updatedListing != null && "EXTERNAL_ITEM_ID_CONFLICT".equals(updatedListing.getLastErrorCode())) {
            ensureReconciliation(operation);
            return;
        }
        refreshAfterOutcome(listing.getId());
    }

    private Map<String, Object> listingResult(ListingOperationData data) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (data.itemId() != null) result.put("item_id", data.itemId());
        if (data.itemUrl() != null) result.put("item_url", data.itemUrl());
        if (data.desiredRevision() != null) result.put("desired_revision", data.desiredRevision());
        if (data.idempotentReused() != null) result.put("idempotent_reused", data.idempotentReused());
        return result;
    }

    private void handleClientFailure(SalesChannelOperation operation, SalesChannelProviderClientException exception) {
        switch (exception.getDisposition()) {
            case SAFE_RETRYABLE -> {
                if (operation.getAttemptCount() >= maxAttempts) {
                    operationService.markFailed(operation.getId(), "MAX_ATTEMPTS_EXCEEDED", exception.getMessage());
                } else {
                    operationService.reschedule(operation.getId(), exception.getErrorCode(), exception.getMessage(),
                            LocalDateTime.now().plus(backoff(operation.getAttemptCount())));
                }
            }
            case SAFE_PERMANENT -> operationService.markFailed(operation.getId(), exception.getErrorCode(), exception.getMessage());
            case UNKNOWN -> {
                if (operationService.markUnknown(operation.getId(), exception.getErrorCode(), exception.getMessage())) {
                    ensureReconciliation(operation);
                }
            }
        }
    }

    private void handleKnownFailure(SalesChannelOperation operation, String code, String message,
                                    boolean retryable) {
        if (retryable && operation.getAttemptCount() < maxAttempts) {
            operationService.reschedule(operation.getId(), code, message,
                    LocalDateTime.now().plus(backoff(operation.getAttemptCount())));
        } else {
            operationService.markFailed(operation.getId(), retryable ? "MAX_ATTEMPTS_EXCEEDED" : code, message);
        }
    }

    private void ensureReconciliation(SalesChannelOperation target) {
        operationService.requestReconciliation(target.getId());
    }

    private void refreshAfterOutcome(UUID listingId) {
        try {
            planner.refresh(listingId);
        } catch (RuntimeException exception) {
            log.warn("Sales channel listing {} needs a later planner refresh after its operation succeeded: {}",
                    listingId, exception.getClass().getSimpleName());
        }
    }

    private DesiredListingSnapshot desiredSnapshot(SalesChannelOperation operation) {
        Object raw = operation.getPayload().get("desired_snapshot");
        if (raw == null) throw new IllegalArgumentException("Operation payload does not contain its desired snapshot");
        return objectMapper.convertValue(raw, DesiredListingSnapshot.class);
    }

    private java.time.Duration backoff(int attempt) {
        long[] seconds = {5, 15, 30, 60, 120, 300, 600, 900};
        long base = seconds[Math.min(Math.max(attempt, 1) - 1, seconds.length - 1)];
        double jitter = ThreadLocalRandom.current().nextDouble(0.8d, 1.2d);
        return java.time.Duration.ofMillis((long) (base * 1000 * jitter));
    }

    private String string(Object value) { return value == null ? null : value.toString(); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
}
