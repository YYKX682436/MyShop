package com.myshop.saleschannel;

import com.myshop.saleschannel.model.SalesChannelDtos.CapabilityData;
import com.myshop.saleschannel.model.SalesChannelDtos.AccountsData;
import com.myshop.saleschannel.model.SalesChannelDtos.AddressMetadataResult;
import com.myshop.saleschannel.model.SalesChannelDtos.CategoryRecommendCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.CategoryRecommendResult;
import com.myshop.saleschannel.model.SalesChannelDtos.IntegrationEnvelope;
import com.myshop.saleschannel.model.SalesChannelDtos.ListingOperationData;
import com.myshop.saleschannel.model.SalesChannelDtos.ListingSpecMappingsData;
import com.myshop.saleschannel.model.SalesChannelDtos.ItemsQueryCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.ItemsQueryData;
import com.myshop.saleschannel.model.SalesChannelDtos.ItemsSyncCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.ItemsSyncData;
import com.myshop.saleschannel.model.SalesChannelDtos.MediaUploadCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.MediaUploadData;
import com.myshop.saleschannel.model.SalesChannelDtos.PublishCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.RepublishCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.QrLoginStartCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.QrLoginReauthenticationStartCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.QrLoginVerificationCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.QrLoginStartData;
import com.myshop.saleschannel.model.SalesChannelDtos.QrLoginStatusCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.QrLoginStatusData;
import com.myshop.saleschannel.model.SalesChannelDtos.SpecMappingsCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.OwnershipClaimCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.OwnershipClaimData;
import com.myshop.saleschannel.model.SalesChannelDtos.ReconcileData;
import com.myshop.saleschannel.model.SalesChannelDtos.ReconcileCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.UpdateCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.OrderChangesCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.OrderChangesData;
import com.myshop.saleschannel.model.SalesChannelDtos.WatermarkCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.WatermarkData;
import com.myshop.saleschannel.model.SalesChannelDtos.DeliverySendCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.DeliveryReconcileCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.DeliveryData;
import com.myshop.saleschannel.model.SalesChannelDtos.DeliveryReconcileData;
import com.myshop.saleschannel.model.SalesChannelDtos.ConfirmDeliveryCommand;

/** Provider-neutral boundary for operations performed by a sales-channel agent. */
public interface SalesChannelProviderClient {

    IntegrationEnvelope<AccountsData> listAccounts();

    IntegrationEnvelope<ItemsQueryData> queryItems(ItemsQueryCommand command);

    IntegrationEnvelope<ItemsSyncData> syncItems(ItemsSyncCommand command);

    IntegrationEnvelope<CapabilityData> verifyAccount(String accountId);

    IntegrationEnvelope<QrLoginStartData> startQrLogin(QrLoginStartCommand command);

    IntegrationEnvelope<QrLoginStartData> startQrReauthentication(QrLoginReauthenticationStartCommand command);

    IntegrationEnvelope<QrLoginStatusData> getQrLoginStatus(QrLoginStatusCommand command);

    IntegrationEnvelope<QrLoginStatusData> completeQrLoginVerification(QrLoginVerificationCommand command);

    IntegrationEnvelope<MediaUploadData> uploadMedia(MediaUploadCommand command);

    IntegrationEnvelope<ListingOperationData> publish(PublishCommand command);

    IntegrationEnvelope<ListingOperationData> republish(RepublishCommand command);

    IntegrationEnvelope<ListingOperationData> update(String itemId, UpdateCommand command);

    IntegrationEnvelope<ListingOperationData> offline(String itemId, UpdateCommand command);

    IntegrationEnvelope<ListingOperationData> delete(String itemId, UpdateCommand command);

    IntegrationEnvelope<ReconcileData> reconcile(ReconcileCommand command);

    IntegrationEnvelope<ListingSpecMappingsData> getListingSpecMappings(SpecMappingsCommand command);

    IntegrationEnvelope<ListingSpecMappingsData> inspectItemSpecMappings(SpecMappingsCommand command);

    IntegrationEnvelope<OwnershipClaimData> claimOwnership(OwnershipClaimCommand command);

    AddressMetadataResult searchAddress(String keywords, String city);

    CategoryRecommendResult recommendCategory(CategoryRecommendCommand command);

    IntegrationEnvelope<OrderChangesData> getOrderChanges(OrderChangesCommand command);

    IntegrationEnvelope<WatermarkData> getOrderWatermark(WatermarkCommand command);

    IntegrationEnvelope<DeliveryData> sendDeliveryUnit(DeliverySendCommand command);

    IntegrationEnvelope<DeliveryReconcileData> reconcileDeliveryUnit(DeliveryReconcileCommand command);

    IntegrationEnvelope<DeliveryData> confirmDelivery(ConfirmDeliveryCommand command);

    IntegrationEnvelope<DeliveryReconcileData> reconcileConfirmDelivery(DeliveryReconcileCommand command);
}
