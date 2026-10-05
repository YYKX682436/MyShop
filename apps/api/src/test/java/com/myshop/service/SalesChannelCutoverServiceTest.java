package com.myshop.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.constant.SalesChannelAccountStatus;
import com.myshop.constant.SalesChannelConvergenceCutoverState;
import com.myshop.constant.SalesChannelGenerationState;
import com.myshop.constant.SalesChannelListingDesiredState;
import com.myshop.constant.SalesChannelListingManagementMode;
import com.myshop.constant.SalesChannelProvider;
import com.myshop.entity.Product;
import com.myshop.entity.SalesChannelAccount;
import com.myshop.entity.SalesChannelListing;
import com.myshop.entity.SalesChannelListingGeneration;
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
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.CutoverManagedItem;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.CutoverNonterminalOperation;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.CutoverProviderObservation;
import com.myshop.service.SalesChannelDesiredStatePlanner.ComputedDesiredIntent;
import com.myshop.service.impl.SalesChannelOperationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SalesChannelCutoverServiceTest {

    private static final UUID LISTING_ID = UUID.randomUUID();
    private static final UUID ACCOUNT_ROW_ID = UUID.randomUUID();
    private static final String ACCOUNT_ID = "agent-account-1";
    private static final String FINGERPRINT = "a".repeat(64);

    @Mock private SalesChannelListingRepository listingRepository;
    @Mock private SalesChannelAccountRepository accountRepository;
    @Mock private SalesChannelListingGenerationRepository generationRepository;
    @Mock private SalesChannelOperationRepository operationRepository;
    @Mock private SalesChannelCutoverCertificationRepository certificationRepository;
    @Mock private SalesChannelDesiredStatePlanner planner;
    @Mock private SalesChannelOperationServiceImpl operationService;
    @Mock private XianyuConvergenceClient convergenceClient;
    @Mock private PlatformTransactionManager transactionManager;

    private SalesChannelListing listing;
    private SalesChannelAccount account;
    private Product product;
    private SalesChannelCutoverService service;

    @BeforeEach
    void setUp() {
        listing = new SalesChannelListing();
        listing.setId(LISTING_ID);
        listing.setSalesChannelAccountId(ACCOUNT_ROW_ID);
        listing.setDesiredFingerprint(FINGERPRINT);
        listing.incrementDesiredRevision();
        listing.setManagementMode(SalesChannelListingManagementMode.MANAGED);

        account = new SalesChannelAccount();
        account.setProvider(SalesChannelProvider.XIANYU);
        account.setProviderAccountId(ACCOUNT_ID);
        account.setStatus(SalesChannelAccountStatus.CONNECTED);
        account.setEnabled(true);
        account.setLastVerifiedAt(LocalDateTime.now());

        product = new Product();
        product.setEnabled(true);
        product.setIsDeleted(0);

        when(listingRepository.findById(LISTING_ID)).thenReturn(Optional.of(listing));
        when(accountRepository.findById(ACCOUNT_ROW_ID)).thenReturn(Optional.of(account));
        when(generationRepository.findCurrentByListingId(LISTING_ID)).thenReturn(Optional.empty());
        when(generationRepository.findCandidateByListingId(LISTING_ID)).thenReturn(Optional.empty());
        when(operationRepository.findBySalesChannelListingIdOrderByCreatedAtAsc(LISTING_ID)).thenReturn(List.of());
        when(planner.computeCurrentIntent(LISTING_ID)).thenReturn(new ComputedDesiredIntent(
                account, product, SalesChannelListingDesiredState.OFFLINE, null, FINGERPRINT));
        lenient().when(convergenceClient.inspectListing(any(CutoverInspectionRequest.class)))
                .thenAnswer(invocation -> inspection(invocation.getArgument(0), null, List.of(),
                        null, List.of(), null, null));
        service = new SalesChannelCutoverService(listingRepository, accountRepository, generationRepository,
                operationRepository, certificationRepository, planner, operationService, convergenceClient,
                new ObjectMapper(), transactionManager, false);
    }

    @Test
    void identitylessListingCanBeCertifiedWithBootstrapEnsure() {
        var report = service.preflight(LISTING_ID);
        assertTrue(report.eligible(), report.blockers().toString());
        assertTrue(report.bootstrapEnsureRequired());
        assertFalse(report.needsAdoption());
    }

    @Test
    void legacyItemWithoutManagedMarkerIsBlocked() {
        currentItem("item-1");
        provider(AgentProviderStatus.ACTIVE, true);
        var report = service.preflight(LISTING_ID);
        assertBlocker(report, "AGENT_OWNERSHIP_NOT_CLAIMED");
        assertTrue(report.needsAdoption());
    }

    @Test
    void managedLegacyItemWithoutBindingIsEligibleForAdoption() {
        currentItem("item-1");
        provider(AgentProviderStatus.OFFLINE, true);
        inspection((request) -> inspection(request, new CutoverManagedItem("item-1", "MYSHOP"),
                List.of(), null, List.of(), provider(AgentProviderStatus.OFFLINE, true), null));

        var report = service.preflight(LISTING_ID);
        assertTrue(report.eligible(), report.blockers().toString());
        assertTrue(report.needsAdoption());
        assertTrue(report.warnings().stream().anyMatch(issue -> issue.code().equals("ADOPTION_REQUIRED")));
    }

    @Test
    void exactCurrentBindingNeedsNoAdoptionAndExactLifecycleNeedsNoBootstrap() {
        currentItem("item-1");
        CutoverBinding exact = binding(LISTING_ID.toString(), "item-1", AgentBindingState.CURRENT);
        CutoverLifecycleHead head = new CutoverLifecycleHead(1L, FINGERPRINT, false);
        CutoverProviderObservation provider = provider(AgentProviderStatus.ACTIVE, true);
        inspection(request -> inspection(request, new CutoverManagedItem("item-1", "MYSHOP"),
                List.of(exact), head, List.of(), provider, null));

        var report = service.preflight(LISTING_ID);
        assertTrue(report.eligible(), report.blockers().toString());
        assertFalse(report.needsAdoption());
        assertFalse(report.bootstrapEnsureRequired());
    }

    @Test
    void identityConflictsAndRetiredOrTransitionBindingsBlock() {
        currentItem("item-1");
        CutoverBinding wrongListingItem = binding(LISTING_ID.toString(), "item-2", AgentBindingState.CURRENT);
        inspection(request -> inspection(request, new CutoverManagedItem("item-1", "MYSHOP"),
                List.of(wrongListingItem), null, List.of(), provider(AgentProviderStatus.ACTIVE, true), null));
        assertBlocker(service.preflight(LISTING_ID), "AGENT_LISTING_IDENTITY_CONFLICT");

        CutoverBinding otherListing = binding("other-listing", "item-1", AgentBindingState.CURRENT);
        inspection(request -> inspection(request, new CutoverManagedItem("item-1", "MYSHOP"),
                List.of(otherListing), null, List.of(), provider(AgentProviderStatus.ACTIVE, true), null));
        assertBlocker(service.preflight(LISTING_ID), "AGENT_ITEM_IDENTITY_CONFLICT");

        CutoverBinding retired = binding(LISTING_ID.toString(), "item-1", AgentBindingState.RETIRED);
        inspection(request -> inspection(request, new CutoverManagedItem("item-1", "MYSHOP"),
                List.of(retired), null, List.of(), provider(AgentProviderStatus.ACTIVE, true), null));
        assertBlocker(service.preflight(LISTING_ID), "AGENT_RETIRED_IDENTITY_CONFLICT");

        CutoverBinding transition = binding(LISTING_ID.toString(), "item-1", AgentBindingState.TRANSITION_PENDING);
        inspection(request -> inspection(request, new CutoverManagedItem("item-1", "MYSHOP"),
                List.of(transition), null, List.of(), provider(AgentProviderStatus.ACTIVE, true), null));
        assertBlocker(service.preflight(LISTING_ID), "AGENT_TRANSITION_PENDING");
    }

    @Test
    void agentCurrentBindingWithoutLocalCurrentGenerationBlocks() {
        CutoverBinding unexpected = binding(LISTING_ID.toString(), "item-1", AgentBindingState.CURRENT);
        inspection(request -> inspection(request, null, List.of(unexpected), null, List.of(), null, null));
        assertBlocker(service.preflight(LISTING_ID), "AGENT_UNEXPECTED_CURRENT_BINDING");
    }

    @Test
    void lifecycleRevisionAndFingerprintAreFenced() {
        inspection(request -> inspection(request, null, List.of(),
                new CutoverLifecycleHead(2L, FINGERPRINT, false), List.of(), null, null));
        assertBlocker(service.preflight(LISTING_ID), "AGENT_LIFECYCLE_AHEAD");

        inspection(request -> inspection(request, null, List.of(),
                new CutoverLifecycleHead(1L, "b".repeat(64), false), List.of(), null, null));
        assertBlocker(service.preflight(LISTING_ID), "AGENT_LIFECYCLE_CONFLICT");

        inspection(request -> inspection(request, null, List.of(),
                new CutoverLifecycleHead(0L, "b".repeat(64), false), List.of(), null, null));
        var behind = service.preflight(LISTING_ID);
        assertTrue(behind.eligible(), behind.blockers().toString());
        assertTrue(behind.bootstrapEnsureRequired());
        assertTrue(behind.warnings().stream().anyMatch(issue -> issue.code().equals("AGENT_LIFECYCLE_BEHIND")));
    }

    @Test
    void providerMissingUnavailableAndAgentOperationsBlock() {
        currentItem("item-1");
        inspection(request -> inspection(request, new CutoverManagedItem("item-1", "MYSHOP"),
                List.of(), null, List.of(), provider(AgentProviderStatus.MISSING, true), null));
        assertBlocker(service.preflight(LISTING_ID), "PROVIDER_ITEM_MISSING");

        CutoverProviderObservation unavailable = provider(AgentProviderStatus.UNAVAILABLE, false);
        inspection(request -> inspection(request, new CutoverManagedItem("item-1", "MYSHOP"),
                List.of(), null, List.of(), unavailable, null));
        assertBlocker(service.preflight(LISTING_ID), "PROVIDER_OBSERVATION_UNAVAILABLE");

        CutoverNonterminalOperation operation = new CutoverNonterminalOperation(
                "operation-1", "ENSURE_LISTING", "RUNNING", 1L, "item-1", true);
        inspection(request -> inspection(request, new CutoverManagedItem("item-1", "MYSHOP"),
                List.of(), null, List.of(operation), provider(AgentProviderStatus.ACTIVE, true), null));
        assertBlocker(service.preflight(LISTING_ID), "AGENT_OPERATION_IN_FLIGHT");
    }

    @Test
    void localIntentMustBeFreshAndProviderIdentityMustBeExact() {
        when(planner.computeCurrentIntent(LISTING_ID)).thenReturn(new ComputedDesiredIntent(
                account, product, SalesChannelListingDesiredState.OFFLINE, null, "b".repeat(64)));
        assertBlocker(service.preflight(LISTING_ID), "LOCAL_DESIRED_INTENT_STALE");

        currentItem("x".repeat(65));
        assertBlocker(service.preflight(LISTING_ID), "PROVIDER_ITEM_IDENTITY_MISMATCH");
    }

    private void currentItem(String itemId) {
        SalesChannelListingGeneration current = new SalesChannelListingGeneration(
                LISTING_ID, ACCOUNT_ROW_ID, 1L, itemId, SalesChannelGenerationState.CURRENT, "LEGACY_MIGRATION");
        when(generationRepository.findCurrentByListingId(LISTING_ID)).thenReturn(Optional.of(current));
    }

    private void managedItem(String itemId, String owner) {
        inspection(request -> inspection(request, new CutoverManagedItem(itemId, owner), List.of(), null,
                List.of(), provider(AgentProviderStatus.ACTIVE, true), null));
    }

    private CutoverProviderObservation provider(AgentProviderStatus status, boolean complete) {
        return new CutoverProviderObservation("item-1", status, complete, "2026-10-05T00:00:00Z");
    }

    private CutoverBinding binding(String listingKey, String itemId, AgentBindingState state) {
        return new CutoverBinding(listingKey, itemId, state, 1L, FINGERPRINT, 1);
    }

    private CutoverInspection inspection(CutoverInspectionRequest request, CutoverManagedItem managed,
                                         List<CutoverBinding> bindings, CutoverLifecycleHead head,
                                         List<CutoverNonterminalOperation> operations,
                                         CutoverProviderObservation provider, String providerError) {
        return new CutoverInspection(1, request.requestId(), request.accountId(), request.listingKey(),
                request.expectedExternalItemId(), false, false, managed, bindings, head, operations,
                provider, providerError, "c".repeat(64));
    }

    private void inspection(java.util.function.Function<CutoverInspectionRequest, CutoverInspection> response) {
        when(convergenceClient.inspectListing(any(CutoverInspectionRequest.class)))
                .thenAnswer(invocation -> response.apply(invocation.getArgument(0)));
    }

    private void assertBlocker(com.myshop.saleschannel.model.SalesChannelAdminDtos.CutoverPreflight report,
                               String code) {
        assertFalse(report.eligible(), report.blockers().toString());
        assertTrue(report.blockers().stream().anyMatch(issue -> issue.code().equals(code)),
                "missing blocker " + code + " in " + report.blockers());
    }
}
