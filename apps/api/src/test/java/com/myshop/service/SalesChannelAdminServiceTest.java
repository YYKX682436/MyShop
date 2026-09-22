package com.myshop.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.constant.SalesChannelAccountStatus;
import com.myshop.constant.SalesChannelProvider;
import com.myshop.constant.StockMode;
import com.myshop.entity.Product;
import com.myshop.entity.SalesChannelAccount;
import com.myshop.entity.SalesChannelListing;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.SalesChannelAccountRepository;
import com.myshop.repository.SalesChannelDeliveryOperationRepository;
import com.myshop.repository.SalesChannelListingRepository;
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
}
