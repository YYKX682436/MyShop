package com.myshop.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.constant.SalesChannelAccountStatus;
import com.myshop.constant.SalesChannelConvergenceCutoverState;
import com.myshop.constant.SalesChannelGenerationState;
import com.myshop.constant.SalesChannelListingDesiredState;
import com.myshop.constant.SalesChannelListingManagementMode;
import com.myshop.constant.SalesChannelListingStatus;
import com.myshop.constant.SalesChannelOperationStatus;
import com.myshop.constant.SalesChannelOperationType;
import com.myshop.constant.SalesChannelProvider;
import com.myshop.entity.Product;
import com.myshop.entity.SalesChannelAccount;
import com.myshop.entity.SalesChannelCutoverCertification;
import com.myshop.entity.SalesChannelListing;
import com.myshop.entity.SalesChannelListingGeneration;
import com.myshop.entity.SalesChannelOperation;
import com.myshop.repository.SalesChannelAccountRepository;
import com.myshop.repository.SalesChannelCutoverCertificationRepository;
import com.myshop.repository.SalesChannelListingGenerationRepository;
import com.myshop.repository.SalesChannelListingRepository;
import com.myshop.repository.SalesChannelOperationRepository;
import com.myshop.saleschannel.convergence.XianyuConvergenceClient;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.AgentBindingState;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.AgentProviderStatus;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.CutoverBinding;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.CutoverInspection;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.CutoverInspectionRequest;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.CutoverLifecycleHead;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.CutoverReadiness;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.DesiredListingState;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.ListingSnapshot;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.CutoverCertificationResult;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.CutoverIssue;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.CutoverPreflight;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.CutoverReadinessSummary;
import com.myshop.service.SalesChannelDesiredStatePlanner.ComputedDesiredIntent;
import com.myshop.service.impl.SalesChannelOperationServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Production authorization control plane. It has no provider write dependency. */
@Service
@Slf4j
public class SalesChannelCutoverService {

    private static final Set<SalesChannelOperationStatus> NONTERMINAL = Set.of(
            SalesChannelOperationStatus.PENDING, SalesChannelOperationStatus.RUNNING,
            SalesChannelOperationStatus.UNKNOWN);

    private final SalesChannelListingRepository listingRepository;
    private final SalesChannelAccountRepository accountRepository;
    private final SalesChannelListingGenerationRepository generationRepository;
    private final SalesChannelOperationRepository operationRepository;
    private final SalesChannelCutoverCertificationRepository certificationRepository;
    private final SalesChannelDesiredStatePlanner planner;
    private final SalesChannelOperationServiceImpl operationService;
    private final XianyuConvergenceClient convergenceClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final boolean workerEnabled;

    public SalesChannelCutoverService(
            SalesChannelListingRepository listingRepository,
            SalesChannelAccountRepository accountRepository,
            SalesChannelListingGenerationRepository generationRepository,
            SalesChannelOperationRepository operationRepository,
            SalesChannelCutoverCertificationRepository certificationRepository,
            SalesChannelDesiredStatePlanner planner,
            SalesChannelOperationServiceImpl operationService,
            XianyuConvergenceClient convergenceClient,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            @Value("${sales-channel.worker.enabled:false}") boolean workerEnabled) {
        this.listingRepository = listingRepository;
        this.accountRepository = accountRepository;
        this.generationRepository = generationRepository;
        this.operationRepository = operationRepository;
        this.certificationRepository = certificationRepository;
        this.planner = planner;
        this.operationService = operationService;
        this.convergenceClient = convergenceClient;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.workerEnabled = workerEnabled;
    }

    /** Pure evidence read: no planner refresh, command creation, adoption, or listing write. */
    public CutoverPreflight preflight(UUID listingId) {
        return evaluate(listingId).report();
    }

    /** Refresh local desired intent, inspect Agent/provider read-only, then persist immutable evidence. */
    public CutoverCertificationResult certify(UUID listingId) {
        SalesChannelListing before = listingRepository.findById(listingId).orElse(null);
        if (before == null) return new CutoverCertificationResult(emptyReport(listingId), null);
        if (before.getConvergenceCutoverState() == SalesChannelConvergenceCutoverState.ACTIVE
                || before.getConvergenceCutoverState() == SalesChannelConvergenceCutoverState.PAUSED) {
            Evaluation current = evaluate(listingId);
            return new CutoverCertificationResult(withIssue(current.report(), "CUTOVER_ALREADY_ACTIVE",
                    "An activated listing cannot be re-certified in place"), null);
        }

        // This is the only cutover operation that refreshes business intent.
        // The planner itself cannot enqueue an ENSURE for UNCERTIFIED/CERTIFIED.
        planner.refresh(listingId);
        Evaluation candidate = evaluate(listingId);
        if (!candidate.report().eligible()) return new CutoverCertificationResult(candidate.report(), null);

        Finalization result = transactionTemplate.execute(status -> finalizeCertification(candidate));
        if (result == null || result.errorCode() != null) {
            String code = result == null ? "CUTOVER_SNAPSHOT_CHANGED" : result.errorCode();
            return new CutoverCertificationResult(withIssue(candidate.report(), code,
                    "Local listing state changed while certification evidence was being captured"), null);
        }
        return new CutoverCertificationResult(withCutoverState(
                candidate.report(), SalesChannelConvergenceCutoverState.CERTIFIED),
                result.certificationId().toString());
    }

