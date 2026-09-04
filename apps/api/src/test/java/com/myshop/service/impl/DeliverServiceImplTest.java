package com.myshop.service.impl;

import com.myshop.constant.CardKeyStatus;
import com.myshop.constant.ErrorCode;
import com.myshop.constant.FulfillmentStatus;
import com.myshop.constant.OrderStatus;
import com.myshop.constant.OrderType;
import com.myshop.constant.StockMode;
import com.myshop.entity.CardKey;
import com.myshop.entity.Order;
import com.myshop.entity.OrderItem;
import com.myshop.exception.BusinessException;
import com.myshop.repository.CardKeyRepository;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.service.EmailService;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
    private FulfillmentTransactionService fulfillmentService;

    @BeforeEach
    void setUp() {
        fulfillmentService = new FulfillmentTransactionService(orderRepository, orderItemRepository,
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
        order.setOrderNo(1000000001L);
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

        when(orderRepository.findByOrderNo(order.getOrderNo())).thenReturn(Optional.of(order));
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(List.of(first, second));
        when(cardKeyRepository.findAndLockAvailable(firstProductId, null, 1)).thenReturn(List.of(available));
        when(cardKeyRepository.findAndLockAvailable(secondProductId, null, 1)).thenReturn(List.of());

        BusinessException exception = assertThrows(BusinessException.class, () ->
                service.deliverOrders(Map.of(
                        "orders", List.of(Map.of(
                                "order_no", order.getOrderNo().toString(),
                                "lookup_token", lookupToken))), null));

        assertEquals("缺货补货中，请联系客服", exception.getMessage());
        assertEquals(CardKeyStatus.AVAILABLE, available.getStatus());
        verify(cardKeyRepository, never()).saveAll(anyList());
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void orderNumberAloneCannotTriggerGuestDelivery() {
        UUID orderId = UUID.randomUUID();
        Order order = new Order();
        order.setId(orderId);
        order.setOrderNo(1000000001L);
        order.setStatus(OrderStatus.PAID);
        order.setLookupTokenHash(LookupTokens.hash("correct-token"));
        when(orderRepository.findByOrderNo(order.getOrderNo())).thenReturn(Optional.of(order));

        BusinessException exception = assertThrows(BusinessException.class, () ->
                service.deliverOrders(Map.of(
                        "orders", List.of(Map.of("order_no", order.getOrderNo().toString()))), null));

        assertEquals("订单查询凭证无效", exception.getMessage());
        verify(cardKeyRepository, never()).findAndLockAvailable(any(), any(), anyInt());
    }

    @Test
    void deliveredRetryReturnsExistingKeysWithoutAllocatingAgain() {
        UUID orderId = UUID.randomUUID();
        String lookupToken = "delivered-token";
        Order order = new Order();
        order.setId(orderId);
        order.setOrderNo(1000000001L);
        order.setStatus(OrderStatus.DELIVERED);
        order.setLookupTokenHash(LookupTokens.hash(lookupToken));
        when(orderRepository.findByOrderNo(order.getOrderNo())).thenReturn(Optional.of(order));
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(cardKeyRepository.findByOrderIdOrderByCreatedAtAsc(orderId)).thenReturn(List.of());
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(List.of());

        List<?> result = service.deliverOrders(Map.of(
                "orders", List.of(Map.of(
                        "order_no", order.getOrderNo().toString(),
                        "lookup_token", lookupToken))), null);

        Map<?, ?> response = (Map<?, ?>) result.get(0);
        assertEquals("DELIVERED", response.get("status"));
        verify(cardKeyRepository, never()).findAndLockAvailable(any(), any(), anyInt());
    }

    @Test
    void guestCanQueryByOrderNumberAndWrongTokenIsRejected() {
        UUID orderId = UUID.randomUUID();
        String lookupToken = "query-order-number-token";
        Order order = new Order();
        order.setId(orderId);
        order.setOrderNo(1000000001L);
        order.setStatus(OrderStatus.PAID);
        order.setOrderType(OrderType.NORMAL);
        order.setLookupTokenHash(LookupTokens.hash(lookupToken));
        when(orderRepository.findByOrderNo(order.getOrderNo())).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(List.of());

        List<?> result = service.queryOrders(Map.of(
                "orders", List.of(Map.of("order_no", order.getOrderNo().toString(),
                        "lookup_token", lookupToken))), null);

        Map<?, ?> brief = (Map<?, ?>) result.get(0);
        assertEquals(order.getOrderNo(), brief.get("order_no"));
        assertEquals(orderId, brief.get("id"));

        BusinessException missingToken = assertThrows(BusinessException.class, () -> service.queryOrders(Map.of(
                "orders", List.of(Map.of("order_no", order.getOrderNo().toString()))), null));
        assertEquals(ErrorCode.FORBIDDEN, missingToken.getCode());

        BusinessException exception = assertThrows(BusinessException.class, () -> service.queryOrders(Map.of(
                "orders", List.of(Map.of("order_no", order.getOrderNo().toString(),
                        "lookup_token", "wrong-token"))), null));
        assertEquals(ErrorCode.FORBIDDEN, exception.getCode());
    }

    @Test
    void registeredOwnerCanQueryAndExportWithoutLookupToken() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        Order order = paidOrder(orderId, OrderType.NORMAL);
        order.setStatus(OrderStatus.DELIVERED);
        order.setUserId(userId);
        OrderItem item = item(orderId, UUID.randomUUID(), 1);
        item.setId(itemId);
        CardKey card = new CardKey();
        card.setId(UUID.randomUUID());
        card.setOrderId(orderId);
        card.setOrderItemId(itemId);
        card.setContentParts(List.of("account", "secret"));
        card.setStatus(CardKeyStatus.SOLD);

        when(orderRepository.findByOrderNo(order.getOrderNo())).thenReturn(Optional.of(order));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(List.of(item));
        when(cardKeyRepository.findByOrderIdOrderByCreatedAtAsc(orderId)).thenReturn(List.of(card));

        List<?> result = service.queryOrders(Map.of(
                "orders", List.of(Map.of("order_no", order.getOrderNo().toString()))), userId);
        Map<?, ?> brief = (Map<?, ?>) result.get(0);
        assertEquals(order.getOrderNo(), brief.get("order_no"));

        String exported = service.exportCardKeys(orderId, null, userId);
        assertTrue(exported.contains("卡券内容 1: account"));
        assertTrue(exported.contains("卡券内容 2: secret"));
    }

    @Test
    void adminPickupAutomaticFulfillmentDoesNotSendDeliveryEmail() {
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        Order order = paidOrder(orderId, OrderType.ADMIN_PICKUP);
        OrderItem item = item(orderId, productId, 1);
        item.setId(itemId);
        CardKey card = new CardKey();
        card.setId(UUID.randomUUID());
        card.setProductId(productId);
        card.setContentParts(List.of("admin-card"));
        card.setStatus(CardKeyStatus.AVAILABLE);

        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(List.of(item));
        when(cardKeyRepository.findAndLockAvailable(productId, null, 1)).thenReturn(List.of(card));
        when(cardKeyRepository.findByOrderIdOrderByCreatedAtAsc(orderId)).thenReturn(List.of(card));

        fulfillmentService.fulfillOrder(orderId);

        assertEquals(OrderStatus.DELIVERED, order.getStatus());
        assertEquals(CardKeyStatus.SOLD, card.getStatus());
        verify(emailService, never()).sendDeliveryEmail(any(), any(), anyList());
    }

    @Test
    void adminPickupManualCompletionDoesNotSendDeliveryEmail() {
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        Order order = paidOrder(orderId, OrderType.ADMIN_PICKUP);
        OrderItem item = item(orderId, productId, 1);
        item.setId(itemId);
        item.setDeliveryType("MANUAL");
        item.setStockMode(StockMode.MANUAL);
        item.setManualStockReserved(0);

        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(orderItemRepository.findById(itemId)).thenReturn(Optional.of(item));
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(List.of(item));
        when(cardKeyRepository.findByOrderIdOrderByCreatedAtAsc(orderId)).thenReturn(List.of());

        fulfillmentService.completeManualItem(orderId, itemId);

        assertEquals(OrderStatus.DELIVERED, order.getStatus());
        assertEquals(FulfillmentStatus.DELIVERED, item.getFulfillmentStatus());
        verify(emailService, never()).sendDeliveryEmail(any(), any(), anyList());
    }

    @Test
    void normalAutomaticFulfillmentStillSendsDeliveryEmail() {
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        Order order = paidOrder(orderId, OrderType.NORMAL);
        OrderItem item = item(orderId, productId, 1);
        item.setId(itemId);
        CardKey card = new CardKey();
        card.setId(UUID.randomUUID());
        card.setProductId(productId);
        card.setContentParts(List.of("normal-card"));
        card.setStatus(CardKeyStatus.AVAILABLE);

        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(List.of(item));
        when(cardKeyRepository.findAndLockAvailable(productId, null, 1)).thenReturn(List.of(card));
        when(cardKeyRepository.findByOrderIdOrderByCreatedAtAsc(orderId)).thenReturn(List.of(card));

        fulfillmentService.fulfillOrder(orderId);

        verify(emailService).sendDeliveryEmail(eq(orderId), isNull(), eq(List.of(itemId)));
    }

    private Order paidOrder(UUID orderId, OrderType orderType) {
        Order order = new Order();
        order.setId(orderId);
        order.setOrderNo(1000000001L);
        order.setStatus(OrderStatus.PAID);
        order.setOrderType(orderType);
        return order;
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
