package com.myshop.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.constant.SalesChannelAccountStatus;
import com.myshop.constant.SalesChannelGenerationState;
import com.myshop.constant.SalesChannelListingDesiredState;
import com.myshop.constant.SalesChannelListingManagementMode;
import com.myshop.constant.SalesChannelListingStatus;
import com.myshop.constant.SalesChannelOperationStatus;
import com.myshop.constant.SalesChannelOperationType;
import com.myshop.constant.SalesChannelProvider;
import com.myshop.entity.SalesChannelAccount;
import com.myshop.entity.SalesChannelListing;
import com.myshop.entity.SalesChannelListingGeneration;
import com.myshop.entity.SalesChannelOperation;
import com.myshop.entity.SalesChannelRecoveryPermit;
import com.myshop.repository.SalesChannelAccountRepository;
import com.myshop.repository.SalesChannelListingGenerationRepository;
import com.myshop.repository.SalesChannelListingRepository;
import com.myshop.repository.SalesChannelOperationRepository;
import com.myshop.repository.SalesChannelRecoveryPermitRepository;
import com.myshop.saleschannel.convergence.XianyuConvergenceClient;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.RecoveryArmRequest;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.CutoverReadiness;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.RecoveryPermitObservation;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.RecoveryPrepareRequest;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.RecoveryScope;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.RecoveryValidationRequest;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.RecoveryValidationResponse;
import com.myshop.saleschannel.model.SalesChannelRecoveryDtos.RecoveryPermitSummary;
import com.myshop.saleschannel.model.SalesChannelRecoveryDtos.RecoveryPlan;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** One-time, exact-command recovery protocol. The sole execution grant is the local GO transaction. */
@Service
@RequiredArgsConstructor
public class SalesChannelFailedEnsureRecoveryService {

    public static final UUID B_LISTING_ID = UUID.fromString("c827bfa4-abfe-47f9-8d60-f2d1c6972fcd");
    public static final UUID B_BOOTSTRAP_OPERATION_ID = UUID.fromString("7369460a-45a7-49d0-9cf5-85d98eff4199");
    public static final long B_AGENT_OPERATION_ID = 15L;
    public static final String B_ACCOUNT_ID = "2215747465381";
    public static final String B_REQUEST_ID = "0153b2a7-fbd7-48b8-b52c-0ea2379ddf26";
    public static final long B_REVISION = 10L;
    public static final String B_FINGERPRINT = "def5d3cbd69714b2cd8b3e87e5fc472631e46150b7bcd2c778a23f59b7989446";
    public static final String B_OLD_ITEM_ID = "1084013851108";

    private final SalesChannelListingRepository listingRepository;
    private final SalesChannelAccountRepository accountRepository;
    private final SalesChannelListingGenerationRepository generationRepository;
    private final SalesChannelOperationRepository operationRepository;
    private final SalesChannelRecoveryPermitRepository permitRepository;
    private final SalesChannelDesiredStatePlanner planner;
    private final XianyuConvergenceClient convergenceClient;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

    @Value("${sales-channel.worker.enabled:false}")
    private boolean workerEnabled;

    /** Read-only local and Agent validation. It does not create or mutate a permit. */
    public RecoveryPlan plan(UUID listingId, UUID operationId) {
        LocalSnapshot local = validateLocal(listingId, operationId, false);
        RecoveryValidationResponse agent = validateAgent(local.validationRequest());
        if (agent.agentOperationId() != B_AGENT_OPERATION_ID
                || !B_REQUEST_ID.equals(agent.requestId())) {
            throw rejected("AGENT_RECOVERY_COMMAND_IDENTITY_MISMATCH");
        }
        return recoveryPlan(local, agent);
    }

