package com.myshop.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.constant.SalesChannelAccountStatus;
import com.myshop.constant.SalesChannelProvider;
import com.myshop.constant.StockMode;
import com.myshop.entity.Product;
import com.myshop.entity.ProductSpec;
import com.myshop.entity.SalesChannelAccount;
import com.myshop.entity.SalesChannelListing;
import com.myshop.entity.SalesChannelListingGeneration;
import com.myshop.exception.SalesChannelSpecMappingException;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.ProductSpecRepository;
import com.myshop.repository.SalesChannelAccountRepository;
import com.myshop.repository.SalesChannelDeliveryOperationRepository;
import com.myshop.repository.SalesChannelListingRepository;
import com.myshop.repository.SalesChannelListingGenerationRepository;
import com.myshop.repository.SalesChannelListingGenerationSpecRepository;
import com.myshop.repository.SalesChannelOperationRepository;
import com.myshop.repository.SalesChannelOrderCursorRepository;
import com.myshop.repository.SalesChannelOrderRepository;
import com.myshop.saleschannel.SalesChannelProviderClient;
import com.myshop.saleschannel.SalesChannelProviderClientException;
import com.myshop.saleschannel.model.SalesChannelDtos.AccountCapabilities;
import com.myshop.saleschannel.model.SalesChannelDtos.AccountsData;
import com.myshop.saleschannel.model.SalesChannelDtos.CapabilityData;
import com.myshop.saleschannel.model.SalesChannelDtos.IntegrationEnvelope;
import com.myshop.saleschannel.model.SalesChannelDtos.Outcome;
import com.myshop.saleschannel.model.SalesChannelDtos.CatalogItem;
import com.myshop.saleschannel.model.SalesChannelDtos.ItemsQueryCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.ItemsQueryData;
import com.myshop.saleschannel.model.SalesChannelDtos.ItemsSyncCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.ItemsSyncData;
import com.myshop.saleschannel.model.SalesChannelDtos.QrLoginStatusData;
import com.myshop.service.impl.SalesChannelAccountServiceImpl;
import com.myshop.service.impl.SalesChannelListingServiceImpl;
import com.myshop.service.XianyuItemBindingService;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SalesChannelAdminServiceTest {

    @Mock private SalesChannelProviderClient providerClient;
    @Mock private SalesChannelAccountServiceImpl accountService;
    @Mock private SalesChannelListingServiceImpl listingService;
    @Mock private SalesChannelDesiredStatePlanner planner;
    @Mock private SalesChannelAccountRepository accountRepository;
    @Mock private SalesChannelListingRepository listingRepository;
    @Mock private SalesChannelListingGenerationRepository generationRepository;
    @Mock private SalesChannelOperationRepository operationRepository;
    @Mock private ObjectMapper objectMapper;
    @Mock private ProductRepository productRepository;
    @Mock private ProductSpecRepository productSpecRepository;
    @Mock private SalesChannelListingGenerationSpecRepository generationSpecRepository;
    @Mock private SalesChannelSpecMappingValidator specMappingValidator;
    @Mock private SalesChannelSpecMappingSyncService specMappingSyncService;
    @Mock private SalesChannelOrderCursorRepository cursorRepository;
    @Mock private SalesChannelOrderRepository channelOrderRepository;
    @Mock private SalesChannelDeliveryOperationRepository deliveryOperationRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private XianyuItemBindingService itemBindingService;

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
        SalesChannelListingGeneration generation = generation(listing, "external-item");
        when(generationRepository.findBySalesChannelListingIdAndCurrentTrue(listingId))
                .thenReturn(Optional.of(generation));

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
        SalesChannelListingGeneration generation = generation(listing, "external-item");
        when(generationRepository.findBySalesChannelListingIdAndCurrentTrue(listingId))
                .thenReturn(Optional.of(generation));

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
        when(generationSpecRepository.findBySalesChannelListingGenerationIdForUpdate(generation.getId()))
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

    @Test
    void catalogQueryAnnotatesOnlyExactExistingListingsAndBatchLoadsProducts() {
        UUID accountId = UUID.randomUUID();
        UUID linkedProductId = UUID.randomUUID();
        UUID conflictProductId1 = UUID.randomUUID();
        UUID conflictProductId2 = UUID.randomUUID();
        SalesChannelAccount account = new SalesChannelAccount();
        account.setId(accountId);
        account.setProvider(SalesChannelProvider.XIANYU);
        account.setProviderAccountId("2215747465381");
        account.setEnabled(true);

        CatalogItem linked = catalogItem("linked-item", "安卓定制V");
        CatalogItem unlinked = catalogItem("unlinked-item", "安卓定制V similar title");
        CatalogItem conflict = catalogItem("conflict-item", "Conflict title");
        ItemsQueryData remote = new ItemsQueryData(List.of(linked, unlinked, conflict), 1, 20, 3L, 1);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(providerClient.queryItems(any())).thenReturn(new IntegrationEnvelope<>(
                null, Outcome.SUCCEEDED, remote, null));

        SalesChannelListing linkedListing = listing(accountId, linkedProductId, "linked-item");
        SalesChannelListing conflictListing1 = listing(accountId, conflictProductId1, "conflict-item");
        SalesChannelListing conflictListing2 = listing(accountId, conflictProductId2, "conflict-item");
        SalesChannelListingGeneration linkedGeneration = generation(linkedListing, "linked-item");
        SalesChannelListingGeneration conflictGeneration1 = generation(conflictListing1, "conflict-item");
        SalesChannelListingGeneration conflictGeneration2 = generation(conflictListing2, "conflict-item");
        when(generationRepository.findAllBySalesChannelAccountIdAndExternalItemIdIn(
                eq(accountId), any())).thenReturn(List.of(linkedGeneration, conflictGeneration1, conflictGeneration2));
        when(listingRepository.findAllById(any())).thenReturn(
                List.of(linkedListing, conflictListing1, conflictListing2));

        Product linkedProduct = product(linkedProductId, "安卓定制V");
        Product conflictProduct1 = product(conflictProductId1, "Conflict A");
        Product conflictProduct2 = product(conflictProductId2, "Conflict B");
        when(productRepository.findAllById(any())).thenReturn(
                List.of(linkedProduct, conflictProduct1, conflictProduct2));

        var result = service.queryXianyuItems(accountId, 1, 20, "");

        assertEquals(3, result.items().size());
        var linkedResult = result.items().get(0);
        assertEquals("LINKED", linkedResult.bindingState());
        assertEquals(linkedListing.getId().toString(), linkedResult.listingId());
        assertEquals(linkedProductId.toString(), linkedResult.productId());
        assertEquals("安卓定制V", linkedResult.productTitle());

        var unlinkedResult = result.items().get(1);
        assertEquals("UNLINKED", unlinkedResult.bindingState());
        assertNull(unlinkedResult.productId());
        assertTrue(unlinkedResult.bindings().isEmpty());

        var conflictResult = result.items().get(2);
        assertEquals("CONFLICT", conflictResult.bindingState());
        assertNull(conflictResult.listingId());
        assertEquals(2, conflictResult.bindings().size());
        assertEquals(List.of(conflictProductId1.toString(), conflictProductId2.toString()),
                conflictResult.bindings().stream().map(binding -> binding.productId()).toList());

        verify(providerClient).queryItems(new ItemsQueryCommand("2215747465381", 1, 20, ""));
        verify(generationRepository).findAllBySalesChannelAccountIdAndExternalItemIdIn(eq(accountId), any());
        verify(productRepository).findAllById(any());
        verify(listingRepository, never()).save(any());
        verifyNoInteractions(listingService);
    }

    @Test
    void queryRejectsDisabledOrNonXianyuAccountBeforeProviderAccess() {
        UUID accountId = UUID.randomUUID();
        SalesChannelAccount account = new SalesChannelAccount();
        account.setId(accountId);
        account.setProvider(SalesChannelProvider.XIANYU);
        account.setEnabled(false);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        assertThrows(RuntimeException.class, () -> service.queryXianyuItems(accountId, 1, 20, ""));
        verifyNoInteractions(providerClient);

        account.setEnabled(true);
        account.setProvider(null);
        assertThrows(RuntimeException.class, () -> service.syncXianyuItems(accountId, UUID.randomUUID().toString()));
        verifyNoInteractions(providerClient);
    }

    @Test
    void manualCatalogSyncIsSafeRepeatableAndDoesNotTouchListingOrOrderState() {
        UUID accountId = UUID.randomUUID();
        SalesChannelAccount account = new SalesChannelAccount();
        account.setId(accountId);
        account.setProvider(SalesChannelProvider.XIANYU);
        account.setProviderAccountId("2215747465381");
        account.setEnabled(true);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(providerClient.syncItems(any())).thenReturn(new IntegrationEnvelope<>(
                UUID.randomUUID().toString(), Outcome.SUCCEEDED,
                new ItemsSyncData("2215747465381", 42, 40, 3, false), null));

        String requestId = UUID.randomUUID().toString();
        var result = service.syncXianyuItems(accountId, requestId);

        assertEquals(accountId.toString(), result.accountId());
        assertEquals(42, result.fetchedCount());
        assertEquals(40, result.savedCount());
        assertEquals(3, result.pages());
        verify(providerClient).syncItems(new ItemsSyncCommand(requestId, "2215747465381"));
        verifyNoInteractions(listingRepository, listingService, cursorRepository, channelOrderRepository,
                deliveryOperationRepository, orderRepository, orderItemRepository);
    }

    @Test
    void retryableCatalogSyncFailureKeepsSafeRepeatableDisposition() {
        UUID accountId = UUID.randomUUID();
        SalesChannelAccount account = new SalesChannelAccount();
        account.setId(accountId);
        account.setProvider(SalesChannelProvider.XIANYU);
        account.setProviderAccountId("2215747465381");
        account.setEnabled(true);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(providerClient.syncItems(any())).thenReturn(new IntegrationEnvelope<>(
                "retry", Outcome.FAILED, null,
                new com.myshop.saleschannel.model.SalesChannelDtos.IntegrationError(
                        "ITEM_SYNC_BUSY", "busy", true)));

        SalesChannelProviderClientException error = assertThrows(SalesChannelProviderClientException.class,
                () -> service.syncXianyuItems(accountId, UUID.randomUUID().toString()));
        assertEquals("ITEM_SYNC_BUSY", error.getErrorCode());
        assertEquals(SalesChannelProviderClientException.Disposition.SAFE_RETRYABLE,
                error.getDisposition());
        verifyNoInteractions(listingRepository, listingService, cursorRepository, channelOrderRepository,
                deliveryOperationRepository, orderRepository, orderItemRepository);
    }

    private static CatalogItem catalogItem(String itemId, String title) {
        return new CatalogItem("2215747465381", itemId, title, "20", 5, "在售",
                1, false, true, "2026-09-24T00:00:00Z", "2026-09-24T01:00:00Z");
    }

    private static SalesChannelListing listing(UUID accountId, UUID productId, String itemId) {
        SalesChannelListing listing = new SalesChannelListing();
        listing.setId(UUID.randomUUID());
        listing.setSalesChannelAccountId(accountId);
        listing.setProductId(productId);
        return listing;
    }

    private static SalesChannelListingGeneration generation(SalesChannelListing listing, String itemId) {
        SalesChannelListingGeneration generation = new SalesChannelListingGeneration(
                listing.getId(), listing.getSalesChannelAccountId(), 1, itemId, true, "LEGACY_MIGRATION");
        generation.setId(UUID.randomUUID());
        return generation;
    }

    private static Product product(UUID id, String title) {
        Product product = new Product();
        product.setId(id);
        product.setTitle(title);
        return product;
    }
}
