package com.myshop.service.impl;

import com.myshop.constant.SalesChannelOperationStatus;
import com.myshop.constant.SalesChannelOperationType;
import com.myshop.constant.SalesChannelListingManagementMode;
import com.myshop.constant.SalesChannelListingStatus;
import com.myshop.entity.SalesChannelListing;
import com.myshop.entity.SalesChannelOperation;
import com.myshop.repository.SalesChannelListingGenerationRepository;
import com.myshop.repository.SalesChannelListingRepository;
import com.myshop.repository.SalesChannelOperationRepository;
import com.myshop.repository.SalesChannelRecoveryPermitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SalesChannelOperationServiceImplTest {

    @Mock private SalesChannelListingRepository listingRepository;
    @Mock private SalesChannelListingGenerationRepository generationRepository;
    @Mock private SalesChannelOperationRepository operationRepository;
    @Mock private SalesChannelRecoveryPermitRepository recoveryPermitRepository;
    @InjectMocks private SalesChannelOperationServiceImpl service;

    @BeforeEach
    void defaultToNoScopedRecoveryPermit() {
        lenient().when(recoveryPermitRepository.findByOperationIdForUpdate(any())).thenReturn(Optional.empty());
        lenient().when(recoveryPermitRepository.findByStateInForUpdate(any())).thenReturn(List.of());
    }

    @Test
    void requestCreatesOnlyFrozenOfflineEnsureWithPathSafeStableIdentity() {
        UUID listingId = UUID.randomUUID();
        SalesChannelListing listing = new SalesChannelListing();
        listing.setId(listingId);
        listing.setDesiredFingerprint("a".repeat(64));
        listing.certifyConvergence(UUID.randomUUID());
        listing.activateConvergence(LocalDateTime.now());
        when(listingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        when(generationRepository.findCandidateByListingId(listingId)).thenReturn(Optional.empty());
        when(operationRepository.saveAndFlush(any(SalesChannelOperation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("account_id", "agent-account");
        payload.put("listing_key", listingId.toString().toLowerCase());
        payload.put("revision", 0L);
        payload.put("desired_state", "OFFLINE");
        payload.put("desired_snapshot", null);
        payload.put("desired_fingerprint", "a".repeat(64));

        SalesChannelOperation operation = service.requestEnsureListing(listingId, payload);

        assertEquals(SalesChannelOperationType.ENSURE_LISTING, operation.getOperationType());
        assertEquals(SalesChannelOperationStatus.PENDING, operation.getStatus());
        assertNull(operation.getTargetGenerationId());
        assertNull(operation.getResultGenerationId());
        assertTrue(operation.getIdempotencyKey().matches(
                "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"));
        assertNull(operation.getPayload().get("desired_snapshot"));
        verify(operationRepository).saveAndFlush(any(SalesChannelOperation.class));
    }

    @Test
    void offlineEnsureRejectsNonNullSnapshotBeforePersistence() {
        UUID listingId = UUID.randomUUID();
        SalesChannelListing listing = new SalesChannelListing();
        listing.setId(listingId);
        when(listingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));

        assertThrows(IllegalArgumentException.class, () -> service.requestEnsureListing(listingId, Map.of(
                "listing_key", listingId.toString(),
                "revision", 0L,
                "desired_state", "OFFLINE",
                "desired_snapshot", Map.of("unexpected", true),
                "desired_fingerprint", "a".repeat(64))));

        verify(operationRepository, never()).saveAndFlush(any());
    }

    @Test
    void legacyProviderOperationsAreNeverClaimedByTheEnsureService() {
        UUID listingId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        SalesChannelListing listing = new SalesChannelListing();
        listing.setId(listingId);
        SalesChannelOperation legacy = new SalesChannelOperation(
                listingId, SalesChannelOperationType.PUBLISH, 0, "legacy-request", Map.of());
        legacy.setId(operationId);
        when(operationRepository.findById(operationId)).thenReturn(Optional.of(legacy));
        when(listingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        when(operationRepository.findByIdForUpdate(operationId)).thenReturn(Optional.of(legacy));

        assertFalse(service.markRunning(operationId));
        assertEquals(SalesChannelOperationStatus.PENDING, legacy.getStatus());
        assertEquals(0, legacy.getAttemptCount());
    }

    @Test
    void possiblySentEnsureRetainsRecoveryPrivilegeAfterManagementModeChanges() {
        UUID listingId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        SalesChannelListing listing = new SalesChannelListing();
        listing.setId(listingId);
        listing.setManagementMode(SalesChannelListingManagementMode.LINKED_ONLY);
        listing.setStatus(SalesChannelListingStatus.ONLINE);
        SalesChannelOperation operation = new SalesChannelOperation(
                listingId, SalesChannelOperationType.ENSURE_LISTING, 0, "same-request", Map.of());
        operation.setId(operationId);
        operation.start(LocalDateTime.now().minusMinutes(1));
        operation.reschedule("AGENT_APPLYING", "replay", LocalDateTime.now().minusSeconds(1));
        when(operationRepository.findById(operationId)).thenReturn(Optional.of(operation));
        when(listingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        when(operationRepository.findByIdForUpdate(operationId)).thenReturn(Optional.of(operation));
        when(generationRepository.findCandidateByListingId(listingId)).thenReturn(Optional.empty());
        when(operationRepository.findBySalesChannelListingIdOrderByCreatedAtAsc(listingId))
                .thenReturn(java.util.List.of(operation));
        when(operationRepository.existsBySalesChannelListingIdAndStatus(listingId, SalesChannelOperationStatus.RUNNING))
                .thenReturn(false);

        assertTrue(service.markRunning(operationId));
        assertEquals(SalesChannelOperationStatus.RUNNING, operation.getStatus());
        assertEquals(2, operation.getAttemptCount());
        assertEquals(SalesChannelListingStatus.ONLINE, listing.getStatus());
    }

    @Test
    void neverSentEnsureIsSupersededWhenManagementModeNoLongerAllowsWrites() {
        UUID listingId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        SalesChannelListing listing = new SalesChannelListing();
        listing.setId(listingId);
        listing.setManagementMode(SalesChannelListingManagementMode.LINKED_ONLY);
        listing.certifyConvergence(UUID.randomUUID());
        listing.activateConvergence(LocalDateTime.now());
        SalesChannelOperation operation = new SalesChannelOperation(
                listingId, SalesChannelOperationType.ENSURE_LISTING, 0, "never-sent", Map.of());
        operation.setId(operationId);
        when(operationRepository.findById(operationId)).thenReturn(Optional.of(operation));
        when(listingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        when(operationRepository.findByIdForUpdate(operationId)).thenReturn(Optional.of(operation));

        assertFalse(service.markRunning(operationId));
        assertEquals(SalesChannelOperationStatus.SUPERSEDED, operation.getStatus());
        assertEquals(0, operation.getAttemptCount());
    }

    @Test
    void neverSentBootstrapEnsureWaitsIntactUntilListingIsActive() {
        UUID listingId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        SalesChannelListing listing = new SalesChannelListing();
        listing.setId(listingId);
        SalesChannelOperation operation = new SalesChannelOperation(
                listingId, SalesChannelOperationType.ENSURE_LISTING, 0, "bootstrap-request", Map.of());
        operation.setId(operationId);
        when(operationRepository.findById(operationId)).thenReturn(Optional.of(operation));
        when(listingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        when(operationRepository.findByIdForUpdate(operationId)).thenReturn(Optional.of(operation));

        assertFalse(service.markRunning(operationId));
        assertEquals(SalesChannelOperationStatus.PENDING, operation.getStatus());
        assertEquals(0, operation.getAttemptCount());
        assertNull(operation.getStartedAt());
    }

    @Test
    void oldEnsureTerminalSuccessCannotClearCurrentInvalidIntent() {
        UUID listingId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        SalesChannelListing listing = new SalesChannelListing();
        listing.setId(listingId);
        listing.setLastErrorCode("DESIRED_LISTING_INVALID");
        listing.setLastErrorMessage("invalid current source");
        listing.setStatus(SalesChannelListingStatus.ERROR);
        SalesChannelOperation operation = new SalesChannelOperation(
                listingId, SalesChannelOperationType.ENSURE_LISTING, 0, "old-request",
                Map.of("desired_state", "OFFLINE", "desired_fingerprint", "a".repeat(64)));
        operation.setId(operationId);
        operation.start(LocalDateTime.now().minusMinutes(1));
        when(operationRepository.findById(operationId)).thenReturn(Optional.of(operation));
        when(listingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        when(operationRepository.findByIdForUpdate(operationId)).thenReturn(Optional.of(operation));

        assertTrue(service.markSucceeded(operationId, Map.of("state", "SUCCEEDED"), "a".repeat(64)));
        assertEquals(SalesChannelOperationStatus.SUCCEEDED, operation.getStatus());
        assertEquals(SalesChannelListingStatus.ERROR, listing.getStatus());
        assertEquals("DESIRED_LISTING_INVALID", listing.getLastErrorCode());
        assertEquals(0, listing.getAppliedRevision());
    }
}