    /** Persist PREPARED on MyShop, then idempotently prepare Agent; no state here can claim the FAILED row. */
    public RecoveryPermitSummary prepare(UUID listingId, UUID operationId, String operator, String reason) {
        if (operator == null || operator.isBlank() || reason == null || reason.isBlank()) {
            throw rejected("RECOVERY_OPERATOR_AND_REASON_REQUIRED");
        }
        RecoveryPlan validated = plan(listingId, operationId);
        SalesChannelRecoveryPermit existing = permitRepository.findByMyshopOperationId(operationId).orElse(null);
        SalesChannelRecoveryPermit permit = existing;
        if (permit == null) {
            Map<String, Object> preconditions = new LinkedHashMap<>(validated.myshopPreconditions());
            preconditions.put("agent", validated.agentPreconditions());
            try {
                permit = new TransactionTemplate(transactionManager).execute(status -> {
                    LocalSnapshot current = validateLocal(listingId, operationId, true);
                    if (permitRepository.findByOperationIdForUpdate(operationId).isPresent()) {
                        return permitRepository.findByOperationIdForUpdate(operationId).orElseThrow();
                    }
                    SalesChannelRecoveryPermit created = new SalesChannelRecoveryPermit(
                            1L, listingId, operationId, validated.agentOperationId(),
                            current.account().getProviderAccountId(), B_REQUEST_ID, B_REVISION,
                            B_FINGERPRINT, B_OLD_ITEM_ID, operator.strip(), reason.strip(), preconditions,
                            LocalDateTime.now());
                    return permitRepository.saveAndFlush(created);
                });
            } catch (DataIntegrityViolationException concurrentPrepare) {
                permit = permitRepository.findByMyshopOperationId(operationId).orElseThrow(
                        () -> rejected("RECOVERY_PREPARE_CONFLICT"));
            }
        } else if (!matchesPermit(existing, listingId, operationId)) {
            throw rejected("RECOVERY_PERMIT_IDENTITY_CONFLICT");
        }

        RecoveryPermitObservation remote = convergenceClient.prepareRecovery(prepareRequest(permit));
        verifyRemoteIdentity(permit, remote);
        if (!("PREPARED".equals(remote.state()) || "ARMED".equals(remote.state()))) {
            throw rejected("AGENT_RECOVERY_PERMIT_NOT_PREPARED");
        }
        return summary(permitRepository.findById(permit.getId()).orElse(permit));
    }

    /** Agent ARMED is durable first; MyShop ARMED is committed only after its exact preconditions still hold. */
    public RecoveryPermitSummary arm(UUID permitId) {
        SalesChannelRecoveryPermit permit = requirePermit(permitId);
        LocalSnapshot local = validateLocal(permit.getSalesChannelListingId(), permit.getMyshopOperationId(), false);
        verifyScopeAgainstPermit(permit, local);
        RecoveryPermitObservation remote = convergenceClient.armRecovery(armRequest(permit));
        verifyRemoteIdentity(permit, remote);
        if (!("ARMED".equals(remote.state()) || "EXECUTING".equals(remote.state())
                || "RECOVERY_REQUIRED".equals(remote.state()))) {
            throw rejected("AGENT_RECOVERY_PERMIT_NOT_ARMED");
        }
        var transaction = new TransactionTemplate(transactionManager);
        SalesChannelRecoveryPermit armed = transaction.execute(status -> {
            LocalSnapshot current = validateLocal(permit.getSalesChannelListingId(), permit.getMyshopOperationId(), true);
            verifyScopeAgainstPermit(permit, current);
            SalesChannelRecoveryPermit locked = permitRepository.findByIdForUpdate(permitId)
                    .orElseThrow(() -> rejected("RECOVERY_PERMIT_NOT_FOUND"));
            if ("ARMED".equals(locked.getState())) return locked;
            if (!"PREPARED".equals(locked.getState())) throw rejected("RECOVERY_PERMIT_NOT_PREPARED");
            locked.arm(remote.preconditions(), LocalDateTime.now());
            return locked;
        });
        return summary(armed);
    }

