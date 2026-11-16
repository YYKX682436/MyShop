package com.myshop.service;

import com.myshop.constant.FulfillmentStatus;
import com.myshop.constant.OrderStatus;
import com.myshop.entity.Order;
import com.myshop.entity.OrderItem;
import com.myshop.entity.PaymentAttempt;
import com.myshop.payment.PaymentStatus;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.PaymentAttemptRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderExpiryServiceTest {
    @Mock OrderRepository orders;
    @Mock OrderItemRepository items;
    @Mock PaymentAttemptRepository attempts;
    @Mock ManualStockService stock;
    @InjectMocks OrderExpiryService service;

    @Test
    void expiryClosesAttemptsAndReleasesReservationOnlyOnce() {
        Order order = expiredOrder();
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setStatus(PaymentStatus.PENDING);
        OrderItem item = new OrderItem();
        item.setProductId(UUID.randomUUID());
        item.setManualStockReserved(2);
        item.setFulfillmentStatus(FulfillmentStatus.PENDING);
        when(attempts.findByOrderIdAndStatusForUpdate(order.getId(), PaymentStatus.PENDING))
                .thenReturn(List.of(attempt));
        when(items.findByOrderId(order.getId())).thenReturn(List.of(item));

        service.expireLockedOrder(order);
        service.expireLockedOrder(order);

        assertEquals(OrderStatus.EXPIRED, order.getStatus());
        assertEquals(PaymentStatus.CLOSED, attempt.getStatus());
        assertEquals(FulfillmentStatus.CANCELLED, item.getFulfillmentStatus());
        assertEquals(0, item.getManualStockReserved());
        verify(stock).release(item.getProductId(), null, 2);
        verify(attempts).save(attempt);
        verify(orders).save(order);
    }

    @Test
    void anOrderPaidBeforeTheExpiryLockNeverReleasesItsStock() {
        Order order = expiredOrder();
        order.setStatus(OrderStatus.PAID);

        service.expireLockedOrder(order);

        assertEquals(OrderStatus.PAID, order.getStatus());
        verifyNoInteractions(items, attempts, stock, orders);
    }

    private Order expiredOrder() {
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setStatus(OrderStatus.PENDING);
        order.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        return order;
    }
}