    public CutoverPreflight activate(UUID listingId) {
        SalesChannelListing listing = listingRepository.findById(listingId).orElse(null);
        if (listing == null) return emptyReport(listingId);
        if (listing.getConvergenceCutoverState() != SalesChannelConvergenceCutoverState.CERTIFIED
                || listing.getConvergenceCertificationId() == null) {
            return withIssue(evaluate(listingId).report(), "CUTOVER_NOT_CERTIFIED",
                    "Only a certified listing can be activated");
        }
        SalesChannelCutoverCertification certificate = certificationRepository
                .findById(listing.getConvergenceCertificationId()).orElse(null);
        if (certificate == null) return invalidateStaleAndReport(listingId, listing.getConvergenceCertificationId());

        Evaluation candidate = evaluate(listingId);
        if (!localCertificateMatches(candidate, certificate)) {
            return invalidateStaleAndReport(listingId, certificate.getId());
        }
        if (certificate.getBootstrapOperationId() != null
                && !bootstrapStillExact(candidate, certificate.getBootstrapOperationId(), false)) {
            return invalidateStaleAndReport(listingId, certificate.getId());
        }
        if (!candidate.report().eligible()) return candidate.report();
        if (candidate.inspection() == null
                || !certificate.getAgentStateDigest().equals(candidate.inspection().inspectionDigest())) {
            return withIssue(candidate.report(), "CUTOVER_CERTIFICATION_STALE",
                    "Agent identity or lifecycle evidence differs from the certificate");
        }

        CutoverReadiness readiness;
        try {
            readiness = convergenceClient.agentReadiness();
        } catch (RuntimeException unavailable) {
            return withIssue(candidate.report(), "AGENT_INSPECTION_UNAVAILABLE",
                    "Agent runtime readiness could not be read; retry activation");
        }
        CutoverPreflight gated = withRuntimeGates(candidate.report(), readiness);
        if (!readiness.v2WritesEnabled()) {
            gated = withIssue(gated, "AGENT_V2_WRITES_DISABLED", "Agent v2 writes must be enabled before activation");
        }
        if (certificate.isRequiresAdoption() && !readiness.v2AdoptionEnabled()) {
            gated = withIssue(gated, "AGENT_V2_ADOPTION_DISABLED",
                    "Agent v2 adoption must be enabled for this listing");
        }
        if (!gated.eligible()) return gated;

        Finalization result = transactionTemplate.execute(status -> finalizeActivation(
                candidate, certificate, readiness));
        if (result == null || result.errorCode() != null) {
            String code = result == null ? "CUTOVER_CERTIFICATION_STALE" : result.errorCode();
            if ("CUTOVER_CERTIFICATION_STALE".equals(code)) {
                invalidateCertificate(listingId, certificate.getId());
            }
            return withIssue(gated, code, "Certificate or bootstrap operation changed before activation");
        }
        return withCutoverState(gated, SalesChannelConvergenceCutoverState.ACTIVE);
    }

    public CutoverPreflight pause(UUID listingId) {
        String error = transactionTemplate.execute(status -> {
            SalesChannelListing listing = listingRepository.findByIdForUpdate(listingId).orElse(null);
            if (listing == null) return "LISTING_NOT_FOUND";
            if (listing.getConvergenceCutoverState() != SalesChannelConvergenceCutoverState.ACTIVE) {
                return "CUTOVER_NOT_ACTIVE";
            }
            listing.pauseConvergence(LocalDateTime.now());
            return null;
        });
        CutoverPreflight report = evaluate(listingId).report();
        return error == null ? report : withIssue(report, error, "Listing cutover state does not allow pause");
    }