    /** The only GO point: one MyShop transaction revalidates and reopens the same failed command identity. */
    public RecoveryPermitSummary go(UUID permitId, String operator) {
        SalesChannelRecoveryPermit permit = requirePermit(permitId);
        if ("ACTIVE".equals(permit.getState()) || "EXECUTING".equals(permit.getState())
                || "RECOVERY_REQUIRED".equals(permit.getState()) || "TERMINAL".equals(permit.getState())) {
            return summary(permit);
        }
        if ("ARMED".equals(permit.getState())) {
            final SalesChannelRecoveryPermit armedPermit = permit;
            RecoveryPermitObservation remote = convergenceClient.observeRecovery(permitId.toString().toLowerCase());
            verifyRemoteIdentity(permit, remote);
            if (!"ARMED".equals(remote.state())) throw rejected("AGENT_RECOVERY_PERMIT_NOT_ARMED");

            permit = new TransactionTemplate(transactionManager).execute(status -> {
                listingRepository.findByIdForUpdate(armedPermit.getSalesChannelListingId())
                        .orElseThrow(() -> rejected("LISTING_NOT_FOUND"));
                SalesChannelOperation observedOperation = operationRepository.findByIdForUpdate(
                        armedPermit.getMyshopOperationId()).orElseThrow(() -> rejected("MYSHOP_OPERATION_NOT_FOUND"));
                boolean alreadyReopened = observedOperation.getStatus() == SalesChannelOperationStatus.PENDING;
                LocalSnapshot current = validateLocal(armedPermit.getSalesChannelListingId(),
                        armedPermit.getMyshopOperationId(), true, alreadyReopened);
                verifyScopeAgainstPermit(armedPermit, current);
                SalesChannelRecoveryPermit lockedPermit = permitRepository.findByIdForUpdate(permitId)
                        .orElseThrow(() -> rejected("RECOVERY_PERMIT_NOT_FOUND"));
                if ("GO_PENDING".equals(lockedPermit.getState()) || "ACTIVE".equals(lockedPermit.getState())) {
                    return lockedPermit;
                }
                if (alreadyReopened) throw rejected("MYSHOP_GO_STATE_MISMATCH");
                if (!"ARMED".equals(lockedPermit.getState())) throw rejected("RECOVERY_PERMIT_NOT_ARMED");
                SalesChannelOperation operation = operationRepository.findByIdForUpdate(
                        lockedPermit.getMyshopOperationId()).orElseThrow(() -> rejected("MYSHOP_OPERATION_NOT_FOUND"));
                if (!operation.reopenFailedForRecovery(LocalDateTime.now())) {
                    throw rejected("MYSHOP_FAILED_OPERATION_NOT_REOPENABLE");
                }
                lockedPermit.activate(operator, LocalDateTime.now(), current.preconditions());
                return lockedPermit;
            });
        }

        if (!"GO_PENDING".equals(permit.getState())) throw rejected("RECOVERY_PERMIT_NOT_GO_PENDING");
        final SalesChannelRecoveryPermit goPendingPermit = permit;
        RecoveryPermitObservation remote = convergenceClient.observeRecovery(permitId.toString().toLowerCase());
        verifyRemoteIdentity(permit, remote);
        if ("ARMED".equals(remote.state())) {
            remote = convergenceClient.activateRecovery(armRequest(permit));
            verifyRemoteIdentity(permit, remote);
        }
        if (!"ACTIVE".equals(remote.state())) throw rejected("AGENT_RECOVERY_GO_NOT_ACTIVE");

        SalesChannelRecoveryPermit activated = new TransactionTemplate(transactionManager).execute(status -> {
            LocalSnapshot current = validateLocal(goPendingPermit.getSalesChannelListingId(), goPendingPermit.getMyshopOperationId(), true, true);
            verifyScopeAgainstPermit(goPendingPermit, current);
            SalesChannelRecoveryPermit lockedPermit = permitRepository.findByIdForUpdate(permitId)
                    .orElseThrow(() -> rejected("RECOVERY_PERMIT_NOT_FOUND"));
            if ("ACTIVE".equals(lockedPermit.getState()) || "EXECUTING".equals(lockedPermit.getState())
                    || "RECOVERY_REQUIRED".equals(lockedPermit.getState())
                    || "TERMINAL".equals(lockedPermit.getState())) return lockedPermit;
            if (!"GO_PENDING".equals(lockedPermit.getState())) throw rejected("RECOVERY_PERMIT_NOT_GO_PENDING");
            lockedPermit.completeGo(LocalDateTime.now());
            return lockedPermit;
        });
        return summary(activated);
    }

    public RecoveryPermitSummary observe(UUID permitId) {
        return summary(requirePermit(permitId));
    }

