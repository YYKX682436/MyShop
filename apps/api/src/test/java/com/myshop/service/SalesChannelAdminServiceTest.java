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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
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
}
