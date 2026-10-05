package com.myshop.saleschannel;

import com.myshop.constant.SalesChannelConvergenceCutoverState;
import com.myshop.entity.SalesChannelListing;
import com.myshop.repository.SalesChannelListingRepository;
import com.myshop.service.SalesChannelDesiredStatePlanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SalesChannelDriftScannerTest {

    @Mock private SalesChannelListingRepository listingRepository;
    @Mock private SalesChannelDesiredStatePlanner planner;

    @Test
    void scansOnlyEnabledActiveCutoverListings() {
        UUID listingId = UUID.randomUUID();
        SalesChannelListing active = new SalesChannelListing();
        active.setId(listingId);
        when(listingRepository.findByEnabledTrueAndConvergenceCutoverStateOrderByUpdatedAtAsc(
                SalesChannelConvergenceCutoverState.ACTIVE)).thenReturn(List.of(active));

        new SalesChannelDriftScanner(listingRepository, planner).scanEnabledListings();

        verify(listingRepository).findByEnabledTrueAndConvergenceCutoverStateOrderByUpdatedAtAsc(
                SalesChannelConvergenceCutoverState.ACTIVE);
        verify(planner).refresh(listingId);
    }
}
