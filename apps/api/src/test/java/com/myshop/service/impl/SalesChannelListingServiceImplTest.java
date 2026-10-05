package com.myshop.service.impl;

import com.myshop.entity.Product;
import com.myshop.entity.SalesChannelAccount;
import com.myshop.entity.SalesChannelListing;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.SalesChannelAccountRepository;
import com.myshop.repository.SalesChannelListingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SalesChannelListingServiceImplTest {

    @Mock private SalesChannelAccountRepository accountRepository;
    @Mock private ProductRepository productRepository;
    @Mock private SalesChannelListingRepository listingRepository;
    @InjectMocks private SalesChannelListingServiceImpl service;

    @Test
    void ensuresOneDraftIdentityWithoutPlanningProviderExecution() {
        UUID accountId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        Product product = new Product();
        product.setId(productId);
        product.setIsDeleted(0);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(new SalesChannelAccount()));
        when(productRepository.findByIdForUpdate(productId)).thenReturn(Optional.of(product));
        when(listingRepository.findBySalesChannelAccountIdAndProductId(accountId, productId))
                .thenReturn(Optional.empty());
        when(listingRepository.saveAndFlush(any(SalesChannelListing.class))).thenAnswer(invocation -> {
            SalesChannelListing listing = invocation.getArgument(0);
            listing.setId(UUID.randomUUID());
            return listing;
        });

        SalesChannelListing listing = service.ensureDraft(accountId, productId);

        assertEquals(accountId, listing.getSalesChannelAccountId());
        assertEquals(productId, listing.getProductId());
        verify(listingRepository).saveAndFlush(any(SalesChannelListing.class));
    }
}
