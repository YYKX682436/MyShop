package com.myshop.service.impl;

import com.myshop.constant.SalesChannelOperationStatus;
import com.myshop.constant.SalesChannelOperationType;
import com.myshop.constant.SalesChannelListingManagementMode;
import com.myshop.constant.SalesChannelListingDesiredState;
import com.myshop.constant.SalesChannelListingStatus;
import com.myshop.constant.SalesChannelGenerationState;
import com.myshop.entity.SalesChannelAccount;
import com.myshop.entity.SalesChannelListing;
import com.myshop.entity.SalesChannelListingGeneration;
import com.myshop.entity.SalesChannelOperation;
import com.myshop.exception.BusinessException;
import com.myshop.constant.SalesChannelProvider;
import com.myshop.repository.SalesChannelAccountRepository;
import com.myshop.repository.SalesChannelListingRepository;
import com.myshop.repository.SalesChannelListingGenerationRepository;
import com.myshop.repository.SalesChannelListingGenerationSpecRepository;
import com.myshop.repository.ProductSpecRepository;
import com.myshop.repository.SalesChannelOperationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SalesChannelOperationServiceImplTest {

    @Mock private SalesChannelAccountRepository accountRepository;
    @Mock private SalesChannelListingRepository listingRepository;
    @Mock private SalesChannelListingGenerationRepository generationRepository;
    @Mock private SalesChannelListingGenerationSpecRepository generationSpecRepository;
    @Mock private ProductSpecRepository productSpecRepository;
    @Mock private SalesChannelOperationRepository operationRepository;
    @InjectMocks private SalesChannelOperationServiceImpl service;

    @Test
    void requestBuildsServerOwnedDeterministicKeyAndReusesExistingOperation() {
        UUID listingId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        SalesChannelListing listing = new SalesChannelListing();
        listing.setId(listingId);
        listing.setSalesChannelAccountId(accountId);
        listing.incrementDesiredRevision();
        SalesChannelAccount account = new SalesChannelAccount();
        account.setProvider(SalesChannelProvider.XIANYU);
        SalesChannelListingGeneration generation = generation(listingId, accountId, "item-1", true);
        String key = "XIANYU:" + listingId + ":" + generation.getId() + ":SYNC:1";

        when(listingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(generationRepository.findCurrentByListingId(listingId))
                .thenReturn(Optional.of(generation));
        when(operationRepository.findByIdempotencyKey(key)).thenReturn(Optional.empty(), Optional.of(operation(key)));
        when(operationRepository.saveAndFlush(any(SalesChannelOperation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        SalesChannelOperation first = service.request(listingId, SalesChannelOperationType.SYNC,
                Map.of("snapshot", "fixed"));
        SalesChannelOperation repeated = service.request(listingId, SalesChannelOperationType.SYNC,
                Map.of("snapshot", "different caller payload"));

        assertEquals(key, first.getIdempotencyKey());
        assertEquals(key, repeated.getIdempotencyKey());
        assertEquals(Map.of("snapshot", "fixed"), first.getPayload());
        verify(operationRepository, org.mockito.Mockito.times(1)).saveAndFlush(any(SalesChannelOperation.class));
    }

    @ParameterizedTest
    @EnumSource(value = SalesChannelListingManagementMode.class,
            names = {"LINKED_ONLY", "TAKEOVER_PENDING"})
    void directProviderSideEffectRequestIsRejectedOutsideManagedMode(
            SalesChannelListingManagementMode managementMode) {
        UUID listingId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        SalesChannelListing listing = new SalesChannelListing();
        listing.setId(listingId);
        listing.setSalesChannelAccountId(accountId);
        listing.setManagementMode(managementMode);
        SalesChannelAccount account = new SalesChannelAccount();
        account.setProvider(SalesChannelProvider.XIANYU);

        when(listingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        assertThrows(BusinessException.class,
                () -> service.request(listingId, SalesChannelOperationType.OFFLINE, Map.of()));
        verify(operationRepository, never()).findByIdempotencyKey(any());
        verify(operationRepository, never()).saveAndFlush(any());
    }

    @Test
    void managedModeStillAllowsOriginalOperationPath() {
        UUID listingId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        SalesChannelListing listing = new SalesChannelListing();
        listing.setId(listingId);
        listing.setSalesChannelAccountId(accountId);
        listing.setManagementMode(SalesChannelListingManagementMode.MANAGED);
        SalesChannelAccount account = new SalesChannelAccount();
        account.setProvider(SalesChannelProvider.XIANYU);
        SalesChannelListingGeneration generation = generation(listingId, accountId, "item-1", true);
        String key = "XIANYU:" + listingId + ":" + generation.getId() + ":OFFLINE:0";
        when(listingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(generationRepository.findCurrentByListingId(listingId))
                .thenReturn(Optional.of(generation));
        when(operationRepository.findByIdempotencyKey(key)).thenReturn(Optional.empty());
        when(operationRepository.saveAndFlush(any(SalesChannelOperation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        SalesChannelOperation operation = service.request(listingId, SalesChannelOperationType.OFFLINE, Map.of());

        assertEquals(SalesChannelOperationType.OFFLINE, operation.getOperationType());
        verify(operationRepository).saveAndFlush(any(SalesChannelOperation.class));
        assertEquals(generation.getId(), operation.getTargetGenerationId());
    }

    @Test
    void republishFreezesCurrentSourceAndCreatesOnlyACandidateAfterProviderSuccess() {
        UUID listingId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        SalesChannelListing listing = new SalesChannelListing();
        listing.setId(listingId);
        listing.setSalesChannelAccountId(accountId);
        listing.setManagementMode(SalesChannelListingManagementMode.MANAGED);
        listing.setDesiredState(SalesChannelListingDesiredState.ONLINE);
        listing.setDesiredFingerprint("fp-1");
        listing.incrementDesiredRevision();
        SalesChannelAccount account = new SalesChannelAccount();
        account.setProvider(SalesChannelProvider.XIANYU);
        SalesChannelListingGeneration source = generation(listingId, accountId, "item-source", true);
        String key = "XIANYU:" + listingId + ":" + source.getId() + ":REPUBLISH:1";
        SalesChannelOperation operation = new SalesChannelOperation(listingId, source.getId(),
                SalesChannelOperationType.REPUBLISH, 1, key,
                Map.of("desired_fingerprint", "fp-1", "replacement_of_item_id", "item-source"));
        operation.setId(UUID.randomUUID());
        assertTrue(operation.start(LocalDateTime.now()));

        when(listingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        when(operationRepository.findById(operation.getId())).thenReturn(Optional.of(operation));
        when(operationRepository.findByIdForUpdate(operation.getId())).thenReturn(Optional.of(operation));
        when(generationRepository.findBySalesChannelAccountIdAndExternalItemId(accountId, "item-successor"))
                .thenReturn(Optional.empty());
        when(generationRepository.nextGenerationNumber(listingId)).thenReturn(2L);
        when(generationRepository.saveAndFlush(any(SalesChannelListingGeneration.class)))
                .thenAnswer(invocation -> {
                    SalesChannelListingGeneration candidate = invocation.getArgument(0);
                    candidate.setId(UUID.randomUUID());
                    return candidate;
                });

        assertTrue(service.markSucceeded(operation.getId(), Map.of("item_id", "item-successor"), "fp-1"));

        assertEquals(SalesChannelOperationStatus.SUCCEEDED, operation.getStatus());
        assertEquals(SalesChannelListingStatus.REPLACEMENT_PENDING, listing.getStatus());
        assertEquals(0, listing.getAppliedRevision());
        assertEquals(source.getId(), operation.getTargetGenerationId());
        assertEquals("item-successor", operation.getResult().get("item_id"));
        ArgumentCaptor<SalesChannelListingGeneration> candidateCaptor =
                ArgumentCaptor.forClass(SalesChannelListingGeneration.class);
        verify(generationRepository).saveAndFlush(candidateCaptor.capture());
        SalesChannelListingGeneration candidate = candidateCaptor.getValue();
        assertEquals(SalesChannelGenerationState.CANDIDATE, candidate.getGenerationState());
        assertEquals(source.getId(), candidate.getReplacesGenerationId());
        assertEquals("item-successor", candidate.getExternalItemId());
    }

    @Test
    void existingItemOperationFreezesGenerationBeforeCurrentGenerationCanChange() {
        UUID listingId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        SalesChannelListing listing = new SalesChannelListing();
        listing.setId(listingId);
        listing.setSalesChannelAccountId(accountId);
        SalesChannelAccount account = new SalesChannelAccount();
        account.setProvider(SalesChannelProvider.XIANYU);
        SalesChannelListingGeneration first = generation(listingId, accountId, "item-a", true);
        SalesChannelListingGeneration second = generation(listingId, accountId, "item-b", true);
        when(listingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(generationRepository.findCurrentByListingId(listingId))
                .thenReturn(Optional.of(first), Optional.of(second));
        when(operationRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(operationRepository.saveAndFlush(any(SalesChannelOperation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        SalesChannelOperation operation = service.request(listingId, SalesChannelOperationType.OFFLINE, Map.of());

        assertEquals(first.getId(), operation.getTargetGenerationId());
        assertEquals("item-a", first.getExternalItemId());
        assertEquals("item-b", second.getExternalItemId());
    }

    @Test
    void stalePendingProviderOperationCannotStartAfterListingLeavesManagedMode() {
        UUID listingId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        SalesChannelListing listing = new SalesChannelListing();
        listing.setId(listingId);
        listing.setManagementMode(SalesChannelListingManagementMode.TAKEOVER_PENDING);
        SalesChannelOperation operation = new SalesChannelOperation(listingId,
                SalesChannelOperationType.SYNC, 0, "XIANYU:" + listingId + ":SYNC:0", Map.of());
        operation.setId(operationId);

        when(operationRepository.findById(operationId)).thenReturn(Optional.of(operation));
        when(listingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        when(operationRepository.findByIdForUpdate(operationId)).thenReturn(Optional.of(operation));

        assertFalse(service.markRunning(operationId));
        assertEquals(SalesChannelOperationStatus.SUPERSEDED, operation.getStatus());
    }

    @Test
    void failedUnknownAndSucceededOperationsCannotReturnToRunning() {
        UUID listingId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        SalesChannelListing listing = new SalesChannelListing();
        listing.setId(listingId);
        SalesChannelOperation operation = new SalesChannelOperation(listingId, SalesChannelOperationType.PUBLISH, 0,
                "XIANYU:" + listingId + ":PUBLISH:0", Map.of());
        operation.setId(operationId);
        assertTrue(operation.start(java.time.LocalDateTime.now()));
        assertTrue(operation.fail("FAILED", "failed", java.time.LocalDateTime.now()));

        when(operationRepository.findById(operationId)).thenReturn(Optional.of(operation));
        when(listingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        when(operationRepository.findByIdForUpdate(operationId)).thenReturn(Optional.of(operation));

        assertFalse(service.markRunning(operationId));
        assertEquals(SalesChannelOperationStatus.FAILED, operation.getStatus());
        assertFalse(operation.start(java.time.LocalDateTime.now()));
        verify(operationRepository, never()).saveAndFlush(any());
    }

    @Test
    void rescheduleReturnsRunningOperationToPendingWithoutChangingAttemptIdentityOrRevision() {
        UUID listingId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        SalesChannelListing listing = new SalesChannelListing();
        listing.setId(listingId);
        listing.incrementDesiredRevision();
        listing.incrementDesiredRevision();
        listing.advanceAppliedRevision(1);

        String idempotencyKey = "XIANYU:" + listingId + ":SYNC:2";
        SalesChannelOperation operation = new SalesChannelOperation(listingId, SalesChannelOperationType.SYNC, 2,
                idempotencyKey, Map.of("snapshot", "same revision"));
        operation.setId(operationId);
        LocalDateTime startedAt = LocalDateTime.now().minusMinutes(1);
        assertTrue(operation.start(startedAt));
        int attemptCount = operation.getAttemptCount();
        LocalDateTime nextAttemptAt = LocalDateTime.now().plusHours(1);

        when(operationRepository.findById(operationId)).thenReturn(Optional.of(operation));
        when(listingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        when(operationRepository.findByIdForUpdate(operationId)).thenReturn(Optional.of(operation));

        assertTrue(service.reschedule(operationId, "TEMPORARY_PROVIDER_ERROR", "retry later", nextAttemptAt));

        assertEquals(SalesChannelOperationStatus.PENDING, operation.getStatus());
        assertEquals(attemptCount, operation.getAttemptCount());
        assertEquals(nextAttemptAt, operation.getNextAttemptAt());
        assertNull(operation.getStartedAt());
        assertNull(operation.getFinishedAt());
        assertEquals("TEMPORARY_PROVIDER_ERROR", operation.getLastErrorCode());
        assertEquals("retry later", operation.getLastErrorMessage());
        assertEquals(2, operation.getDesiredRevision());
        assertEquals(idempotencyKey, operation.getIdempotencyKey());
        assertEquals(1, listing.getAppliedRevision());

        // A queued retry cannot be claimed before its scheduled time, even through the service method.
        assertFalse(service.markRunning(operationId));
        assertEquals(SalesChannelOperationStatus.PENDING, operation.getStatus());
        assertEquals(attemptCount, operation.getAttemptCount());
    }

    @Test
    void staleSideEffectRunIsConservativelyMovedToUnknown() {
        UUID listingId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        SalesChannelListing listing = new SalesChannelListing();
        listing.setId(listingId);
        listing.incrementDesiredRevision();
        SalesChannelOperation operation = new SalesChannelOperation(listingId, SalesChannelOperationType.PUBLISH, 1,
                "XIANYU:" + listingId + ":PUBLISH:1", Map.of());
        operation.setId(operationId);
        assertTrue(operation.start(LocalDateTime.now().minusMinutes(10)));

        when(operationRepository.findById(operationId)).thenReturn(Optional.of(operation));
        when(listingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        when(operationRepository.findByIdForUpdate(operationId)).thenReturn(Optional.of(operation));

        assertTrue(service.recoverStaleRunningAsUnknown(operationId, LocalDateTime.now().minusMinutes(5)));

        assertEquals(SalesChannelOperationStatus.UNKNOWN, operation.getStatus());
        assertEquals("WORKER_ATTEMPT_STALE", operation.getLastErrorCode());
        assertEquals(com.myshop.constant.SalesChannelListingStatus.UNKNOWN, listing.getStatus());
        assertEquals("WORKER_ATTEMPT_STALE", listing.getLastErrorCode());
    }

    @Test
    void rescheduleRequiresANonNullNextAttemptAt() {
        assertThrows(NullPointerException.class,
                () -> service.reschedule(UUID.randomUUID(), "TEMPORARY", "retry later", null));
    }

    @ParameterizedTest
    @EnumSource(value = SalesChannelOperationStatus.class,
            mode = EnumSource.Mode.EXCLUDE, names = "RUNNING")
    void onlyRunningOperationsCanBeRescheduled(SalesChannelOperationStatus status) {
        SalesChannelOperation operation = operation("XIANYU:listing:SYNC:0");
        LocalDateTime now = LocalDateTime.now();
        switch (status) {
            case PENDING -> { }
            case SUCCEEDED -> {
                assertTrue(operation.start(now));
                assertTrue(operation.succeed(Map.of("ok", true), now));
            }
            case FAILED -> {
                assertTrue(operation.start(now));
                assertTrue(operation.fail("PERMANENT", "failed", now));
            }
            case UNKNOWN -> {
                assertTrue(operation.start(now));
                assertTrue(operation.markUnknown("OUTCOME_UNKNOWN", "unknown", now));
            }
            case SUPERSEDED -> assertTrue(operation.supersede(now));
            case RUNNING -> throw new AssertionError("RUNNING is excluded from this test");
        }

        assertFalse(operation.reschedule("TEMPORARY", "retry later", now.plusMinutes(5)));
        assertEquals(status, operation.getStatus());
    }

    private SalesChannelOperation operation(String idempotencyKey) {
        return new SalesChannelOperation(UUID.randomUUID(), SalesChannelOperationType.PUBLISH, 0,
                idempotencyKey, Map.of());
    }

    private SalesChannelListingGeneration generation(UUID listingId, UUID accountId,
                                                      String itemId, boolean current) {
        SalesChannelListingGeneration generation = new SalesChannelListingGeneration(
                listingId, accountId, current ? 1 : 2, itemId,
                current ? SalesChannelGenerationState.CURRENT : SalesChannelGenerationState.RETIRED, "LINK");
        generation.setId(UUID.randomUUID());
        return generation;
    }
}
