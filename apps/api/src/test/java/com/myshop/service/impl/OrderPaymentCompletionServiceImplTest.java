package com.myshop.service.impl;

import com.myshop.constant.OrderStatus;
import com.myshop.entity.Order;
import com.myshop.repository.OrderRepository;
import com.myshop.service.AutoFulfillmentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderPaymentCompletionServiceImplTest {

    @Mock private OrderRepository orderRepository;
    @Mock private AutoFulfillmentService autoFulfillmentService;

    @Test
    void firstPaymentTransitionIsIdempotentAndTriggersFulfillmentOnce() {
        UUID orderId = UUID.randomUUID();
        Order order = new Order();
        order.setId(orderId);
        order.setStatus(OrderStatus.PENDING);
        OrderPaymentCompletionServiceImpl service =
                new OrderPaymentCompletionServiceImpl(orderRepository, autoFulfillmentService);

        assertTrue(service.completeFirstPayment(order));
        assertFalse(service.completeFirstPayment(order));

        assertEquals(OrderStatus.PAID, order.getStatus());
        assertNotNull(order.getPaidAt());
        verify(orderRepository, times(1)).save(order);
        verify(autoFulfillmentService, times(1)).trigger(orderId);
    }

    @Test
    void alreadyPaidOrderDoesNotMutateOrTriggerAgain() {
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setStatus(OrderStatus.PAID);
        OrderPaymentCompletionServiceImpl service =
                new OrderPaymentCompletionServiceImpl(orderRepository, autoFulfillmentService);

        assertFalse(service.completeFirstPayment(order));

        verify(orderRepository, times(0)).save(any(Order.class));
        verify(autoFulfillmentService, times(0)).trigger(any(UUID.class));
    }
}