    /** Release the global scope only after both services report every write gate closed. */
    public RecoveryPermitSummary cancel(UUID permitId, String operator, String reason) {
        if (operator == null || operator.isBlank() || operator.length() > 128
                || reason == null || reason.isBlank() || reason.length() > 2000) {
            throw rejected("RECOVERY_OPERATOR_AND_REASON_REQUIRED");
        }
        if (workerEnabled) throw rejected("RECOVERY_CANCEL_REQUIRES_MYSHOP_WORKER_DISABLED");
        CutoverReadiness readiness = convergenceClient.agentReadiness();
        if (readiness == null || !Boolean.FALSE.equals(readiness.v2WritesEnabled())
                || !Boolean.FALSE.equals(readiness.v2AdoptionEnabled())) {
            throw rejected("RECOVERY_CANCEL_REQUIRES_AGENT_GATES_DISABLED");
        }

        SalesChannelRecoveryPermit staged = new TransactionTemplate(transactionManager).execute(status -> {
            SalesChannelRecoveryPermit locked = permitRepository.findByIdForUpdate(permitId)
                    .orElseThrow(() -> rejected("RECOVERY_PERMIT_NOT_FOUND"));
            if ("CANCELLED".equals(locked.getState())) return locked;
            if (!"CANCEL_PENDING".equals(locked.getState())
                    && !("PREPARED".equals(locked.getState()) || "ARMED".equals(locked.getState())
                    || "TERMINAL".equals(locked.getState()))) {
                throw rejected("RECOVERY_PERMIT_CANNOT_BE_CANCELLED");
            }
            if ("TERMINAL".equals(locked.getState())) {
                SalesChannelOperation operation = operationRepository.findByIdForUpdate(
                        locked.getMyshopOperationId()).orElseThrow(() -> rejected("MYSHOP_OPERATION_NOT_FOUND"));
                if (operation.getStatus() != SalesChannelOperationStatus.SUCCEEDED
                        && operation.getStatus() != SalesChannelOperationStatus.FAILED) {
                    throw rejected("MYSHOP_TERMINAL_OPERATION_REQUIRED");
                }
            }
            locked.beginCancel(operator.strip(), reason.strip(), LocalDateTime.now());
            return locked;
        });
        if ("CANCELLED".equals(staged.getState())) return summary(staged);

        RecoveryPermitObservation remote = convergenceClient.cancelRecovery(armRequest(staged));
        verifyRemoteIdentity(staged, remote);
        if (!"CANCELLED".equals(remote.state())) throw rejected("AGENT_RECOVERY_CANCEL_INCOMPLETE");

        SalesChannelRecoveryPermit cancelled = new TransactionTemplate(transactionManager).execute(status -> {
            SalesChannelRecoveryPermit locked = permitRepository.findByIdForUpdate(permitId)
                    .orElseThrow(() -> rejected("RECOVERY_PERMIT_NOT_FOUND"));
            if ("CANCELLED".equals(locked.getState())) return locked;
            if (!"CANCEL_PENDING".equals(locked.getState())) throw rejected("RECOVERY_CANCEL_STATE_MISMATCH");
            locked.finishCancel();
            return locked;
        });
        return summary(cancelled);
    }

    private RecoveryValidationResponse validateAgent(RecoveryValidationRequest request) {
        RecoveryValidationResponse response = convergenceClient.validateRecovery(request);
        if (response == null || !response.eligible() || response.agentOperationId() == null
                || !B_REQUEST_ID.equals(response.requestId()) || response.commandFingerprint() == null
                || !response.commandFingerprint().matches("^[a-f0-9]{64}$")) {
            throw rejected("AGENT_RECOVERY_VALIDATION_FAILED");
        }
        return response;
    }

    private RecoveryPlan recoveryPlan(LocalSnapshot local, RecoveryValidationResponse agent) {
        Map<String, Object> agentPreconditions = agent.preconditions() == null ? Map.of() : agent.preconditions();
        return new RecoveryPlan(true, B_LISTING_ID, B_BOOTSTRAP_OPERATION_ID, agent.agentOperationId(),
                B_ACCOUNT_ID, B_REQUEST_ID, B_REVISION, B_FINGERPRINT, B_OLD_ITEM_ID,
                local.preconditions(), agentPreconditions);
    }

    private LocalSnapshot validateLocal(UUID listingId, UUID operationId, boolean lock) {
        return validateLocal(listingId, operationId, lock, false);
    }

