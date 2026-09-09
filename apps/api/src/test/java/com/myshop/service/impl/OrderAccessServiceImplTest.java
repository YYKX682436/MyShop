package com.myshop.service.impl;

import com.myshop.constant.ErrorCode;
import com.myshop.constant.OrderStatus;
import com.myshop.entity.CardKey;
import com.myshop.entity.Order;
import com.myshop.entity.OrderItem;
import com.myshop.exception.BusinessException;
import com.myshop.payment.PaymentStatus;
import com.myshop.repository.CardKeyRepository;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.PaymentAttemptRepository;
import com.myshop.service.BalanceOrderPaymentService;
import com.myshop.service.OrderService;
import com.myshop.service.PaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderAccessServiceImplTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private CardKeyRepository cardKeyRepository;
    @Mock private PaymentAttemptRepository paymentAttemptRepository;
    @Mock private OrderService orderService;
    @Mock private PaymentService paymentService;
    @Mock private BalanceOrderPaymentService balanceOrderPaymentService;

    private OrderAccessServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new OrderAccessServiceImpl(orderRepository, orderItemRepository, cardKeyRepository,
                paymentAttemptRepository, orderService, paymentService, balanceOrderPaymentService,
                new ObjectMapper());
    }

    @Test
    void oneGuestSessionCanReadRefreshRepayAndExportItsOrder() {
        UUID orderId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        Order order = guestOrder(orderId, "session-a");
        order.setStatus(OrderStatus.PENDING);
        OrderItem item = item(itemId);
        CardKey key = cardKey(orderId, itemId);
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(List.of(item));
        when(cardKeyRepository.findByOrderIdOrderByCreatedAtAsc(orderId)).thenReturn(List.of(key));
        when(paymentAttemptRepository.findByOrderIdOrderByCreatedAtDesc(orderId)).thenReturn(List.of());
        when(paymentService.repay(orderId, "mobile"))
                .thenReturn(Map.of("order_id", orderId, "status", PaymentStatus.PENDING));

        Map<String, Object> view = service.getOrder(orderId, null, "session-a");
        assertEquals(orderId, ((Map<?, ?>) view.get("order")).get("id"));
        Map<?, ?> delivery = (Map<?, ?>) view.get("delivery");
        List<?> groups = (List<?>) delivery.get("groups");
        Map<?, ?> group = (Map<?, ?>) groups.get(0);
        List<?> deliveryItems = (List<?>) group.get("delivery_items");
        Map<?, ?> deliveryItem = (Map<?, ?>) deliveryItems.get(0);
        assertEquals("card-value", ((List<?>) deliveryItem.get("content_parts")).get(0));

        Map<String, Object> refreshed = service.refreshOrder(orderId, null, "session-a");
        assertEquals(orderId, ((Map<?, ?>) refreshed.get("order")).get("id"));
        verify(paymentService).refreshPayment(orderId);

        Map<String, Object> repaid = service.repayOrder(orderId, "mobile", null, "session-a");
        assertEquals(PaymentStatus.PENDING, repaid.get("status"));

        assertEquals("MyShop 卡密\n\n测试商品\ncard-value\n",
                service.exportCardKeys(orderId, null, "session-a"));
    }

    @Test
    void missingOrDifferentGuestSessionIsForbiddenBeforeSensitiveReads() {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findByIdForUpdate(orderId))
                .thenReturn(Optional.of(guestOrder(orderId, "session-a")));

        assertForbidden(() -> service.getOrder(orderId, null, null));
        clearInvocations(orderRepository);
        assertForbidden(() -> service.refreshOrder(orderId, null, "session-b"));
        clearInvocations(orderRepository);
        assertForbidden(() -> service.repayOrder(orderId, "pc", null, "session-b"));
        clearInvocations(orderRepository);
        assertForbidden(() -> service.exportCardKeys(orderId, null, "session-b"));
        clearInvocations(orderRepository);
        assertForbidden(() -> service.getOrder(orderId, UUID.randomUUID(), null));

        verifyNoInteractions(orderItemRepository, cardKeyRepository, paymentAttemptRepository, paymentService);
    }

    @Test
    void registeredOwnerCanAccessOnlyTheirOrderAndCannotUseGuestIdentityAsABypass() {
        UUID orderId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        Order registered = guestOrder(orderId, "session-a");
        registered.setUserId(ownerId);
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(registered));
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(List.of());
        when(cardKeyRepository.findByOrderIdOrderByCreatedAtAsc(orderId)).thenReturn(List.of());
        when(paymentAttemptRepository.findByOrderIdOrderByCreatedAtDesc(orderId)).thenReturn(List.of());

        assertEquals(orderId, ((Map<?, ?>) service.getOrder(orderId, ownerId, null).get("order")).get("id"));
        assertForbidden(() -> service.getOrder(orderId, otherUserId, "session-a"));
        assertForbidden(() -> service.getOrder(orderId, null, "session-a"));
        verify(orderRepository, never()).findById(orderId);
    }

    private void assertForbidden(Runnable action) {
        BusinessException exception = assertThrows(BusinessException.class, action::run);
        assertEquals(ErrorCode.FORBIDDEN, exception.getCode());
    }

    private Order guestOrder(UUID id, String sessionToken) {
        Order order = new Order();
        order.setId(id);
        order.setSessionToken(sessionToken);
        order.setStatus(OrderStatus.PAID);
        order.setTotalAmount(java.math.BigDecimal.TEN);
        order.setActualAmount(java.math.BigDecimal.TEN);
        return order;
    }

    private OrderItem item(UUID id) {
        OrderItem item = new OrderItem();
        item.setId(id);
        item.setProductNo(1L);
        item.setProductTitle("测试商品");
        item.setQuantity(1);
        item.setSubtotal(java.math.BigDecimal.TEN);
        item.setCustomerInputs(List.of());
        return item;
    }

    private CardKey cardKey(UUID orderId, UUID itemId) {
        CardKey key = new CardKey();
        key.setId(UUID.randomUUID());
        key.setOrderId(orderId);
        key.setOrderItemId(itemId);
        key.setContentParts(List.of("card-value"));
        return key;
    }
}