    public CutoverPreflight resume(UUID listingId) {
        SalesChannelListing listing = listingRepository.findById(listingId).orElse(null);
        if (listing == null) return emptyReport(listingId);
        if (listing.getConvergenceCutoverState() != SalesChannelConvergenceCutoverState.PAUSED) {
            return withIssue(evaluate(listingId).report(), "CUTOVER_NOT_PAUSED",
                    "Only a paused listing can be resumed");
        }
        planner.refresh(listingId);
        Evaluation candidate = evaluate(listingId);
        if (!candidate.report().eligible()) return candidate.report();
        CutoverReadiness readiness;
        try {
            readiness = convergenceClient.agentReadiness();
        } catch (RuntimeException unavailable) {
            return withIssue(candidate.report(), "AGENT_INSPECTION_UNAVAILABLE",
                    "Agent runtime readiness could not be read; retry resume");
        }
        CutoverPreflight gated = withRuntimeGates(candidate.report(), readiness);
        if (!readiness.v2WritesEnabled()) {
            gated = withIssue(gated, "AGENT_V2_WRITES_DISABLED", "Agent v2 writes must be enabled before resume");
        }
        if (candidate.report().needsAdoption() && !readiness.v2AdoptionEnabled()) {
            gated = withIssue(gated, "AGENT_V2_ADOPTION_DISABLED",
                    "Agent v2 adoption must be enabled for this listing");
        }
        if (!gated.eligible()) return gated;
        String error = transactionTemplate.execute(status -> {
            SalesChannelListing locked = listingRepository.findByIdForUpdate(listingId).orElse(null);
            if (locked == null || locked.getConvergenceCutoverState() != SalesChannelConvergenceCutoverState.PAUSED
                    || !localSnapshotMatches(locked, candidate)
                    || !freshIntentMatches(locked, listingId)) {
                return "CUTOVER_SNAPSHOT_CHANGED";
            }
            SalesChannelListingGeneration current = generationRepository.findCurrentByListingId(locked.getId()).orElse(null);
            if (!Objects.equals(current == null ? null : current.getExternalItemId(), candidate.report().currentExternalItemId())) {
                return "CUTOVER_SNAPSHOT_CHANGED";
            }
            if (hasInFlightLocalOperation(listingId)) return "CONVERGENCE_OPERATION_IN_FLIGHT";
            locked.activateConvergence(LocalDateTime.now());
            return null;
        });
        return error == null
                ? withCutoverState(gated, SalesChannelConvergenceCutoverState.ACTIVE)
                : withIssue(gated, error, "Listing state changed before resume");
    }

