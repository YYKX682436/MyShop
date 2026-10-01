package com.myshop.service.impl;

import com.myshop.constant.SalesChannelListingDesiredState;
import com.myshop.constant.SalesChannelOperationStatus;
import com.myshop.constant.SalesChannelOperationType;
import com.myshop.entity.SalesChannelListing;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.ProductSpecRepository;
import com.myshop.repository.SalesChannelAccountRepository;
import com.myshop.repository.SalesChannelListingRepository;
import com.myshop.repository.SalesChannelListingGenerationRepository;
import com.myshop.repository.SalesChannelListingGenerationSpecRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SalesChannelListingServiceImplTest {

    @Mock private SalesChannelAccountRepository accountRepository;
    @Mock private ProductRepository productRepository;
    @Mock private ProductSpecRepository productSpecRepository;
    @Mock private SalesChannelListingRepository listingRepository;
    @Mock private SalesChannelListingGenerationSpecRepository generationSpecRepository;
    @Mock private SalesChannelOperationRepository operationRepository;
    @Mock private SalesChannelListingGenerationRepository generationRepository;
    @InjectMocks private SalesChannelListingServiceImpl service;

    @Test
    void configAndStateOnlyAdvanceRevisionOnEffectiveChanges() {
        UUID listingId = UUID.randomUUID();
        SalesChannelListing listing = new SalesChannelListing();
        listing.setId(listingId);
        when(listingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        when(listingRepository.saveAndFlush(listing)).thenReturn(listing);

        assertEquals(1, service.updateProviderConfig(listingId, Map.of("title", "first")).getDesiredRevision());
        assertEquals(1, service.updateProviderConfig(listingId, Map.of("title", "first")).getDesiredRevision());
        assertEquals(2, service.updateProviderConfig(listingId, Map.of("title", "second")).getDesiredRevision());
        assertEquals(3, service.setDesiredState(listingId, SalesChannelListingDesiredState.ONLINE).getDesiredRevision());
        assertEquals(3, service.setDesiredState(listingId, SalesChannelListingDesiredState.ONLINE).getDesiredRevision());

        verify(operationRepository, org.mockito.Mockito.times(3)).supersedePendingBeforeRevision(
                eq(listingId), any(Long.class), eq(SalesChannelOperationStatus.PENDING),
                eq(SalesChannelOperationStatus.SUPERSEDED), any());
        verify(operationRepository, never()).supersedePendingBeforeRevision(
                eq(listingId), eq(0L), any(), any(), any());
        verify(listingRepository, org.mockito.Mockito.times(3)).saveAndFlush(listing);
    }
}