    private LocalSnapshot validateLocal(UUID listingId, UUID operationId, boolean lock, boolean goPending) {
        if (!B_LISTING_ID.equals(listingId) || !B_BOOTSTRAP_OPERATION_ID.equals(operationId)) {
            throw rejected("RECOVERY_SCOPE_NOT_ALLOWED");
        }
        SalesChannelListing listing = (lock ? listingRepository.findByIdForUpdate(listingId)
                : listingRepository.findById(listingId)).orElseThrow(() -> rejected("LISTING_NOT_FOUND"));
        SalesChannelOperation operation = (lock ? operationRepository.findByIdForUpdate(operationId)
                : operationRepository.findById(operationId)).orElseThrow(() -> rejected("MYSHOP_OPERATION_NOT_FOUND"));
        SalesChannelAccount account = (lock ? accountRepository.findByIdForUpdate(listing.getSalesChannelAccountId())
                : accountRepository.findById(listing.getSalesChannelAccountId()))
                .orElseThrow(() -> rejected("ACCOUNT_NOT_FOUND"));
        SalesChannelListingGeneration current = (lock
                ? generationRepository.findCurrentByListingIdForUpdate(listingId)
                : generationRepository.findCurrentByListingId(listingId))
                .orElseThrow(() -> rejected("CURRENT_GENERATION_NOT_FOUND"));

        if (listing.getConvergenceCutoverState() == null
                || !"PAUSED".equals(listing.getConvergenceCutoverState().name())
                || listing.getManagementMode() != SalesChannelListingManagementMode.MANAGED
                || listing.getDesiredRevision() != B_REVISION
                || !B_FINGERPRINT.equals(listing.getDesiredFingerprint())) {
            throw rejected("MYSHOP_LISTING_SNAPSHOT_MISMATCH");
        }
        if (account.getProvider() != SalesChannelProvider.XIANYU
                || !account.isEnabled() || account.getStatus() != SalesChannelAccountStatus.CONNECTED
                || !B_ACCOUNT_ID.equals(account.getProviderAccountId()) || account.getLastVerifiedAt() == null) {
            throw rejected("MYSHOP_ACCOUNT_SCOPE_MISMATCH");
        }
        if (current.getGenerationState() != SalesChannelGenerationState.CURRENT
                || !B_OLD_ITEM_ID.equals(current.getExternalItemId())
                || generationRepository.findCandidateByListingId(listingId).isPresent()
                || generationRepository.existsBySalesChannelListingIdAndGenerationState(
                        listingId, SalesChannelGenerationState.CANDIDATE)
                || listing.getStatus() == SalesChannelListingStatus.REPLACEMENT_PENDING) {
            throw rejected("MYSHOP_CURRENT_IDENTITY_MISMATCH");
        }
        boolean exactFailure = operation.getStatus() == SalesChannelOperationStatus.FAILED;
        boolean reopenedPending = goPending && operation.getStatus() == SalesChannelOperationStatus.PENDING;
        if (operation.getOperationType() != SalesChannelOperationType.ENSURE_LISTING
                || !exactFailure && !reopenedPending
                || operation.getAttemptCount() != 1
                || operation.getDesiredRevision() != B_REVISION
                || !B_REQUEST_ID.equals(operation.getIdempotencyKey())
                || operation.getTargetGenerationId() != null || operation.getResultGenerationId() != null) {
            throw rejected("MYSHOP_BOOTSTRAP_OPERATION_MISMATCH");
        }
        Map<String, Object> payload = operation.getPayload();
        if (!Objects.equals("ONLINE", payload.get("desired_state"))
                || !B_ACCOUNT_ID.equals(Objects.toString(payload.get("account_id"), ""))
                || !B_LISTING_ID.toString().equalsIgnoreCase(Objects.toString(payload.get("listing_key"), ""))
                || asLong(payload.get("revision")) != B_REVISION
                || !B_FINGERPRINT.equals(Objects.toString(payload.get("desired_fingerprint"), ""))
                || payload.get("desired_snapshot") == null) {
            throw rejected("MYSHOP_FROZEN_PAYLOAD_MISMATCH");
        }

        var intent = planner.computeCurrentIntent(listingId);
        if (intent == null || intent.desiredState() != SalesChannelListingDesiredState.ONLINE
                || !B_FINGERPRINT.equals(intent.fingerprint())
                || !jsonEquals(payload.get("desired_snapshot"), intent.snapshot())) {
            throw rejected("MYSHOP_CURRENT_INTENT_MISMATCH");
        }

        List<SalesChannelOperation> operations = operationRepository
                .findBySalesChannelListingIdOrderByCreatedAtAsc(listingId);
        List<String> otherLive = new ArrayList<>();
        List<String> otherRevisionSideEffects = new ArrayList<>();
        for (SalesChannelOperation candidate : operations) {
            if (candidate.getId().equals(operationId)) continue;
            if (candidate.getOperationType() == SalesChannelOperationType.ENSURE_LISTING
                    && (candidate.getStatus() == SalesChannelOperationStatus.PENDING
                    || candidate.getStatus() == SalesChannelOperationStatus.RUNNING
                    || candidate.getStatus() == SalesChannelOperationStatus.UNKNOWN)) {
                otherLive.add(candidate.getId().toString());
            }
            if (candidate.getDesiredRevision() >= B_REVISION) {
                otherRevisionSideEffects.add(candidate.getId().toString());
            }
        }
        if (!otherLive.isEmpty()) throw rejected("MYSHOP_OTHER_LIVE_ENSURE_EXISTS");
        if (!otherRevisionSideEffects.isEmpty()) throw rejected("MYSHOP_OTHER_REVISION_SIDE_EFFECT_EXISTS");

        Map<String, Object> preconditions = new LinkedHashMap<>();
        preconditions.put("listing_id", listing.getId().toString().toLowerCase());
        preconditions.put("cutover_state", listing.getConvergenceCutoverState().name());
        preconditions.put("desired_revision", listing.getDesiredRevision());
        preconditions.put("desired_fingerprint", listing.getDesiredFingerprint());
        preconditions.put("account_id", account.getProviderAccountId());
        preconditions.put("operation_id", operation.getId().toString().toLowerCase());
        preconditions.put("operation_status", operation.getStatus().name());
        preconditions.put("operation_attempt_count", operation.getAttemptCount());
        preconditions.put("request_id", operation.getIdempotencyKey());
        preconditions.put("operation_error_code", operation.getLastErrorCode());
        preconditions.put("operation_error_message", operation.getLastErrorMessage());
        preconditions.put("frozen_payload_match", true);
        preconditions.put("current_generation_id", current.getId().toString().toLowerCase());
        preconditions.put("current_item_id", current.getExternalItemId());
        preconditions.put("current_generation_state", current.getGenerationState().name());
        preconditions.put("candidate_generation_count", 0);
        preconditions.put("other_live_ensure_count", otherLive.size());
        preconditions.put("other_same_revision_side_effect_count", otherRevisionSideEffects.size());
        return new LocalSnapshot(listing, operation, account, current, intent, preconditions);
    }