    public CutoverReadinessSummary readinessSummary() {
        boolean writes = false;
        boolean adoption = false;
        try {
            CutoverReadiness readiness = convergenceClient.agentReadiness();
            writes = readiness.v2WritesEnabled();
            adoption = readiness.v2AdoptionEnabled();
        } catch (RuntimeException ignored) {
            // A readiness outage reports closed gates and never changes listing state.
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        for (SalesChannelConvergenceCutoverState state : SalesChannelConvergenceCutoverState.values()) {
            counts.put(state.name(), listingRepository.countByConvergenceCutoverState(state));
        }
        return new CutoverReadinessSummary(workerEnabled, writes, adoption, counts,
                operationRepository.countPendingNeverSentEnsure(),
                operationRepository.countAttemptedRecoveryEnsure(),
                operationRepository.countLegacyUnresolved());
    }

    private Evaluation evaluate(UUID listingId) {
        SalesChannelListing listing = listingRepository.findById(listingId).orElse(null);
        LinkedHashMap<String, CutoverIssue> blockers = new LinkedHashMap<>();
        LinkedHashMap<String, CutoverIssue> warnings = new LinkedHashMap<>();
        if (listing == null) {
            issue(blockers, "LISTING_NOT_FOUND", "The sales channel listing does not exist");
            return new Evaluation(null, null, null, null, null, null, null,
                    buildReport(listingId, null, null, null, null, blockers, warnings, false, true, true));
        }

        if (listing.getManagementMode() != SalesChannelListingManagementMode.MANAGED) {
            issue(blockers, "LISTING_NOT_MANAGED", "Listing management mode must be MANAGED");
        }
        if (listing.getStatus() == SalesChannelListingStatus.REPLACEMENT_PENDING
                || generationRepository.findCandidateByListingId(listingId).isPresent()) {
            issue(blockers, "LEGACY_CANDIDATE_UNRESOLVED", "A replacement candidate is unresolved");
        }

        SalesChannelAccount account = accountRepository.findById(listing.getSalesChannelAccountId()).orElse(null);
        if (account == null || account.getProvider() != SalesChannelProvider.XIANYU || !account.isEnabled()
                || account.getStatus() != SalesChannelAccountStatus.CONNECTED
                || !exactIdentity(account.getProviderAccountId(), 80)
                || account.getLastVerifiedAt() == null) {
            issue(blockers, "ACCOUNT_NOT_READY", "The exact Xianyu account must be enabled, connected, and verified");
        }

        Product product = null;
        ComputedDesiredIntent intent = null;
        try {
            intent = planner.computeCurrentIntent(listingId);
            product = intent == null ? null : intent.product();
        } catch (RuntimeException invalid) {
            issue(blockers, "LOCAL_DESIRED_INTENT_STALE", "Current product and listing data cannot produce valid intent");
        }
        if (product == null) issue(blockers, "PRODUCT_NOT_FOUND", "The listing product does not exist");
        if (intent != null && (listing.getDesiredState() != intent.desiredState()
                || listing.getDesiredFingerprint() == null
                || !listing.getDesiredFingerprint().equals(intent.fingerprint()))) {
            issue(blockers, "LOCAL_DESIRED_INTENT_STALE", "Stored desired intent differs from current business data");
        }

        SalesChannelListingGeneration current = generationRepository.findCurrentByListingId(listingId).orElse(null);
        String currentItemId = current == null ? null : current.getExternalItemId();
        if (currentItemId != null && (!exactIdentity(currentItemId, 64))) {
            issue(blockers, "PROVIDER_ITEM_IDENTITY_MISMATCH", "Current external item id is outside the exact Agent contract");
        }

        for (SalesChannelOperation operation : operationRepository.findBySalesChannelListingIdOrderByCreatedAtAsc(listingId)) {
            SalesChannelOperationStatus status = operation.getStatus();
            boolean live = NONTERMINAL.contains(status);
            if (operation.getOperationType() == SalesChannelOperationType.ENSURE_LISTING) {
                if (status == SalesChannelOperationStatus.RUNNING
                        || live && operation.getAttemptCount() > 0) {
                    issue(blockers, "CONVERGENCE_OPERATION_IN_FLIGHT", "A convergence ENSURE may already have crossed the Agent boundary");
                }
            } else if (operation.getOperationType() == SalesChannelOperationType.RECONCILE && live
                    || live && (status != SalesChannelOperationStatus.PENDING || operation.getAttemptCount() > 0)) {
                issue(blockers, "LEGACY_OPERATION_UNRESOLVED", "A legacy operation has unresolved execution history");
            }
        }

        CutoverInspection inspection = null;
        boolean readRetryable = false;
        if (account != null && exactIdentity(account.getProviderAccountId(), 80)) {
            try {
                inspection = convergenceClient.inspectListing(new CutoverInspectionRequest(
                        UUID.randomUUID().toString().toLowerCase(), account.getProviderAccountId(),
                        listingId.toString().toLowerCase(), currentItemId));
            } catch (RuntimeException unavailable) {
                readRetryable = true;
                issue(blockers, "AGENT_INSPECTION_UNAVAILABLE", "Read-only Agent inspection failed; retry without changing state");
            }
            if (inspection == null) {
                readRetryable = true;
                issue(blockers, "AGENT_INSPECTION_UNAVAILABLE", "Read-only Agent inspection returned no evidence; retry without changing state");
            }
        }

        boolean needsAdoption = false;
        boolean bootstrapEnsure = true;
        if (inspection != null) {
            if (!inspection.nonterminalOperations().isEmpty()) {
                issue(blockers, "AGENT_OPERATION_IN_FLIGHT", "Agent has a nonterminal v2 operation for this listing");
            }
            List<CutoverBinding> listingBindings = inspection.bindings().stream()
                    .filter(binding -> binding.listingKey().equals(listingId.toString().toLowerCase())).toList();
            List<CutoverBinding> itemBindings = currentItemId == null ? List.of() : inspection.bindings().stream()
                    .filter(binding -> binding.externalItemId().equals(currentItemId)).toList();
            if (inspection.bindings().stream().anyMatch(binding -> binding.state() == AgentBindingState.TRANSITION_PENDING)) {
                issue(blockers, "AGENT_TRANSITION_PENDING", "Agent has an unresolved binding transition");
            }

            if (currentItemId == null) {
                if (listingBindings.stream().anyMatch(binding -> binding.state() == AgentBindingState.CURRENT)) {
                    issue(blockers, "AGENT_UNEXPECTED_CURRENT_BINDING", "Agent has a current item for a listing with no local current generation");
                }
            } else {
                var managed = inspection.managedItem();
                if (managed == null || !currentItemId.equals(managed.externalItemId())) {
                    issue(blockers, "AGENT_OWNERSHIP_NOT_CLAIMED", "Agent has no managed-item marker for the exact current item");
                } else if (!"MYSHOP".equals(managed.managedBy())) {
                    issue(blockers, "AGENT_OWNERSHIP_CONFLICT", "The exact provider item is managed by another principal");
                }
                if (itemBindings.stream().anyMatch(binding -> binding.state() == AgentBindingState.RETIRED)) {
                    issue(blockers, "AGENT_RETIRED_IDENTITY_CONFLICT", "The current item is retired in Agent binding history");
                }
                if (listingBindings.stream().anyMatch(binding -> binding.state() == AgentBindingState.CURRENT
                        && !currentItemId.equals(binding.externalItemId()))) {
                    issue(blockers, "AGENT_LISTING_IDENTITY_CONFLICT", "Agent maps this listing to a different current item");
                }
                if (itemBindings.stream().anyMatch(binding -> binding.state() == AgentBindingState.CURRENT
                        && !binding.listingKey().equals(listingId.toString().toLowerCase()))) {
                    issue(blockers, "AGENT_ITEM_IDENTITY_CONFLICT", "Agent maps this item to another listing");
                }
                boolean exactCurrent = itemBindings.stream().anyMatch(binding ->
                        binding.listingKey().equals(listingId.toString().toLowerCase())
                                && binding.state() == AgentBindingState.CURRENT);
                needsAdoption = !exactCurrent;
                if (needsAdoption && managed != null && "MYSHOP".equals(managed.managedBy())
                        && itemBindings.isEmpty() && listingBindings.stream().noneMatch(binding ->
                        binding.state() == AgentBindingState.CURRENT)) {
                    issue(warnings, "ADOPTION_REQUIRED", "Exact legacy item ownership is present; Agent binding adoption is required");
                }
                String localMarker = listing.getConvergenceAdoptedExternalItemId();
                if (localMarker != null && (!localMarker.equals(currentItemId) || !exactCurrent)) {
                    issue(blockers, "LOCAL_ADOPTION_MARKER_CONFLICT", "Local adoption marker lacks matching Agent CURRENT binding evidence");
                }

                if (inspection.providerObservationErrorCode() != null
                        || inspection.providerObservation() == null
                        || !inspection.providerObservation().complete()) {
                    issue(blockers, "PROVIDER_OBSERVATION_UNAVAILABLE", "Exact live provider identity could not be read completely");
                } else if (!currentItemId.equals(inspection.providerObservation().externalItemId())) {
                    issue(blockers, "PROVIDER_ITEM_IDENTITY_MISMATCH", "Provider returned another external item id");
                } else if (inspection.providerObservation().status() == AgentProviderStatus.MISSING) {
                    issue(blockers, "PROVIDER_ITEM_MISSING", "The exact current provider item is missing");
                } else if (inspection.providerObservation().status() == AgentProviderStatus.UNAVAILABLE) {
                    issue(blockers, "PROVIDER_OBSERVATION_UNAVAILABLE", "Exact live provider identity is unavailable");
                }
            }

            CutoverLifecycleHead head = inspection.lifecycleHead();
            if (head == null) {
                bootstrapEnsure = true;
            } else if (intent != null && head.desiredRevision() > listing.getDesiredRevision()) {
                issue(blockers, "AGENT_LIFECYCLE_AHEAD", "Agent lifecycle revision is ahead of MyShop");
            } else if (intent != null && head.desiredRevision() == listing.getDesiredRevision()) {
                boolean active = intent.desiredState() == SalesChannelListingDesiredState.ONLINE;
                if (!Objects.equals(head.desiredFingerprint(), listing.getDesiredFingerprint())
                        || head.desiredActive() != active) {
                    issue(blockers, "AGENT_LIFECYCLE_CONFLICT", "Equal lifecycle revisions contain different desired intent");
                } else {
                    bootstrapEnsure = false;
                }
            } else if (head != null) {
                issue(warnings, "AGENT_LIFECYCLE_BEHIND", "Agent lifecycle revision is behind MyShop and needs a fenced ENSURE");
                bootstrapEnsure = true;
            }
        }

        CutoverPreflight report = buildReport(listingId, listing, intent, currentItemId, inspection,
                blockers, warnings, needsAdoption, readRetryable, bootstrapEnsure);
        Map<String, Object> payload = intent == null || account == null ? null : ensurePayload(listing, account, intent);
        return new Evaluation(listing, account, product, current, intent, inspection, payload, report);
    }

    private Finalization finalizeCertification(Evaluation candidate) {
        if (candidate.listing() == null || candidate.intent() == null || candidate.account() == null) {
            return new Finalization(null, "CUTOVER_SNAPSHOT_CHANGED");
        }
        SalesChannelListing locked = listingRepository.findByIdForUpdate(candidate.listing().getId()).orElse(null);
        if (locked == null || locked.getConvergenceCutoverState() == SalesChannelConvergenceCutoverState.ACTIVE
                || locked.getConvergenceCutoverState() == SalesChannelConvergenceCutoverState.PAUSED
                || !localSnapshotMatches(locked, candidate)
                || !freshIntentMatches(locked, locked.getId())
                || hasInFlightLocalOperation(locked.getId())) {
            return new Finalization(null, "CUTOVER_SNAPSHOT_CHANGED");
        }
        SalesChannelListingGeneration current = generationRepository.findCurrentByListingId(locked.getId()).orElse(null);
        String itemId = current == null ? null : current.getExternalItemId();
        if (!Objects.equals(itemId, candidate.report().currentExternalItemId())) {
            return new Finalization(null, "CUTOVER_SNAPSHOT_CHANGED");
        }

        SalesChannelOperation bootstrap = null;
        if (candidate.report().bootstrapEnsureRequired()) {
            bootstrap = operationService.prepareCertifiedEnsure(locked.getId(), candidate.ensurePayload());
        }
        UUID certificationId = UUID.randomUUID();
        Map<String, Object> evidence = safeEvidence(candidate);
        SalesChannelCutoverCertification certificate = new SalesChannelCutoverCertification(
                certificationId, locked.getId(), locked.getDesiredRevision(), locked.getDesiredState().name(),
                locked.getDesiredFingerprint(), itemId,
                candidate.inspection().inspectionDigest(), candidate.report().needsAdoption(),
                bootstrap == null ? null : bootstrap.getId(), evidence, LocalDateTime.now());
        certificationRepository.saveAndFlush(certificate);
        locked.certifyConvergence(certificationId);
        if (itemId != null && hasExactCurrentBinding(candidate.inspection(), locked.getId(), itemId)) {
            locked.setConvergenceAdoptedExternalItemId(itemId);
            locked.setConvergenceAdoptedAt(LocalDateTime.now());
        }
        return new Finalization(certificationId, null);
    }

    private Finalization finalizeActivation(Evaluation candidate, SalesChannelCutoverCertification certificate,
                                            CutoverReadiness readiness) {
        SalesChannelListing locked = listingRepository.findByIdForUpdate(candidate.listing().getId()).orElse(null);
        if (locked == null || locked.getConvergenceCutoverState() != SalesChannelConvergenceCutoverState.CERTIFIED
                || !certificate.getId().equals(locked.getConvergenceCertificationId())
                || !localSnapshotMatches(locked, candidate)
                || !freshIntentMatches(locked, locked.getId())
                || locked.getDesiredRevision() != certificate.getDesiredRevision()
                || locked.getDesiredState() == null
                || !locked.getDesiredState().name().equals(certificate.getDesiredState())
                || !Objects.equals(locked.getDesiredFingerprint(), certificate.getDesiredFingerprint())
                || !localCertificateMatches(candidate, certificate)) {
            return new Finalization(null, "CUTOVER_CERTIFICATION_STALE");
        }
        if (!readiness.v2WritesEnabled() || certificate.isRequiresAdoption() && !readiness.v2AdoptionEnabled()) {
            return new Finalization(null, "CUTOVER_GATES_CLOSED");
        }
        SalesChannelListingGeneration current = generationRepository.findCurrentByListingId(locked.getId()).orElse(null);
        if (!Objects.equals(current == null ? null : current.getExternalItemId(), certificate.getCurrentExternalItemId())) {
            return new Finalization(null, "CUTOVER_CERTIFICATION_STALE");
        }
        if (certificate.getBootstrapOperationId() != null
                && !bootstrapStillExact(candidate, certificate.getBootstrapOperationId(), true)) {
            return new Finalization(null, "CUTOVER_CERTIFICATION_STALE");
        }
        locked.activateConvergence(LocalDateTime.now());
        return new Finalization(certificate.getId(), null);
    }

    private boolean bootstrapStillExact(Evaluation candidate, UUID operationId, boolean lock) {
        SalesChannelOperation operation = (lock ? operationRepository.findByIdForUpdate(operationId)
                : operationRepository.findById(operationId)).orElse(null);
        return operation != null && operation.getOperationType() == SalesChannelOperationType.ENSURE_LISTING
                && operation.getSalesChannelListingId().equals(candidate.listing().getId())
                && operation.getDesiredRevision() == candidate.listing().getDesiredRevision()
                && operation.getStatus() == SalesChannelOperationStatus.PENDING
                && operation.getAttemptCount() == 0 && operation.getStartedAt() == null
                && operationService.isSameFrozenPayload(operation.getPayload(), candidate.ensurePayload());
    }

    private boolean localCertificateMatches(Evaluation candidate, SalesChannelCutoverCertification certificate) {
        Object certifiedAccountId = certificate.getEvidence().get("account_provider_id");
        return candidate.listing() != null && candidate.intent() != null
                && candidate.account() != null
                && Objects.equals(certifiedAccountId, candidate.account().getProviderAccountId())
                && candidate.listing().getDesiredRevision() == certificate.getDesiredRevision()
                && candidate.listing().getDesiredState().name().equals(certificate.getDesiredState())
                && Objects.equals(candidate.listing().getDesiredFingerprint(), certificate.getDesiredFingerprint())
                && Objects.equals(candidate.report().currentExternalItemId(), certificate.getCurrentExternalItemId())
                && candidate.intent().desiredState().name().equals(certificate.getDesiredState())
                && candidate.intent().fingerprint().equals(certificate.getDesiredFingerprint());
    }

    private boolean localSnapshotMatches(SalesChannelListing locked, Evaluation candidate) {
        return candidate.listing() != null && candidate.intent() != null
                && locked.getDesiredRevision() == candidate.listing().getDesiredRevision()
                && locked.getDesiredState() == candidate.listing().getDesiredState()
                && Objects.equals(locked.getDesiredFingerprint(), candidate.listing().getDesiredFingerprint())
                && locked.getConvergenceCutoverState() == candidate.listing().getConvergenceCutoverState();
    }

    private boolean freshIntentMatches(SalesChannelListing listing, ComputedDesiredIntent intent) {
        return intent != null && listing.getDesiredState() == intent.desiredState()
                && Objects.equals(listing.getDesiredFingerprint(), intent.fingerprint());
    }

    private boolean freshIntentMatches(SalesChannelListing listing, UUID listingId) {
        try {
            return freshIntentMatches(listing, planner.computeCurrentIntent(listingId));
        } catch (RuntimeException invalidIntent) {
            return false;
        }
    }

    private boolean hasInFlightLocalOperation(UUID listingId) {
        return operationRepository.findBySalesChannelListingIdOrderByCreatedAtAsc(listingId).stream()
                .anyMatch(operation -> operation.getOperationType() == SalesChannelOperationType.ENSURE_LISTING
                        && NONTERMINAL.contains(operation.getStatus()) && operation.getAttemptCount() > 0
                        || operation.getOperationType() != SalesChannelOperationType.ENSURE_LISTING
                        && (operation.getStatus() == SalesChannelOperationStatus.RUNNING
                        || operation.getStatus() == SalesChannelOperationStatus.UNKNOWN
                        || operation.getStatus() == SalesChannelOperationStatus.PENDING
                        && operation.getAttemptCount() > 0
                        || operation.getOperationType() == SalesChannelOperationType.RECONCILE
                        && operation.getStatus() == SalesChannelOperationStatus.PENDING));
    }

    private Map<String, Object> ensurePayload(SalesChannelListing listing, SalesChannelAccount account,
                                              ComputedDesiredIntent intent) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("account_id", account.getProviderAccountId());
        payload.put("listing_key", listing.getId().toString().toLowerCase());
        payload.put("revision", listing.getDesiredRevision());
        payload.put("desired_state", intent.desiredState().name());
        payload.put("desired_snapshot", intent.snapshot() == null ? null
                : objectMapper.convertValue(intent.snapshot(), Map.class));
        payload.put("desired_fingerprint", intent.fingerprint());
        return payload;
    }

