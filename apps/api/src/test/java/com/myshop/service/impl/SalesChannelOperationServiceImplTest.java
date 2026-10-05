package com.myshop.service.impl;

import com.myshop.constant.SalesChannelOperationStatus;
import com.myshop.constant.SalesChannelOperationType;
import com.myshop.entity.SalesChannelListing;
import com.myshop.entity.SalesChannelOperation;
import com.myshop.repository.SalesChannelListingGenerationRepository;
import com.myshop.repository.SalesChannelListingRepository;
import com.myshop.repository.SalesChannelOperationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SalesChannelOperationServiceImplTest {

    @Mock private SalesChannelListingRepository listingRepository;
    @Mock private SalesChannelListingGenerationRepository generationRepository;
    @Mock private SalesChannelOperationRepository operationRepository;
    @InjectMocks private SalesChannelOperationServiceImpl service;

    @Test
    void requestCreatesOnlyFrozenOfflineEnsureWithPathSafeStableIdentity() {
        UUID listingId = UUID.randomUUID();
        SalesChannelListing listing = new SalesChannelListing();
        listing.setId(listingId);
        listing.setDesiredFingerprint("a".repeat(64));
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
}