    private RecoveryValidationRequest validationRequest(LocalSnapshot local) {
        return new RecoveryValidationRequest(B_ACCOUNT_ID, B_LISTING_ID.toString().toLowerCase(),
                B_BOOTSTRAP_OPERATION_ID.toString().toLowerCase(), B_REQUEST_ID, B_REVISION,
                B_FINGERPRINT, B_OLD_ITEM_ID);
    }

    private RecoveryScope scope(SalesChannelRecoveryPermit permit) {
        return new RecoveryScope(permit.getAccountId(), permit.getSalesChannelListingId().toString().toLowerCase(),
                permit.getMyshopOperationId().toString().toLowerCase(), permit.getRequestId(),
                permit.getDesiredRevision(), permit.getDesiredFingerprint(), permit.getExpectedOldItemId(),
                permit.getAgentOperationId());
    }

    private RecoveryPrepareRequest prepareRequest(SalesChannelRecoveryPermit permit) {
        RecoveryScope scope = scope(permit);
        return new RecoveryPrepareRequest(permit.getId().toString().toLowerCase(), permit.getGeneration(),
                scope.accountId(), scope.listingKey(), scope.myshopOperationId(), scope.requestId(),
                scope.desiredRevision(), scope.desiredFingerprint(), scope.expectedOldItemId(),
                scope.agentOperationId(), permit.getOperator(), permit.getReason());
    }

    private RecoveryArmRequest armRequest(SalesChannelRecoveryPermit permit) {
        RecoveryScope scope = scope(permit);
        return new RecoveryArmRequest(permit.getId().toString().toLowerCase(), permit.getGeneration(),
                scope.accountId(), scope.listingKey(), scope.myshopOperationId(), scope.requestId(),
                scope.desiredRevision(), scope.desiredFingerprint(), scope.expectedOldItemId(),
                scope.agentOperationId());
    }

