package com.myshop.service.impl;

import com.myshop.constant.SalesChannelOperationStatus;
import com.myshop.constant.SalesChannelOperationType;
import com.myshop.entity.SalesChannelAccount;
import com.myshop.entity.SalesChannelListing;
import com.myshop.entity.SalesChannelOperation;
import com.myshop.constant.SalesChannelProvider;
import com.myshop.repository.SalesChannelAccountRepository;
import com.myshop.repository.SalesChannelListingRepository;
import com.myshop.repository.SalesChannelOperationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SalesChannelOperationServiceImplTest {

    @Mock private SalesChannelAccountRepository accountRepository;
    @Mock private SalesChannelListingRepository listingRepository;
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
        String key = "XIANYU:" + listingId + ":SYNC:1";

        when(listingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
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

    private SalesChannelOperation operation(String idempotencyKey) {
        return new SalesChannelOperation(UUID.randomUUID(), SalesChannelOperationType.PUBLISH, 0,
                idempotencyKey, Map.of());
    }
}
