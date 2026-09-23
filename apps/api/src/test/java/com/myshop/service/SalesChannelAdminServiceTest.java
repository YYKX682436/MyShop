package com.myshop.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.constant.SalesChannelAccountStatus;
import com.myshop.constant.SalesChannelProvider;
import com.myshop.constant.StockMode;
import com.myshop.entity.Product;
import com.myshop.entity.ProductSpec;
import com.myshop.entity.SalesChannelAccount;
import com.myshop.entity.SalesChannelListing;
import com.myshop.exception.SalesChannelSpecMappingException;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.ProductSpecRepository;
import com.myshop.repository.SalesChannelAccountRepository;
import com.myshop.repository.SalesChannelDeliveryOperationRepository;
import com.myshop.repository.SalesChannelListingRepository;
import com.myshop.repository.SalesChannelListingSpecRepository;
import com.myshop.repository.SalesChannelOrderCursorRepository;
import com.myshop.repository.SalesChannelOrderRepository;
import com.myshop.saleschannel.SalesChannelProviderClient;
import com.myshop.saleschannel.model.SalesChannelDtos.AccountCapabilities;
import com.myshop.saleschannel.model.SalesChannelDtos.AccountsData;
import com.myshop.saleschannel.model.SalesChannelDtos.CapabilityData;
import com.myshop.saleschannel.model.SalesChannelDtos.IntegrationEnvelope;
import com.myshop.saleschannel.model.SalesChannelDtos.Outcome;
import com.myshop.saleschannel.model.SalesChannelDtos.QrLoginStatusData;
import com.myshop.service.impl.SalesChannelAccountServiceImpl;
import com.myshop.service.impl.SalesChannelListingServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SalesChannelAdminServiceTest {

    @Mock private SalesChannelProviderClient providerClient;
    @Mock private SalesChannelAccountServiceImpl accountService;
    @Mock private SalesChannelListingServiceImpl listingService;
    @Mock private SalesChannelDesiredStatePlanner planner;
    @Mock private SalesChannelAccountRepository accountRepository;
    @Mock private SalesChannelListingRepository listingRepository;
    @Mock private ObjectMapper objectMapper;
    @Mock private ProductRepository productRepository;
    @Mock private ProductSpecRepository productSpecRepository;
    @Mock private SalesChannelListingSpecRepository listingSpecRepository;
    @Mock private SalesChannelSpecMappingValidator specMappingValidator;
    @Mock private SalesChannelSpecMappingSyncService specMappingSyncService;
    @Mock private SalesChannelOrderCursorRepository cursorRepository;
    @Mock private SalesChannelOrderRepository channelOrderRepository;
    @Mock private SalesChannelDeliveryOperationRepository deliveryOperationRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private OrderItemRepository orderItemRepository;

    @InjectMocks private SalesChannelAdminService service;

    @Test
    void autoFulfillmentPreflightRejectsSpecDisabledProductWithoutCostPrice() {
        UUID listingId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        SalesChannelListing listing = new SalesChannelListing();
        listing.setId(listingId);
        listing.setSalesChannelAccountId(accountId);
        listing.setProductId(productId);
        listing.bindExternalItemId("external-item");

        SalesChannelAccount account = new SalesChannelAccount();
        account.setId(accountId);
        account.setProvider(SalesChannelProvider.XIANYU);
        account.setStatus(SalesChannelAccountStatus.CONNECTED);
        account.setCapabilities(Map.of("account_type", "FISH_SHOP"));

        Product product = new Product();
        product.setId(productId);
        product.setSpecEnabled(false);
        product.setDeliveryType("AUTO");
        product.setStockMode(StockMode.CARD_KEY);
        product.setRetailPrice(new BigDecimal("20.00"));
        product.setCostPrice(null);

        when(listingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));

        assertThrows(IllegalStateException.class,
                () -> service.setAutoFulfillmentEnabled(listingId, true));
        verify(listingRepository, never()).save(any(SalesChannelListing.class));
    }

    @Test
    void autoFulfillmentPreflightRejectsSpecEnabledProductWithoutCompleteMapping() {
        UUID listingId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID specId = UUID.randomUUID();

        SalesChannelListing listing = new SalesChannelListing();
        listing.setId(listingId);
        listing.setSalesChannelAccountId(accountId);
        listing.setProductId(productId);
        listing.bindExternalItemId("external-item");

        SalesChannelAccount account = new SalesChannelAccount();
        account.setId(accountId);
        account.setProvider(SalesChannelProvider.XIANYU);
        account.setStatus(SalesChannelAccountStatus.CONNECTED);
        account.setCapabilities(Map.of("account_type", "FISH_SHOP"));

        Product product = new Product();
        product.setId(productId);
        product.setSpecEnabled(true);
        product.setDeliveryType("AUTO");
        product.setStockMode(StockMode.CARD_KEY);

        ProductSpec spec = new ProductSpec();
        spec.setId(specId);
        spec.setProductId(productId);
        spec.setName("月卡");

        when(listingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(productSpecRepository.findByProductIdAndIsDeletedOrderBySortOrderAsc(productId, 0))
                .thenReturn(java.util.List.of(spec));
        when(listingSpecRepository.findBySalesChannelListingIdForUpdate(listingId))
                .thenReturn(java.util.List.of());
        doThrow(new SalesChannelSpecMappingException("SPEC_MAPPING_INCOMPLETE", "mapping required"))
                .when(specMappingValidator).assertCompleteForAutoFulfillment(any(), any());

        assertThrows(SalesChannelSpecMappingException.class,
                () -> service.setAutoFulfillmentEnabled(listingId, true));
        verify(specMappingValidator).assertCompleteForAutoFulfillment(any(), any());
        verify(listingRepository, never()).save(any(SalesChannelListing.class));
    }

    @Test
    void successfulQrStatusSynchronizesAndVerifiesTheSameAccountOnRepeatedSuccess() {
        String providerAccountId = "2215747465381";
        UUID localAccountId = UUID.randomUUID();
        SalesChannelAccount account = new SalesChannelAccount();
        account.setId(localAccountId);
        account.setProvider(SalesChannelProvider.XIANYU);
        account.setProviderAccountId(providerAccountId);
        account.setDisplayName("现有闲鱼账号");
        account.setStatus(SalesChannelAccountStatus.CONNECTED);
        account.setCapabilities(Map.of("account_type", "FISH_SHOP"));

        var qrStatus = new QrLoginStatusData("SUCCESS", providerAccountId, false, null, null);
        when(providerClient.getQrLoginStatus(any())).thenReturn(new IntegrationEnvelope<>(
                "status-request", Outcome.SUCCEEDED, qrStatus, null));
        var remoteAccount = new com.myshop.saleschannel.model.SalesChannelDtos.SalesChannelAccount(
                providerAccountId, "现有闲鱼账号", "active", true,
                "2026-09-23T12:30:00+08:00", null, null);
        when(providerClient.listAccounts()).thenReturn(new IntegrationEnvelope<>(
                null, Outcome.SUCCEEDED, new AccountsData(List.of(remoteAccount)), null));
        when(accountRepository.findByProviderAndProviderAccountId(SalesChannelProvider.XIANYU, providerAccountId))
                .thenReturn(Optional.of(account));
        when(accountRepository.findById(localAccountId)).thenReturn(Optional.of(account));
        var capabilities = new AccountCapabilities("FISH_SHOP", true, true, true, true, true);
        when(providerClient.verifyAccount(providerAccountId)).thenReturn(new IntegrationEnvelope<>(
                "verify-request", Outcome.SUCCEEDED, new CapabilityData(capabilities), null));
        when(accountService.upsert(any(), any(), any(), any(), any(), any(), any())).thenReturn(account);

        var first = service.getQrLoginStatus("status-request", UUID.randomUUID().toString());
        var repeated = service.getQrLoginStatus("status-request-2", UUID.randomUUID().toString());

        assertEquals("SUCCESS", first.status());
        assertEquals(providerAccountId, first.accountId());
        assertEquals(first.accountId(), repeated.accountId());
        verify(accountService, times(4)).upsert(any(), any(), any(), any(), any(), any(), any());
        verify(providerClient, times(2)).verifyAccount(providerAccountId);
        verify(accountRepository, times(4)).findByProviderAndProviderAccountId(
                SalesChannelProvider.XIANYU, providerAccountId);
    }

    @Test
    void accountSyncPreservesPreviouslyVerifiedCapabilitiesWhenAgentHasNoCapabilityCache() {
        String providerAccountId = "2215747465381";
        Map<String, Object> verifiedCapabilities = Map.of(
                "account_type", "FISH_SHOP", "publish", true, "edit", true);
        SalesChannelAccount existing = new SalesChannelAccount();
        existing.setId(UUID.randomUUID());
        existing.setProvider(SalesChannelProvider.XIANYU);
        existing.setProviderAccountId(providerAccountId);
        existing.setDisplayName("现有闲鱼账号");
        existing.setStatus(SalesChannelAccountStatus.CONNECTED);
        existing.setCapabilities(verifiedCapabilities);

        var remoteAccount = new com.myshop.saleschannel.model.SalesChannelDtos.SalesChannelAccount(
                providerAccountId, "现有闲鱼账号", "active", true, null, null, null);
        when(providerClient.listAccounts()).thenReturn(new IntegrationEnvelope<>(
                null, Outcome.SUCCEEDED, new AccountsData(List.of(remoteAccount)), null));
        when(accountRepository.findByProviderAndProviderAccountId(SalesChannelProvider.XIANYU, providerAccountId))
                .thenReturn(Optional.of(existing));

        service.syncXianyuAccounts();

        verify(accountService).upsert(
                eq(SalesChannelProvider.XIANYU), eq(providerAccountId), eq("现有闲鱼账号"),
                eq(SalesChannelAccountStatus.CONNECTED), eq(verifiedCapabilities), isNull(), isNull());
    }
}