    private void verifyRemoteIdentity(SalesChannelRecoveryPermit permit, RecoveryPermitObservation remote) {
        if (remote == null || !permit.getId().toString().equalsIgnoreCase(remote.permitId())
                || remote.generation() == null || remote.generation() != permit.getGeneration()
                || !permit.getAccountId().equals(remote.accountId())
                || !permit.getSalesChannelListingId().toString().equalsIgnoreCase(remote.listingKey())
                || !permit.getMyshopOperationId().toString().equalsIgnoreCase(remote.myshopOperationId())
                || remote.agentOperationId() == null || remote.agentOperationId() != permit.getAgentOperationId()
                || !permit.getRequestId().equals(remote.requestId())
                || remote.desiredRevision() == null || remote.desiredRevision() != permit.getDesiredRevision()
                || !permit.getDesiredFingerprint().equals(remote.desiredFingerprint())
                || !permit.getExpectedOldItemId().equals(remote.expectedOldItemId())) {
            throw rejected("AGENT_RECOVERY_PERMIT_IDENTITY_MISMATCH");
        }
    }

    private void verifyScopeAgainstPermit(SalesChannelRecoveryPermit permit, LocalSnapshot local) {
        if (!permit.getSalesChannelListingId().equals(local.listing().getId())
                || !permit.getMyshopOperationId().equals(local.operation().getId())
                || !permit.getAccountId().equals(local.account().getProviderAccountId())
                || !permit.getRequestId().equals(local.operation().getIdempotencyKey())
                || permit.getDesiredRevision() != local.operation().getDesiredRevision()
                || !permit.getDesiredFingerprint().equals(local.listing().getDesiredFingerprint())
                || !permit.getExpectedOldItemId().equals(local.current().getExternalItemId())
                || permit.getAgentOperationId() != B_AGENT_OPERATION_ID) {
            throw rejected("RECOVERY_PERMIT_IDENTITY_CONFLICT");
        }
    }

    private SalesChannelRecoveryPermit requirePermit(UUID permitId) {
        return permitRepository.findById(permitId).orElseThrow(() -> rejected("RECOVERY_PERMIT_NOT_FOUND"));
    }

    private boolean matchesPermit(SalesChannelRecoveryPermit permit, UUID listingId, UUID operationId) {
        return permit.getSalesChannelListingId().equals(listingId)
                && permit.getMyshopOperationId().equals(operationId)
                && permit.getAgentOperationId() == B_AGENT_OPERATION_ID
                && B_ACCOUNT_ID.equals(permit.getAccountId()) && B_REQUEST_ID.equals(permit.getRequestId())
                && permit.getDesiredRevision() == B_REVISION
                && B_FINGERPRINT.equals(permit.getDesiredFingerprint())
                && B_OLD_ITEM_ID.equals(permit.getExpectedOldItemId());
    }

    private RecoveryPermitSummary summary(SalesChannelRecoveryPermit permit) {
        return new RecoveryPermitSummary(permit.getId(), permit.getGeneration(), permit.getState(),
                permit.getSalesChannelListingId(), permit.getMyshopOperationId(), permit.getAgentOperationId(),
                permit.getAccountId(), permit.getRequestId(), permit.getDesiredRevision(),
                permit.getDesiredFingerprint(), permit.getExpectedOldItemId(), permit.getOperator(), permit.getReason(),
                permit.getGoOperator(), permit.getPreparedAt(), permit.getArmedAt(), permit.getGoAt(),
                permit.getExecutionStartedAt(), permit.getTerminalAt(), permit.getExecutionOutcome(),
                permit.getExecutionFence(), permit.getPreconditions(), permit.getAgentPreconditions());
    }

    private boolean jsonEquals(Object left, Object right) {
        JsonNode leftNode = objectMapper.valueToTree(left);
        JsonNode rightNode = objectMapper.valueToTree(right);
        return leftNode.equals(rightNode);
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String string) {
            try { return Long.parseLong(string); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private IllegalStateException rejected(String code) {
        return new IllegalStateException(code);
    }

    private record LocalSnapshot(
            SalesChannelListing listing,
            SalesChannelOperation operation,
            SalesChannelAccount account,
            SalesChannelListingGeneration current,
            SalesChannelDesiredStatePlanner.ComputedDesiredIntent intent,
            Map<String, Object> preconditions) {
        RecoveryValidationRequest validationRequest() {
            return new RecoveryValidationRequest(B_ACCOUNT_ID, B_LISTING_ID.toString().toLowerCase(),
                    B_BOOTSTRAP_OPERATION_ID.toString().toLowerCase(), B_REQUEST_ID, B_REVISION,
                    B_FINGERPRINT, B_OLD_ITEM_ID);
        }
    }
}
