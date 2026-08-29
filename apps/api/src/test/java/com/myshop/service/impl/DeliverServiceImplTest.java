package com.myshop.service.impl;

import com.myshop.constant.CardKeyStatus;
import com.myshop.constant.FulfillmentStatus;
import com.myshop.constant.OrderStatus;
import com.myshop.constant.StockMode;
import com.myshop.entity.CardKey;
import com.myshop.entity.Order;
import com.myshop.entity.OrderItem;
import com.myshop.exception.BusinessException;
import com.myshop.repository.CardKeyRepository;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.service.EmailService;
import com.myshop.service.FulfillmentService;
import com.myshop.service.ManualStockService;
import com.myshop.utils.LookupTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliverServiceImplTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private CardKeyRepository cardKeyRepository;
    @Mock private EmailService emailService;
    @Mock private ManualStockService manualStockService;

    private DeliverServiceImpl service;

    @BeforeEach
    void setUp() {
        FulfillmentService fulfillmentService = new FulfillmentTransactionService(orderRepository, orderItemRepository,
                cardKeyRepository, emailService, manualStockService);
        service = new DeliverServiceImpl(orderRepository, orderItemRepository, cardKeyRepository,
                fulfillmentService, manualStockService);
    }

    @Test
    void shortageInLaterOrderLineDoesNotMutateEarlierPool() {
        UUID orderId = UUID.randomUUID();
        UUID firstProductId = UUID.randomUUID();
        UUID secondProductId = UUID.randomUUID();
        String lookupToken = "guest-token-for-test";

        Order order = new Order();
        order.setId(orderId);
        order.setStatus(OrderStatus.PAID);
        order.setLookupTokenHash(LookupTokens.hash(lookupToken));

        OrderItem first = item(orderId, firstProductId, 1);
        OrderItem second = item(orderId, secondProductId, 1);
        CardKey available = new CardKey();
        available.setId(UUID.randomUUID());
        available.setProductId(firstProductId);
        available.setContentParts(List.of("first-card"));
        available.setContentFingerprint("fingerprint");
        available.setStatus(CardKeyStatus.AVAILABLE);

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(List.of(first, second));
        when(cardKeyRepository.findAndLockAvailable(firstProductId, null, 1)).thenReturn(List.of(available));
        when(cardKeyRepository.findAndLockAvailable(secondProductId, null, 1)).thenReturn(List.of());

        BusinessException exception = assertThrows(BusinessException.class, () ->
                service.deliverOrders(Map.of(
                        "orders", List.of(Map.of(
                                "order_id", orderId.toString(),
                                "lookup_token", lookupToken))), null));

        assertEquals("缺货补货中，请联系客服", exception.getMessage());
        assertEquals(CardKeyStatus.AVAILABLE, available.getStatus());
        verify(cardKeyRepository, never()).saveAll(anyList());
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void orderUuidAloneCannotTriggerGuestDelivery() {
        UUID orderId = UUID.randomUUID();
        Order order = new Order();
        order.setId(orderId);
        order.setStatus(OrderStatus.PAID);
        order.setLookupTokenHash(LookupTokens.hash("correct-token"));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        BusinessException exception = assertThrows(BusinessException.class, () ->
                service.deliverOrders(Map.of(
                        "orders", List.of(Map.of("order_id", orderId.toString()))), null));

        assertEquals("订单查询凭证无效", exception.getMessage());
        verify(cardKeyRepository, never()).findAndLockAvailable(any(), any(), anyInt());
    }

    @Test
    void deliveredRetryReturnsExistingKeysWithoutAllocatingAgain() {
        UUID orderId = UUID.randomUUID();
        String lookupToken = "delivered-token";
        Order order = new Order();
        order.setId(orderId);
        order.setStatus(OrderStatus.DELIVERED);
        order.setLookupTokenHash(LookupTokens.hash(lookupToken));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(cardKeyRepository.findByOrderIdOrderByCreatedAtAsc(orderId)).thenReturn(List.of());
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(List.of());

        List<?> result = service.deliverOrders(Map.of(
                "orders", List.of(Map.of(
                        "order_id", orderId.toString(),
                        "lookup_token", lookupToken))), null);

        Map<?, ?> response = (Map<?, ?>) result.get(0);
        assertEquals("DELIVERED", response.get("status"));
        verify(cardKeyRepository, never()).findAndLockAvailable(any(), any(), anyInt());
    }

    private OrderItem item(UUID orderId, UUID productId, int quantity) {
        OrderItem item = new OrderItem();
        item.setId(UUID.randomUUID());
        item.setOrderId(orderId);
        item.setProductId(productId);
        item.setProductTitle("Test product");
        item.setQuantity(quantity);
        item.setDeliveryType("AUTO");
        item.setStockMode(StockMode.CARD_KEY);
        item.setFulfillmentStatus(FulfillmentStatus.PENDING);
        item.setManualStockReserved(0);
        return item;
    }
}
