package com.myshop.service;

import com.myshop.exception.SalesChannelSpecMappingException;
import com.myshop.saleschannel.SalesChannelProviderClient;
import com.myshop.saleschannel.SalesChannelProviderClientException;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.SpecMappingSyncResult;
import com.myshop.saleschannel.model.SalesChannelDtos.IntegrationEnvelope;
import com.myshop.saleschannel.model.SalesChannelDtos.ListingSpecMappingsData;
import com.myshop.saleschannel.model.SalesChannelDtos.Outcome;
import com.myshop.saleschannel.model.SalesChannelDtos.SpecMappingsCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Orchestrates read-only provider inspection and atomic local identity backfill. */
@Service
@RequiredArgsConstructor
public class SalesChannelSpecMappingSyncService {

    private final SalesChannelProviderClient providerClient;
    private final SalesChannelSpecMappingPersistence persistence;
    private final SalesChannelSpecMappingValidator validator;

    public SpecMappingSyncResult sync(UUID listingId) {
        SalesChannelSpecMappingPersistence.ListingSpecSyncSnapshot snapshot = persistence.snapshot(listingId);
        IntegrationEnvelope<ListingSpecMappingsData> response;
        try {
            response = providerClient.getListingSpecMappings(new SpecMappingsCommand(
                    UUID.randomUUID().toString(), snapshot.providerAccountId(), snapshot.externalItemId()));
        } catch (SalesChannelProviderClientException exception) {
            boolean retryable = exception.getDisposition()
                    == SalesChannelProviderClientException.Disposition.SAFE_RETRYABLE;
            throw new SalesChannelSpecMappingException(exception.getErrorCode(),
                    "The Xianyu item specifications could not be read", retryable);
        }
        if (response == null || response.outcome() != Outcome.SUCCEEDED || response.data() == null) {
            String code = response == null || response.error() == null
                    ? "ITEM_SPEC_READ_FAILED" : response.error().code();
            boolean retryable = response != null && response.error() != null
                    && Boolean.TRUE.equals(response.error().retryable());
            throw new SalesChannelSpecMappingException(code,
                    "The Xianyu item specifications could not be read", retryable);
        }
        if (!snapshot.externalItemId().equals(response.data().itemId())) {
            throw new SalesChannelSpecMappingException("ITEM_ID_MISMATCH",
                    "The Agent returned a different item identity");
        }
        var resolved = validator.resolve(snapshot.productSpecs(), snapshot.existingMappings(), response.data());
        return persistence.apply(snapshot, resolved);
    }
}
