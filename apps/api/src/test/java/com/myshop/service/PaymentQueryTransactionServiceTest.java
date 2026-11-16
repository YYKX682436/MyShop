package com.myshop.service;

import com.myshop.constant.OrderStatus;
import com.myshop.entity.Order;
import com.myshop.entity.PaymentAttempt;
import com.myshop.payment.PaymentProvider;
import com.myshop.payment.PaymentStatus;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.PaymentAttemptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentQueryTransactionServiceTest {
    @Mock OrderRepository orders;
    @Mock PaymentAttemptRepository attempts;
    @Mock OrderExpiryService expiry;
    @Mock OrderPaymentCompletionService completion;
    @InjectMocks PaymentQueryTransactionService service;
    Order order;
    PaymentAttempt attempt;

    @BeforeEach
    void setUp() {
        order = new Order();
        order.setId(UUID.randomUUID());
        order.setStatus(OrderStatus.PENDING);
        attempt = new PaymentAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setOrderId(order.getId());
        attempt.setStatus(PaymentStatus.PENDING);
        attempt.setAmount(new BigDecimal("10.00"));
        attempt.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        when(orders.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(attempts.findByIdForUpdate(attempt.getId())).thenReturn(Optional.of(attempt));
    }

    @Test
    void anAttemptClosedDuringTheQueryIsNotRevived() {
        attempt.setStatus(PaymentStatus.CLOSED);

        service.complete(order.getId(), attempt.getId(), paid("10.00", "trade"));

        assertEquals(PaymentStatus.CLOSED, attempt.getStatus());
        verify(attempts, never()).save(any());
        verifyNoInteractions(completion);
    }

    @Test
    void wrongAmountAndConflictingProviderTradeCannotCompletePayment() {
        service.complete(order.getId(), attempt.getId(), paid("9.99", "trade"));
        attempt.setProviderTradeNo("original-trade");
        service.complete(order.getId(), attempt.getId(), paid("10.00", "other-trade"));

        assertEquals(PaymentStatus.PENDING, attempt.getStatus());
        verify(attempts, never()).save(any());
        verifyNoInteractions(completion);
    }

    @Test
    void matchingPendingAttemptCompletesThroughTheSharedPaymentBoundary() {
        service.complete(order.getId(), attempt.getId(), paid("10.00", "trade"));

        assertEquals(PaymentStatus.PAID, attempt.getStatus());
        assertEquals("trade", attempt.getProviderTradeNo());
        verify(completion).completeFirstPayment(order);
    }

    private PaymentProvider.PaymentQueryResult paid(String amount, String trade) {
        return new PaymentProvider.PaymentQueryResult(PaymentStatus.PAID, new BigDecimal(amount), trade);
    }
}