    private Map<String, Object> safeEvidence(Evaluation candidate) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("account_provider_id", candidate.account().getProviderAccountId());
        evidence.put("management_mode", candidate.listing().getManagementMode().name());
        evidence.put("managed_item", candidate.inspection().managedItem() == null ? null : Map.of(
                "external_item_id", candidate.inspection().managedItem().externalItemId(),
                "managed_by", candidate.inspection().managedItem().managedBy()));
        evidence.put("bindings", candidate.inspection().bindings().stream().map(binding -> Map.of(
                "listing_key", binding.listingKey(), "external_item_id", binding.externalItemId(),
                "state", binding.state().name(), "desired_revision", binding.desiredRevision(),
                "lifecycle_revision", binding.lifecycleRevision())).toList());
        CutoverLifecycleHead head = candidate.inspection().lifecycleHead();
        evidence.put("lifecycle_head", head == null ? null : Map.of(
                "desired_revision", head.desiredRevision(), "desired_fingerprint", head.desiredFingerprint(),
                "desired_active", head.desiredActive()));
        evidence.put("provider_status", candidate.report().providerStatus());
        evidence.put("warnings", candidate.report().warnings().stream().map(CutoverIssue::code).toList());
        return evidence;
    }

    private boolean hasExactCurrentBinding(CutoverInspection inspection, UUID listingId, String itemId) {
        return inspection.bindings().stream().anyMatch(binding -> binding.state() == AgentBindingState.CURRENT
                && binding.listingKey().equals(listingId.toString().toLowerCase())
                && binding.externalItemId().equals(itemId));
    }

    private CutoverPreflight invalidateStaleAndReport(UUID listingId, UUID certificationId) {
        invalidateCertificate(listingId, certificationId);
        return withIssue(evaluate(listingId).report(), "CUTOVER_CERTIFICATION_STALE",
                "Local desired intent, identity, or bootstrap command changed after certification");
    }

    private void invalidateCertificate(UUID listingId, UUID certificationId) {
        transactionTemplate.executeWithoutResult(status -> {
            SalesChannelListing locked = listingRepository.findByIdForUpdate(listingId).orElse(null);
            if (locked != null && locked.getConvergenceCutoverState() == SalesChannelConvergenceCutoverState.CERTIFIED
                    && Objects.equals(locked.getConvergenceCertificationId(), certificationId)) {
                locked.invalidateConvergenceCertification();
            }
        });
    }

    private CutoverPreflight withRuntimeGates(CutoverPreflight report, CutoverReadiness readiness) {
        return new CutoverPreflight(report.listingId(), report.currentCutoverState(), report.eligible(),
                report.retryable(), report.blockers(), report.warnings(), report.desiredState(),
                report.desiredRevision(), report.desiredFingerprint(), report.currentExternalItemId(),
                report.needsAdoption(), report.bootstrapEnsureRequired(), report.agentInspectionDigest(),
                report.providerStatus(), readiness.v2WritesEnabled(), readiness.v2AdoptionEnabled());
    }

    private CutoverPreflight withCutoverState(CutoverPreflight report,
                                              SalesChannelConvergenceCutoverState state) {
        return new CutoverPreflight(report.listingId(), state, report.eligible(), report.retryable(),
                report.blockers(), report.warnings(), report.desiredState(), report.desiredRevision(),
                report.desiredFingerprint(), report.currentExternalItemId(), report.needsAdoption(),
                report.bootstrapEnsureRequired(), report.agentInspectionDigest(), report.providerStatus(),
                report.agentWritesEnabled(), report.agentAdoptionEnabled());
    }

    private CutoverPreflight withIssue(CutoverPreflight report, String code, String message) {
        LinkedHashMap<String, CutoverIssue> blockers = new LinkedHashMap<>();
        report.blockers().forEach(issue -> blockers.put(issue.code(), issue));
        issue(blockers, code, message);
        return new CutoverPreflight(report.listingId(), report.currentCutoverState(), blockers.isEmpty(),
                report.retryable(), List.copyOf(blockers.values()), report.warnings(), report.desiredState(),
                report.desiredRevision(), report.desiredFingerprint(), report.currentExternalItemId(),
                report.needsAdoption(), report.bootstrapEnsureRequired(), report.agentInspectionDigest(),
                report.providerStatus(), report.agentWritesEnabled(), report.agentAdoptionEnabled());
    }

    private CutoverPreflight buildReport(UUID listingId, SalesChannelListing listing, ComputedDesiredIntent intent,
                                         String currentItemId, CutoverInspection inspection,
                                         Map<String, CutoverIssue> blockers, Map<String, CutoverIssue> warnings,
                                         boolean needsAdoption, boolean retryable, boolean bootstrapEnsure) {
        return new CutoverPreflight(listingId.toString(),
                listing == null ? SalesChannelConvergenceCutoverState.UNCERTIFIED : listing.getConvergenceCutoverState(),
                blockers.isEmpty(), retryable, List.copyOf(blockers.values()), List.copyOf(warnings.values()),
                intent == null ? listing == null ? null : listing.getDesiredState().name() : intent.desiredState().name(),
                listing == null ? null : listing.getDesiredRevision(),
                intent == null ? listing == null ? null : listing.getDesiredFingerprint() : intent.fingerprint(),
                currentItemId, needsAdoption, bootstrapEnsure,
                inspection == null ? null : inspection.inspectionDigest(),
                inspection == null ? null
                        : inspection.providerObservationErrorCode() != null
                        || inspection.providerObservation() == null ? "UNAVAILABLE"
                        : inspection.providerObservation().status().name(),
                inspection != null && inspection.v2WritesEnabled(),
                inspection != null && inspection.v2AdoptionEnabled());
    }

    private CutoverPreflight emptyReport(UUID listingId) {
        LinkedHashMap<String, CutoverIssue> blockers = new LinkedHashMap<>();
        issue(blockers, "LISTING_NOT_FOUND", "The sales channel listing does not exist");
        return buildReport(listingId, null, null, null, null, blockers, new LinkedHashMap<>(), false, false, true);
    }

    private static void issue(Map<String, CutoverIssue> target, String code, String message) {
        target.putIfAbsent(code, new CutoverIssue(code, message));
    }

    private static boolean exactIdentity(String value, int maxLength) {
        return value != null && !value.isBlank() && value.length() <= maxLength && value.equals(value.strip());
    }

    private record Evaluation(SalesChannelListing listing, SalesChannelAccount account, Product product,
                              SalesChannelListingGeneration currentGeneration, ComputedDesiredIntent intent,
                              CutoverInspection inspection, Map<String, Object> ensurePayload,
                              CutoverPreflight report) { }

    private record Finalization(UUID certificationId, String errorCode) { }
}
