package com.myshop.saleschannel;

import com.myshop.saleschannel.model.SalesChannelDtos.CapabilityData;
import com.myshop.saleschannel.model.SalesChannelDtos.AccountsData;
import com.myshop.saleschannel.model.SalesChannelDtos.AddressMetadataResult;
import com.myshop.saleschannel.model.SalesChannelDtos.CategoryRecommendCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.CategoryRecommendResult;
import com.myshop.saleschannel.model.SalesChannelDtos.IntegrationEnvelope;
import com.myshop.saleschannel.model.SalesChannelDtos.ListingOperationData;
import com.myshop.saleschannel.model.SalesChannelDtos.MediaUploadCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.MediaUploadData;
import com.myshop.saleschannel.model.SalesChannelDtos.PublishCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.ReconcileData;
import com.myshop.saleschannel.model.SalesChannelDtos.ReconcileCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.UpdateCommand;

/** Provider-neutral boundary for operations performed by a sales-channel agent. */
public interface SalesChannelProviderClient {

    IntegrationEnvelope<AccountsData> listAccounts();

    IntegrationEnvelope<CapabilityData> verifyAccount(String accountId);

    IntegrationEnvelope<MediaUploadData> uploadMedia(MediaUploadCommand command);

    IntegrationEnvelope<ListingOperationData> publish(PublishCommand command);

    IntegrationEnvelope<ListingOperationData> update(String itemId, UpdateCommand command);

    IntegrationEnvelope<ListingOperationData> offline(String itemId, UpdateCommand command);

    IntegrationEnvelope<ListingOperationData> delete(String itemId, UpdateCommand command);

    IntegrationEnvelope<ReconcileData> reconcile(ReconcileCommand command);

    AddressMetadataResult searchAddress(String keywords, String city);

    CategoryRecommendResult recommendCategory(CategoryRecommendCommand command);
}
