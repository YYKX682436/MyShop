package com.myshop.service;

import com.myshop.constant.OrderStatus;
import com.myshop.entity.Order;
import com.myshop.entity.PaymentAttempt;
import com.myshop.payment.PaymentCreationStatus;
import com.myshop.payment.PaymentProvider;
import com.myshop.payment.PaymentStatus;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.PaymentAttemptRepository;
import com.myshop.service.impl.PaymentConfirmationTransactionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentQueryTransactionServiceTest {
    @Mock OrderRepository orders;
    @Mock PaymentAttemptRepository attempts;
    @Mock OrderExpiryService expiry;
    @Mock PaymentConfirmationTransactionService confirmation;
    @InjectMocks PaymentQueryTransactionService service;

    @Test
    void unknownCreationIsQueryableWithoutInventingACarrier() {
        UUID orderId = UUID.randomUUID();
        Order order = new Order(); order.setId(orderId); order.setStatus(OrderStatus.PENDING);
        PaymentAttempt attempt = new PaymentAttempt(); attempt.setId(UUID.randomUUID());
        attempt.setCreationStatus(PaymentCreationStatus.UNKNOWN); attempt.setProviderType("epay");
        attempt.setMerchantTradeNo("merchant"); attempt.setProviderConfigSnapshot("{}");
        when(orders.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(attempts.findByOrderIdOrderByCreatedAtDesc(orderId)).thenReturn(List.of(attempt));
        var context = service.prepare(orderId);
        assertEquals(attempt.getId(), context.attemptId());
        assertEquals("merchant", context.merchantTradeNo());
    }

    @Test
    void verifiedQueryUsesTheSameLockedConfirmationBoundaryAsCallback() {
        UUID orderId = UUID.randomUUID(), attemptId = UUID.randomUUID();
        var context = new PaymentQueryTransactionService.QueryContext(attemptId, "epay", "merchant", "{}");
        when(attempts.findOrderId(attemptId)).thenReturn(Optional.of(orderId));
        service.complete(orderId, context, new PaymentProvider.PaymentQueryResult(PaymentStatus.PAID, new BigDecimal("10.00"), "trade"));
        verify(confirmation).confirmPaid(eq(attemptId), argThat(callback -> callback.eventId().equals(com.myshop.payment.PaymentEventIdentity.scoped("epay", "merchant", "epay:trade"))
                && callback.merchantTradeNo().equals("merchant") && callback.amount().compareTo(new BigDecimal("10.00")) == 0));
    }

    @Test
    void foreignAttemptOrIncompleteQueryCannotConfirm() {
        UUID orderId = UUID.randomUUID(), attemptId = UUID.randomUUID();
        var context = new PaymentQueryTransactionService.QueryContext(attemptId, "epay", "merchant", "{}");
        when(attempts.findOrderId(attemptId)).thenReturn(Optional.of(UUID.randomUUID()));
        service.complete(orderId, context, new PaymentProvider.PaymentQueryResult(PaymentStatus.PAID, new BigDecimal("10.00"), "trade"));
        verifyNoInteractions(confirmation);
    }
}
