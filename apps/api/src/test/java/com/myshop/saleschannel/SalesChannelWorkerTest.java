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
import com.myshop.repository.SalesChannelListingRepository;
import com.myshop.repository.SalesChannelListingGenerationRepository;
import com.myshop.repository.SalesChannelOperationRepository;
import com.myshop.saleschannel.model.DesiredListingSnapshot;
import com.myshop.saleschannel.model.SalesChannelDtos.IntegrationEnvelope;
import com.myshop.saleschannel.model.SalesChannelDtos.ListingOperationData;
import com.myshop.saleschannel.model.SalesChannelDtos.MediaUploadData;
import com.myshop.saleschannel.model.SalesChannelDtos.MediaType;
import com.myshop.saleschannel.model.SalesChannelDtos.Outcome;
import com.myshop.saleschannel.model.SalesChannelDtos.ReconcileData;
import com.myshop.saleschannel.xianyu.XianyuMediaMaterializer;
import com.myshop.service.SalesChannelDesiredStatePlanner;
import com.myshop.service.SalesChannelOperationClaimService;
import com.myshop.service.impl.SalesChannelOperationServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
    void mediaUploadTimeoutIsSafelyRescheduledWithoutMakingListingUnknown() {
        SalesChannelOperation operation = publishOperation("media timeout");
        WorkerFixture fixture = new WorkerFixture(operation, SalesChannelAccountStatus.CONNECTED);
        fixture.mediaMaterializerReturnsImage();
        when(fixture.providerClient.uploadMedia(any())).thenThrow(new SalesChannelProviderClientException(
                SalesChannelProviderClientException.Disposition.SAFE_RETRYABLE,
                "MEDIA_UPLOAD_TIMEOUT", "Media upload timed out"));

        fixture.worker.runBatch();

        assertEquals(SalesChannelOperationStatus.PENDING, operation.getStatus());
        assertEquals(1, operation.getAttemptCount());
        assertEquals("MEDIA_UPLOAD_TIMEOUT", operation.getLastErrorCode());
        verify(fixture.operationService, never()).markUnknown(any(), any(), any());
        verify(fixture.operationService, never()).requestReconciliation(any());
        verify(fixture.providerClient, never()).publish(any());
        verify(fixture.providerClient, never()).update(any(), any());
    }

    @Test
    void publishUnknownCreatesReconciliationBarrierAndDoesNotRepeatPublish() {
        SalesChannelOperation operation = publishOperation("publish unknown");
        WorkerFixture fixture = new WorkerFixture(operation, SalesChannelAccountStatus.CONNECTED);
        fixture.stubSuccessfulMediaUpload();
        fixture.mediaMaterializerReturnsImage();
        when(fixture.providerClient.publish(any())).thenReturn(new IntegrationEnvelope<>(
                operation.getIdempotencyKey(), Outcome.UNKNOWN, null,
                new com.myshop.saleschannel.model.SalesChannelDtos.IntegrationError(
                        "RESPONSE_LOST", "Publish response could not be confirmed", false)));

        fixture.worker.runBatch();
        fixture.worker.runBatch();

        assertEquals(SalesChannelOperationStatus.UNKNOWN, operation.getStatus());
        verify(fixture.operationService).requestReconciliation(operation.getId());
        verify(fixture.providerClient).publish(any());
        verify(fixture.providerClient, never()).update(any(), any());
    }

    @Test
    void unknownReconciliationOnlyRetriesReadOnlyReconcile() {
        SalesChannelOperation target = publishOperation("reconcile target");
        target.start(LocalDateTime.now());
        target.markUnknown("RESPONSE_LOST", "unconfirmed", LocalDateTime.now());
        UUID reconciliationId = UUID.randomUUID();
        SalesChannelOperation reconciliation = new SalesChannelOperation(target.getSalesChannelListingId(),
                SalesChannelOperationType.RECONCILE, target.getDesiredRevision(), "reconcile-unknown-test",
                Map.of("target_operation_id", target.getId().toString(),
                        "target_request_id", target.getIdempotencyKey()));
        reconciliation.setId(reconciliationId);
        WorkerFixture fixture = new WorkerFixture(reconciliation, SalesChannelAccountStatus.CONNECTED);
        fixture.operations.put(target.getId(), target);
        when(fixture.providerClient.reconcile(any())).thenReturn(new IntegrationEnvelope<>(
                reconciliation.getIdempotencyKey(), Outcome.SUCCEEDED,
                new ReconcileData(target.getIdempotencyKey(), Outcome.UNKNOWN, "PUBLISH", null,
                        target.getDesiredRevision(), null, null), null));

        fixture.worker.runBatch();

        assertEquals(SalesChannelOperationStatus.UNKNOWN, target.getStatus());
        assertEquals(SalesChannelOperationStatus.PENDING, reconciliation.getStatus());
        verify(fixture.providerClient).reconcile(any());
        verify(fixture.providerClient, never()).publish(any());
        verify(fixture.providerClient, never()).update(any(), any());
        verify(fixture.providerClient, never()).offline(any(), any());
        verify(fixture.providerClient, never()).delete(any(), any());
    }

    @Test
    void successfulReconciliationResolvesUnknownPublishWithoutPublishingAgain() {
        SalesChannelOperation target = publishOperation("reconcile success");
        target.start(LocalDateTime.now());
        target.markUnknown("RESPONSE_LOST", "unconfirmed", LocalDateTime.now());
        SalesChannelOperation reconciliation = reconciliationOperation(target, "reconcile-success-test");
        WorkerFixture fixture = new WorkerFixture(reconciliation, SalesChannelAccountStatus.CONNECTED);
        fixture.operations.put(target.getId(), target);
        when(fixture.providerClient.reconcile(any())).thenReturn(new IntegrationEnvelope<>(
                reconciliation.getIdempotencyKey(), Outcome.SUCCEEDED,
                new ReconcileData(target.getIdempotencyKey(), Outcome.SUCCEEDED, "PUBLISH", "item-123",
                        target.getDesiredRevision(), Map.of("item_id", "item-123"), null), null));
        doAnswer(invocation -> {
            Map<String, Object> targetResult = invocation.getArgument(2);
            Map<String, Object> reconciliationResult = invocation.getArgument(3);
            target.resolveUnknownAsSucceeded(targetResult, LocalDateTime.now());
            reconciliation.succeed(reconciliationResult, LocalDateTime.now());
            return true;
        }).when(fixture.operationService).resolveUnknownAsSucceeded(
                eq(reconciliation.getId()), eq(target.getId()), any(), any(), any());

        fixture.worker.runBatch();

        assertEquals(SalesChannelOperationStatus.SUCCEEDED, target.getStatus());
        assertEquals("item-123", target.getResult().get("item_id"));
        assertEquals(SalesChannelOperationStatus.SUCCEEDED, reconciliation.getStatus());
        verify(fixture.providerClient, never()).publish(any());
        verify(fixture.providerClient, never()).update(any(), any());
    }

    @Test
    void failedReconciliationReopensSameTargetOperationOnlyWhenAgentConfirmsNoEffect() {
        SalesChannelOperation target = publishOperation("reconcile retryable");
        target.start(LocalDateTime.now());
        target.markUnknown("RESPONSE_LOST", "unconfirmed", LocalDateTime.now());
        String originalIdempotencyKey = target.getIdempotencyKey();
        int originalAttempts = target.getAttemptCount();
        SalesChannelOperation reconciliation = reconciliationOperation(target, "reconcile-failed-test");
        WorkerFixture fixture = new WorkerFixture(reconciliation, SalesChannelAccountStatus.CONNECTED);
        fixture.operations.put(target.getId(), target);
        when(fixture.providerClient.reconcile(any())).thenReturn(new IntegrationEnvelope<>(
                reconciliation.getIdempotencyKey(), Outcome.SUCCEEDED,
                new ReconcileData(target.getIdempotencyKey(), Outcome.FAILED, "PUBLISH", null,
                        target.getDesiredRevision(), Map.of(), true), null));
        doAnswer(invocation -> {
            Map<String, Object> reconciliationResult = invocation.getArgument(2);
            LocalDateTime nextAttempt = invocation.getArgument(5);
            target.resolveUnknownAsRetryable("RECONCILED_NO_SIDE_EFFECT",
                    "Agent proved that publish did not execute", nextAttempt);
            reconciliation.succeed(reconciliationResult, LocalDateTime.now());
            return true;
        }).when(fixture.operationService).resolveUnknownAsRetryable(
                eq(reconciliation.getId()), eq(target.getId()), any(), any(), any(), any());

        fixture.worker.runBatch();

        assertEquals(SalesChannelOperationStatus.PENDING, target.getStatus());
        assertEquals(originalIdempotencyKey, target.getIdempotencyKey());
        assertEquals(originalAttempts, target.getAttemptCount());
        assertNotNull(target.getNextAttemptAt());
        assertEquals(SalesChannelOperationStatus.SUCCEEDED, reconciliation.getStatus());
        verify(fixture.providerClient, never()).publish(any());
        verify(fixture.providerClient, never()).update(any(), any());
    }

    @Test
    void authRequiredAccountFailsOperationWithoutRepeatedAgentCalls() {
        SalesChannelOperation operation = new SalesChannelOperation(UUID.randomUUID(),
                SalesChannelOperationType.OFFLINE, 1, "offline-auth-test", Map.of());
        operation.setId(UUID.randomUUID());
        WorkerFixture fixture = new WorkerFixture(operation, SalesChannelAccountStatus.AUTH_REQUIRED);

        fixture.worker.runBatch();
        fixture.worker.runBatch();

        assertEquals(SalesChannelOperationStatus.FAILED, operation.getStatus());
        assertEquals("ACCOUNT_AUTH_REQUIRED", operation.getLastErrorCode());
        verifyNoInteractions(fixture.providerClient);
    }

    @Test
    void existingItemWorkerUsesFrozenGenerationAfterCurrentGenerationChanges() {
        UUID listingId = UUID.randomUUID();
        UUID generationId = UUID.randomUUID();
        SalesChannelOperation operation = new SalesChannelOperation(listingId, generationId,
                SalesChannelOperationType.OFFLINE, 1, "offline-generation-a", Map.of());
        operation.setPayload(Map.of("desired_snapshot", snapshot(), "desired_fingerprint", FINGERPRINT));
        operation.setId(UUID.randomUUID());
        WorkerFixture fixture = new WorkerFixture(operation, SalesChannelAccountStatus.CONNECTED);
        fixture.listing.incrementDesiredRevision();
        SalesChannelListingGeneration frozen = new SalesChannelListingGeneration(
                listingId, fixture.account.getId(), 1L, "item-generation-a",
                SalesChannelGenerationState.RETIRED, "LEGACY_MIGRATION");
        frozen.setId(generationId);
        when(fixture.generationRepository.findById(generationId)).thenReturn(Optional.of(frozen));
        when(fixture.providerClient.offline(eq("item-generation-a"), any())).thenReturn(
                new IntegrationEnvelope<>(operation.getIdempotencyKey(), Outcome.SUCCEEDED,
                        new ListingOperationData("item-generation-a", null, null, false, 1L, List.of()), null));

        fixture.worker.runBatch();

        verify(fixture.providerClient).offline(eq("item-generation-a"), any());
        verify(fixture.providerClient, never()).delete(any(), any());
        verify(fixture.providerClient, never()).publish(any());
        verify(fixture.providerClient, never()).update(any(), any());
    }

    private static SalesChannelOperation publishOperation(String suffix) {
        UUID listingId = UUID.randomUUID();
        SalesChannelOperation operation = new SalesChannelOperation(listingId, SalesChannelOperationType.PUBLISH,
                1, "publish-" + suffix.replace(' ', '-'),
                Map.of("desired_snapshot", snapshot(), "desired_fingerprint", FINGERPRINT));
        operation.setId(UUID.randomUUID());
        return operation;
    }

    private static DesiredListingSnapshot snapshot() {
        return new DesiredListingSnapshot("account-123", "FISH_SHOP", "ONLINE",
                "Test listing", "Description", new BigDecimal("12.50"), new BigDecimal("15.00"),
                List.of("https://images.example.test/cover.jpg"), "cat-1", "Category", null, null,
                "leaf-1", null, List.of(), 3, List.of(), List.of(), "Address", "Address check",
                "delivery", "shipping", false, BigDecimal.ZERO);
    }

    private static SalesChannelOperation reconciliationOperation(SalesChannelOperation target, String key) {
        SalesChannelOperation reconciliation = new SalesChannelOperation(target.getSalesChannelListingId(),
                SalesChannelOperationType.RECONCILE, target.getDesiredRevision(), key,
                Map.of("target_operation_id", target.getId().toString(),
                        "target_request_id", target.getIdempotencyKey()));
        reconciliation.setId(UUID.randomUUID());
        return reconciliation;
    }

    @Configuration(proxyBeanMethods = false)
    @Import(SalesChannelWorker.class)
    static class WorkerConfiguration { }

    private static final class WorkerFixture {
        private final Map<UUID, SalesChannelOperation> operations = new HashMap<>();
        private final SalesChannelOperationClaimService claimService = mock(SalesChannelOperationClaimService.class);
        private final SalesChannelOperationRepository operationRepository = mock(SalesChannelOperationRepository.class);
        private final SalesChannelListingRepository listingRepository = mock(SalesChannelListingRepository.class);
        private final SalesChannelListingGenerationRepository generationRepository = mock(SalesChannelListingGenerationRepository.class);
        private final SalesChannelAccountRepository accountRepository = mock(SalesChannelAccountRepository.class);
        private final SalesChannelOperationServiceImpl operationService = mock(SalesChannelOperationServiceImpl.class);
        private final SalesChannelProviderClient providerClient = mock(SalesChannelProviderClient.class);
        private final SalesChannelDesiredStatePlanner planner = mock(SalesChannelDesiredStatePlanner.class);
        private final XianyuMediaMaterializer mediaMaterializer = mock(XianyuMediaMaterializer.class);
        private final SalesChannelListing listing = new SalesChannelListing();
        private final SalesChannelAccount account = new SalesChannelAccount();
        private final SalesChannelWorker worker;

        private WorkerFixture(SalesChannelOperation queuedOperation, SalesChannelAccountStatus accountStatus) {
            UUID accountId = UUID.randomUUID();
            UUID listingId = queuedOperation.getSalesChannelListingId();
            listing.setId(listingId);
            listing.setSalesChannelAccountId(accountId);
            listing.setProductId(UUID.randomUUID());
            account.setId(accountId);
            account.setProvider(com.myshop.constant.SalesChannelProvider.XIANYU);
            account.setProviderAccountId("account-123");
            account.setStatus(accountStatus);
            operations.put(queuedOperation.getId(), queuedOperation);

            when(operationRepository.findStaleRunningBefore(any(), anyInt())).thenReturn(List.of());
            when(claimService.selectReadyIds(any(), anyInt())).thenReturn(List.of(queuedOperation.getId()));
            when(operationRepository.findById(any())).thenAnswer(invocation ->
                    Optional.ofNullable(operations.get(invocation.getArgument(0))));
            when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));
            when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
            doAnswer(invocation -> {
                SalesChannelOperation operation = operations.get(invocation.getArgument(0));
                return operation != null && operation.start(LocalDateTime.now());
            }).when(operationService).markRunning(any());
            doAnswer(invocation -> {
                SalesChannelOperation operation = operations.get(invocation.getArgument(0));
                return operation != null && operation.fail(invocation.getArgument(1), invocation.getArgument(2),
                        LocalDateTime.now());
            }).when(operationService).markFailed(any(), any(), any());
            doAnswer(invocation -> {
                SalesChannelOperation operation = operations.get(invocation.getArgument(0));
                return operation != null && operation.markUnknown(invocation.getArgument(1),
                        invocation.getArgument(2), LocalDateTime.now());
            }).when(operationService).markUnknown(any(), any(), any());
            doAnswer(invocation -> {
                SalesChannelOperation operation = operations.get(invocation.getArgument(0));
                return operation != null && operation.reschedule(invocation.getArgument(1),
                        invocation.getArgument(2), invocation.getArgument(3));
            }).when(operationService).reschedule(any(), any(), any(), any());

            worker = new SalesChannelWorker(claimService, operationRepository, listingRepository, generationRepository, accountRepository,
                    operationService, providerClient, planner, mediaMaterializer, new ObjectMapper(), 10, 8, 300_000);
        }

        private void mediaMaterializerReturnsImage() {
            when(mediaMaterializer.readImage(any())).thenReturn(
                    new XianyuMediaMaterializer.MaterializedImage("cover.png", "image/png", new byte[]{1, 2, 3}));
        }

        private void stubSuccessfulMediaUpload() {
            when(providerClient.uploadMedia(any())).thenAnswer(invocation -> {
                com.myshop.saleschannel.model.SalesChannelDtos.MediaUploadCommand command = invocation.getArgument(0);
                assertEquals(MediaType.IMAGE, command.mediaType());
                return new IntegrationEnvelope<>(command.requestId(), Outcome.SUCCEEDED,
                        new MediaUploadData("media-123", "image"), null);
            });
        }
    }
}
