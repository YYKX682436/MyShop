package com.myshop.saleschannel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.constant.SalesChannelAccountStatus;
import com.myshop.constant.SalesChannelGenerationState;
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
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.OperationObservation;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.OperationState;
import com.myshop.service.SalesChannelDesiredStatePlanner;
import com.myshop.service.SalesChannelOperationClaimService;
import com.myshop.service.impl.SalesChannelOperationServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SalesChannelWorkerTest {

    private static final String FINGERPRINT = "a".repeat(64);

    @Test
    void workerBeanIsNotRegisteredWhenFeatureFlagIsFalse() {
        new ApplicationContextRunner()
                .withUserConfiguration(WorkerConfiguration.class)
                .withPropertyValues("sales-channel.worker.enabled=false")
                .run(context -> assertFalse(context.containsBean("salesChannelWorker")));
    }

    @Test
    void offlineCommandPostsExplicitNullSnapshotAndProjectsAgentSuccess() {
        Fixture fixture = new Fixture(SalesChannelOperationType.ENSURE_LISTING, "OFFLINE", null);
        when(fixture.client.ensureListing(any(), isNull())).thenAnswer(invocation -> {
            EnsureListingRequest request = invocation.getArgument(0);
            return observation(request, OperationState.SUCCEEDED, null, null);
        });

        fixture.worker.runBatch();

        var request = org.mockito.ArgumentCaptor.forClass(EnsureListingRequest.class);
        verify(fixture.client).ensureListing(request.capture(), isNull());
        assertEquals(fixture.operation.getIdempotencyKey(), request.getValue().requestId());
        assertEquals(fixture.listingId.toString(), request.getValue().listingKey());
        assertEquals("agent-account", request.getValue().accountId());
        assertEquals(7L, request.getValue().revision());
        assertEquals(DesiredListingState.OFFLINE, request.getValue().desiredState());
        assertNull(request.getValue().desiredSnapshot());
        verify(fixture.operationService).markSucceeded(eq(fixture.operation.getId()), anyMap(), eq(FINGERPRINT), isNull());
        verify(fixture.planner).refresh(fixture.listingId);
    }

    @Test
    void onlineCommandCarriesFrozenSnapshotAndRequiresTheReturnedProviderIdentity() {
        Fixture fixture = new Fixture(SalesChannelOperationType.ENSURE_LISTING, "ONLINE", null);
        when(fixture.client.ensureListing(any(), isNull())).thenAnswer(invocation -> observation(
                invocation.getArgument(0), OperationState.SUCCEEDED, "provider-item-1", null));

        fixture.worker.runBatch();

        var request = org.mockito.ArgumentCaptor.forClass(EnsureListingRequest.class);
        verify(fixture.client).ensureListing(request.capture(), isNull());
        assertEquals(DesiredListingState.ONLINE, request.getValue().desiredState());
        assertEquals(fixture.listingId.toString(), request.getValue().desiredSnapshot().listingKey());
        verify(fixture.operationService).markSucceeded(eq(fixture.operation.getId()), anyMap(), eq(FINGERPRINT), isNull());
    }

    @Test
    void workerReplansBeforeClaimSoAStaleUnstartedCommandIsNeverSent() {
        Fixture fixture = new Fixture(SalesChannelOperationType.ENSURE_LISTING, "ONLINE", null);
        fixture.listing.activateConvergence(LocalDateTime.now());
        doAnswer(invocation -> {
            fixture.operation.supersede(LocalDateTime.now());
            return fixture.listing;
        }).when(fixture.planner).refresh(fixture.listingId);

        fixture.worker.runBatch();

        assertEquals(SalesChannelOperationStatus.SUPERSEDED, fixture.operation.getStatus());
        verify(fixture.operationService, never()).markRunning(fixture.operation.getId());
        verify(fixture.client, never()).ensureListing(any(), isNull());
    }

    @Test
    void applyingObservationReschedulesTheSameDurableCommand() {
        Fixture fixture = new Fixture(SalesChannelOperationType.ENSURE_LISTING, "OFFLINE", null);
        when(fixture.client.ensureListing(any(), isNull())).thenAnswer(invocation -> observation(
                invocation.getArgument(0), OperationState.APPLYING, null, null));

        fixture.worker.runBatch();
        for (int retry = 1; retry < 12; retry++) {
            fixture.operation.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
            fixture.worker.runBatch();
        }

        assertEquals(SalesChannelOperationStatus.PENDING, fixture.operation.getStatus());
        assertEquals(12, fixture.operation.getAttemptCount());
        assertEquals("AGENT_APPLYING", fixture.operation.getLastErrorCode());
        assertEquals("myshop-ensure-command", fixture.operation.getIdempotencyKey());
        var requests = org.mockito.ArgumentCaptor.forClass(EnsureListingRequest.class);
        verify(fixture.client, org.mockito.Mockito.times(12)).ensureListing(requests.capture(), isNull());
        assertTrue(requests.getAllValues().stream().allMatch(request ->
                requests.getAllValues().getFirst().requestId().equals(request.requestId())
                        && java.util.Objects.equals(requests.getAllValues().getFirst().desiredSnapshot(),
                                request.desiredSnapshot())));
        verify(fixture.operationService, org.mockito.Mockito.times(12)).reschedule(
                eq(fixture.operation.getId()), eq("AGENT_APPLYING"),
                any(), any(), isNull());
    }

    @Test
    void agentFailureIsProjectedAsTerminalFailure() {
        Fixture fixture = new Fixture(SalesChannelOperationType.ENSURE_LISTING, "ONLINE", null);
        when(fixture.client.ensureListing(any(), isNull())).thenAnswer(invocation -> observation(
                invocation.getArgument(0), OperationState.FAILED, null,
                new com.myshop.saleschannel.convergence.XianyuConvergenceContracts.OperationError(
                        "SKU_IDENTITY_CONFLICT", "Agent found a conflicting stable SKU identity")));

        fixture.worker.runBatch();

        assertEquals(SalesChannelOperationStatus.FAILED, fixture.operation.getStatus());
        assertEquals("SKU_IDENTITY_CONFLICT", fixture.operation.getLastErrorCode());
        verify(fixture.operationService).markFailed(fixture.operation.getId(),
                "SKU_IDENTITY_CONFLICT", "Agent found a conflicting stable SKU identity", null);
        verify(fixture.planner).refresh(fixture.listingId);
    }

    @Test
    void onlineSuccessWithoutExactProviderIdentityFailsClosed() {
        Fixture fixture = new Fixture(SalesChannelOperationType.ENSURE_LISTING, "ONLINE", null);
        when(fixture.client.ensureListing(any(), isNull())).thenAnswer(invocation -> observation(
                invocation.getArgument(0), OperationState.SUCCEEDED, null, null));

        fixture.worker.runBatch();

        assertEquals(SalesChannelOperationStatus.FAILED, fixture.operation.getStatus());
        assertEquals("AGENT_RESULT_INCOMPLETE", fixture.operation.getLastErrorCode());
        verify(fixture.operationService).markFailed(fixture.operation.getId(),
                "AGENT_RESULT_INCOMPLETE", "ONLINE success must include the exact external_item_id", null);
        verify(fixture.planner).refresh(fixture.listingId);
    }

    @Test
    void uncertainHttpFailureReplaysTheSameRequestWithoutCreatingUnknownOrReconcile() {
        Fixture fixture = new Fixture(SalesChannelOperationType.ENSURE_LISTING, "OFFLINE", null);
        when(fixture.client.ensureListing(any(), isNull())).thenThrow(new SalesChannelProviderClientException(
                SalesChannelProviderClientException.Disposition.SAFE_RETRYABLE,
                "AGENT_REPLAY_REQUIRED", "Agent response was lost"));

        fixture.worker.runBatch();

        assertEquals(SalesChannelOperationStatus.PENDING, fixture.operation.getStatus());
        assertEquals(1, fixture.operation.getAttemptCount());
        assertEquals("myshop-ensure-command", fixture.operation.getIdempotencyKey());
        assertEquals("AGENT_REPLAY_REQUIRED", fixture.operation.getLastErrorCode());
    }

    @Test
    void historicalProviderMechanicsAreNeverDispatchedByTheWorker() {
        Fixture fixture = new Fixture(SalesChannelOperationType.PUBLISH, "ONLINE", null);

        fixture.worker.runBatch();

        verifyNoInteractions(fixture.client);
        verify(fixture.operationService, never()).markRunning(any());
    }

    @Test
    void staleEnsureLeaseUsesLocalRecoveryAndKeepsItsCommandIdentity() {
        Fixture fixture = new Fixture(SalesChannelOperationType.ENSURE_LISTING, "OFFLINE", null);
        fixture.operation.start(LocalDateTime.now().minusMinutes(10));
        when(fixture.operationRepository.findStaleEnsureRunningBefore(any(), eq(10)))
                .thenReturn(List.of(fixture.operation));
        when(fixture.claimService.selectReadyIds(any(), anyInt()))
                .thenReturn(List.of());

        fixture.worker.runBatch();

        verify(fixture.operationService).recoverStaleEnsureAsReplay(
                eq(fixture.operation.getId()), any());
        assertEquals("myshop-ensure-command", fixture.operation.getIdempotencyKey());
        verifyNoInteractions(fixture.client);
    }

    @Test
    void adoptionUsesStableIdentityAndMustCompleteBeforeEnsureIsPosted() {
        Fixture fixture = new Fixture(SalesChannelOperationType.ENSURE_LISTING, "OFFLINE", null);
        SalesChannelListingGeneration current = new SalesChannelListingGeneration(
                fixture.listingId, fixture.accountId, 1L, "legacy-item-123",
                SalesChannelGenerationState.CURRENT, "LEGACY_MIGRATION");
        when(fixture.generationRepository.findCurrentByListingId(fixture.listingId))
                .thenReturn(Optional.of(current));
        when(fixture.operationService.markConvergenceAdopted(fixture.listingId, "legacy-item-123"))
                .thenReturn(true);
        when(fixture.client.adoptExistingItem(any())).thenAnswer(invocation -> {
            AdoptExistingItemRequest request = invocation.getArgument(0);
            return new OperationObservation(request.requestId(), request.listingKey(), OperationState.SUCCEEDED,
                    null, "legacy-item-123", null, null, null);
        });
        when(fixture.client.ensureListing(any(), isNull())).thenAnswer(invocation -> observation(
                invocation.getArgument(0), OperationState.SUCCEEDED, null, null));

        fixture.worker.runBatch();

        var adoption = org.mockito.ArgumentCaptor.forClass(AdoptExistingItemRequest.class);
        verify(fixture.client).adoptExistingItem(adoption.capture());
        assertEquals("adopt-" + fixture.listingId + "-"
                        + "" + sha256("legacy-item-123"), adoption.getValue().requestId());
        assertEquals(fixture.listingId.toString(), adoption.getValue().listingKey());
        assertEquals("agent-account", adoption.getValue().accountId());
        assertEquals("legacy-item-123", adoption.getValue().externalItemId());
        verify(fixture.operationService).markConvergenceAdopted(fixture.listingId, "legacy-item-123");
        verify(fixture.client).ensureListing(any(), isNull());
    }

    @Test
    void applyingAdoptionReplaysTheSameExactRequestBeforeEnsure() {
        Fixture fixture = new Fixture(SalesChannelOperationType.ENSURE_LISTING, "OFFLINE", null);
        SalesChannelListingGeneration current = new SalesChannelListingGeneration(
                fixture.listingId, fixture.accountId, 1L, "legacy-item-123",
                SalesChannelGenerationState.CURRENT, "LEGACY_MIGRATION");
        when(fixture.generationRepository.findCurrentByListingId(fixture.listingId))
                .thenReturn(Optional.of(current));
        when(fixture.operationService.markConvergenceAdopted(fixture.listingId, "legacy-item-123"))
                .thenReturn(true);
        AtomicInteger attempt = new AtomicInteger();
        when(fixture.client.adoptExistingItem(any())).thenAnswer(invocation -> {
            AdoptExistingItemRequest request = invocation.getArgument(0);
            OperationState state = attempt.getAndIncrement() == 0 ? OperationState.APPLYING : OperationState.SUCCEEDED;
            return new OperationObservation(request.requestId(), request.listingKey(), state,
                    null, state == OperationState.SUCCEEDED ? "legacy-item-123" : null,
                    null, null, null);
        });
        when(fixture.client.ensureListing(any(), isNull())).thenAnswer(invocation -> observation(
                invocation.getArgument(0), OperationState.SUCCEEDED, null, null));

        fixture.worker.runBatch();
        assertEquals(SalesChannelOperationStatus.PENDING, fixture.operation.getStatus());
        verify(fixture.client, never()).ensureListing(any(), isNull());
        fixture.operation.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
        fixture.worker.runBatch();

        var requests = org.mockito.ArgumentCaptor.forClass(AdoptExistingItemRequest.class);
        verify(fixture.client, org.mockito.Mockito.times(2)).adoptExistingItem(requests.capture());
        assertEquals(requests.getAllValues().get(0).requestId(), requests.getAllValues().get(1).requestId());
        assertEquals(requests.getAllValues().get(0), requests.getAllValues().get(1));
        var ensure = org.mockito.ArgumentCaptor.forClass(EnsureListingRequest.class);
        verify(fixture.client).ensureListing(ensure.capture(), isNull());
        assertEquals(fixture.operation.getIdempotencyKey(), ensure.getValue().requestId());
        assertEquals(fixture.operation.getDesiredRevision(), ensure.getValue().revision());
    }

    @Test
    void adoptedMarkerSkipsDuplicateAdoptionForTheSameCurrentIdentity() {
        Fixture fixture = new Fixture(SalesChannelOperationType.ENSURE_LISTING, "OFFLINE", null);
        SalesChannelListingGeneration current = new SalesChannelListingGeneration(
                fixture.listingId, fixture.accountId, 1L, "already-adopted-item",
                SalesChannelGenerationState.CURRENT, "LEGACY_MIGRATION");
        fixture.listing.setConvergenceAdoptedExternalItemId("already-adopted-item");
        when(fixture.generationRepository.findCurrentByListingId(fixture.listingId))
                .thenReturn(Optional.of(current));
        when(fixture.client.ensureListing(any(), isNull())).thenAnswer(invocation -> observation(
                invocation.getArgument(0), OperationState.SUCCEEDED, null, null));

        fixture.worker.runBatch();

        verify(fixture.client, never()).adoptExistingItem(any());
        verify(fixture.client).ensureListing(any(), isNull());
    }

    @Test
    void adoptionResultIsRejectedIfTheLegacyCurrentIdentityChangedBeforeLocalMarkerCommit() {
        Fixture fixture = new Fixture(SalesChannelOperationType.ENSURE_LISTING, "OFFLINE", null);
        SalesChannelListingGeneration current = new SalesChannelListingGeneration(
                fixture.listingId, fixture.accountId, 1L, "stale-legacy-item",
                SalesChannelGenerationState.CURRENT, "LEGACY_MIGRATION");
        when(fixture.generationRepository.findCurrentByListingId(fixture.listingId))
                .thenReturn(Optional.of(current));
        when(fixture.client.adoptExistingItem(any())).thenAnswer(invocation -> {
            AdoptExistingItemRequest request = invocation.getArgument(0);
            return new OperationObservation(request.requestId(), request.listingKey(), OperationState.SUCCEEDED,
                    null, "stale-legacy-item", null, null, null);
        });
        when(fixture.operationService.markConvergenceAdopted(fixture.listingId, "stale-legacy-item"))
                .thenReturn(false);

        fixture.worker.runBatch();

        assertEquals(SalesChannelOperationStatus.FAILED, fixture.operation.getStatus());
        assertEquals("ADOPTION_CURRENT_IDENTITY_CHANGED", fixture.operation.getLastErrorCode());
        verify(fixture.client, never()).ensureListing(any(), isNull());
    }

    @Test
    void failedAdoptionBlocksEnsureUntilIdentityConflictIsResolved() {
        Fixture fixture = new Fixture(SalesChannelOperationType.ENSURE_LISTING, "OFFLINE", null);
        SalesChannelListingGeneration current = new SalesChannelListingGeneration(
                fixture.listingId, fixture.accountId, 1L, "legacy-item-123",
                SalesChannelGenerationState.CURRENT, "LEGACY_MIGRATION");
        when(fixture.generationRepository.findCurrentByListingId(fixture.listingId))
                .thenReturn(Optional.of(current));
        when(fixture.client.adoptExistingItem(any())).thenAnswer(invocation -> {
            AdoptExistingItemRequest request = invocation.getArgument(0);
            return new OperationObservation(request.requestId(), request.listingKey(), OperationState.FAILED,
                    null, null, null, null,
                    new com.myshop.saleschannel.convergence.XianyuConvergenceContracts.OperationError(
                            "ITEM_ALREADY_BOUND", "Identity belongs to another listing"));
        });

        fixture.worker.runBatch();

        assertEquals(SalesChannelOperationStatus.FAILED, fixture.operation.getStatus());
        assertEquals("ITEM_ALREADY_BOUND", fixture.operation.getLastErrorCode());
        verify(fixture.client, never()).ensureListing(any(), isNull());
        verify(fixture.planner).refresh(fixture.listingId);
    }

    private static EnsureListingOperationObservation observation(EnsureListingRequest request,
                                                                  OperationState state,
                                                                  String externalItemId,
                                                                  com.myshop.saleschannel.convergence.XianyuConvergenceContracts.OperationError error) {
        return new EnsureListingOperationObservation(request.requestId(), request.listingKey(),
                request.revision(), state, externalItemId, error);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Map<String, Object> onlineSnapshot(UUID listingId) {
        try (InputStream stream = SalesChannelWorkerTest.class.getResourceAsStream(
                "/contracts/ensure_listing_v2.json")) {
            if (stream == null) throw new IllegalStateException("Missing shared ensure listing fixture");
            ObjectMapper mapper = new ObjectMapper();
            var root = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(stream);
            root.put("listing_key", listingId.toString());
            ((com.fasterxml.jackson.databind.node.ObjectNode) root.get("desired_snapshot"))
                    .put("listing_key", listingId.toString());
            return mapper.convertValue(root.get("desired_snapshot"), Map.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to load the shared desired listing fixture", exception);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(SalesChannelWorker.class)
    static class WorkerConfiguration { }

    private static final class Fixture {
        final UUID listingId = UUID.randomUUID();
        final UUID accountId = UUID.randomUUID();
        final SalesChannelOperation operation;
        final SalesChannelOperationClaimService claimService = mock(SalesChannelOperationClaimService.class);
        final SalesChannelOperationRepository operationRepository = mock(SalesChannelOperationRepository.class);
        final SalesChannelListingRepository listingRepository = mock(SalesChannelListingRepository.class);
        final SalesChannelListingGenerationRepository generationRepository = mock(SalesChannelListingGenerationRepository.class);
        final SalesChannelAccountRepository accountRepository = mock(SalesChannelAccountRepository.class);
        final SalesChannelOperationServiceImpl operationService = mock(SalesChannelOperationServiceImpl.class);
        final XianyuConvergenceClient client = mock(XianyuConvergenceClient.class);
        final SalesChannelDesiredStatePlanner planner = mock(SalesChannelDesiredStatePlanner.class);
        final ObjectMapper objectMapper = new ObjectMapper();
        final SalesChannelListing listing = new SalesChannelListing();
        final SalesChannelAccount account = new SalesChannelAccount();
        final SalesChannelWorker worker;

        Fixture(SalesChannelOperationType operationType, String desiredState, Map<String, Object> snapshot) {
            Object payloadSnapshot = snapshot;
            if ("ONLINE".equals(desiredState) && payloadSnapshot == null) payloadSnapshot = onlineSnapshot(listingId);
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("listing_key", listingId.toString());
            payload.put("account_id", "agent-account");
            payload.put("revision", 7L);
            payload.put("desired_state", desiredState);
            payload.put("desired_snapshot", payloadSnapshot);
            payload.put("desired_fingerprint", FINGERPRINT);
            operation = new SalesChannelOperation(listingId, operationType, 7,
                    "myshop-ensure-command", payload);
            operation.setId(UUID.randomUUID());
            listing.setId(listingId);
            listing.setSalesChannelAccountId(accountId);
            account.setId(accountId);
            account.setProviderAccountId("agent-account");
            account.setStatus(SalesChannelAccountStatus.CONNECTED);
            account.setLastVerifiedAt(LocalDateTime.now());

            when(claimService.selectReadyIds(any(), anyInt())).thenReturn(List.of(operation.getId()));
            when(operationRepository.findById(operation.getId())).thenReturn(Optional.of(operation));
            when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));
            when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
            when(operationRepository.findStaleEnsureRunningBefore(any(), anyInt())).thenReturn(List.of());
            doAnswer(invocation -> operation.start(LocalDateTime.now()))
                    .when(operationService).markRunning(operation.getId());
            doAnswer(invocation -> operation.succeed(invocation.getArgument(1), LocalDateTime.now()))
                    .when(operationService).markSucceeded(eq(operation.getId()), anyMap(), any(), isNull());
            doAnswer(invocation -> operation.fail(invocation.getArgument(1), invocation.getArgument(2),
                    LocalDateTime.now()))
                    .when(operationService).markFailed(eq(operation.getId()), any(), any(), isNull());
            doAnswer(invocation -> operation.reschedule(invocation.getArgument(1), invocation.getArgument(2),
                    invocation.getArgument(3)))
                    .when(operationService).reschedule(eq(operation.getId()), any(), any(), any(), isNull());

            worker = new SalesChannelWorker(claimService, operationRepository, listingRepository,
                    generationRepository, accountRepository, operationService, client, planner,
                    objectMapper, 10, 300_000L);
        }
    }
}
