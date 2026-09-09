package com.myshop.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.constant.ErrorCode;
import com.myshop.constant.OrderStatus;
import com.myshop.constant.OrderType;
import com.myshop.entity.Order;
import com.myshop.entity.PaymentAttempt;
import com.myshop.entity.PaymentChannel;
import com.myshop.exception.BusinessException;
import com.myshop.payment.PaymentNextAction;
import com.myshop.payment.PaymentProvider;
import com.myshop.payment.PaymentStatus;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.PaymentAttemptRepository;
import com.myshop.repository.PaymentChannelRepository;
import com.myshop.service.ManualStockService;
import com.myshop.utils.LookupTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceImplTest {

    @Mock private PaymentChannelRepository paymentChannelRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private PaymentAttemptRepository paymentAttemptRepository;
    @Mock private PaymentProvider provider;
    @Mock private ManualStockService manualStockService;

    private PaymentServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(provider.id()).thenReturn("epay");
        lenient().when(provider.paymentTypes()).thenReturn(List.of("alipay", "wxpay"));
        lenient().when(orderItemRepository.findByOrderId(any())).thenReturn(List.of());
        lenient().when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(paymentAttemptRepository.save(any(PaymentAttempt.class)))
                .thenAnswer(invocation -> {
                    PaymentAttempt attempt = invocation.getArgument(0);
                    if (attempt.getId() == null) attempt.setId(UUID.randomUUID());
                    return attempt;
                });
        lenient().when(paymentAttemptRepository.existsByMerchantTradeNo(anyString())).thenReturn(false);
        service = new PaymentServiceImpl(paymentChannelRepository, orderRepository, orderItemRepository,
                paymentAttemptRepository, List.of(provider), new ObjectMapper(), manualStockService);
    }

    @Test
    void createsOneAttemptAndReusesItWithoutCallingTheProviderAgain() throws Exception {
        UUID orderId = UUID.randomUUID();
        Order order = pendingOrder(orderId, "10.00");
        PaymentChannel channel = channel("alipay");
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(paymentChannelRepository.findByChannelCodeAndIsDeleted("alipay", 0))
                .thenReturn(Optional.of(channel));
        when(paymentAttemptRepository.findFirstByOrderIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(orderId), eq(PaymentStatus.PENDING), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());
        PaymentNextAction.Redirect action = new PaymentNextAction.Redirect("https://pay.example/checkout");
        when(provider.create(any(PaymentProvider.PaymentRequest.class)))
                .thenReturn(new PaymentProvider.PaymentResult("trade-1", action));

        Map<String, Object> first = service.createPayment(orderId, "alipay", new BigDecimal("10.00"), "pc");
        PaymentAttempt saved = capturedAttempt();
        when(paymentAttemptRepository.findFirstByOrderIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(orderId), eq(PaymentStatus.PENDING), any(LocalDateTime.class)))
                .thenReturn(Optional.of(saved));

        Map<String, Object> second = service.createPayment(orderId, "alipay", new BigDecimal("10.00"), "pc");

        assertEquals(saved.getId(), first.get("payment_id"));
        assertEquals(saved.getId(), second.get("payment_id"));
        assertEquals(PaymentStatus.PENDING, first.get("status"));
        assertEquals(Map.of("type", "redirect", "url", action.url()), first.get("next_action"));
        assertEquals(Map.of("type", "redirect", "url", action.url()), second.get("next_action"));
        assertEquals("trade-1", saved.getProviderTradeNo());
        assertEquals("{\"pid\":\"pid\",\"key\":\"secret\",\"api_url\":\"https://pay.example\"}",
                saved.getProviderConfigSnapshot());
        assertNotEquals(order.getOrderNo() == null ? null : order.getOrderNo().toString(),
                saved.getMerchantTradeNo());
        assertFalse(first.containsKey("provider_config_snapshot"));
        verify(provider).create(any(PaymentProvider.PaymentRequest.class));
    }

    @Test
    void ablationWithoutActiveAttemptReuseCallsProviderAgain() {
        UUID orderId = UUID.randomUUID();
        Order order = pendingOrder(orderId, "10.00");
        PaymentChannel channel = channel("alipay");
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(paymentChannelRepository.findByChannelCodeAndIsDeleted("alipay", 0))
                .thenReturn(Optional.of(channel));
        // Controlled ablation: hide the active attempt on both requests, as if
        // the single-attempt reuse guard had been removed from production code.
        when(paymentAttemptRepository.findFirstByOrderIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(orderId), eq(PaymentStatus.PENDING), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());
        when(provider.create(any(PaymentProvider.PaymentRequest.class)))
                .thenReturn(
                        new PaymentProvider.PaymentResult("trade-ablation-1",
                                new PaymentNextAction.Redirect("https://pay.example/one")),
                        new PaymentProvider.PaymentResult("trade-ablation-2",
                                new PaymentNextAction.Redirect("https://pay.example/two")));

        service.createPayment(orderId, "alipay", new BigDecimal("10.00"), "pc");
        service.createPayment(orderId, "alipay", new BigDecimal("10.00"), "pc");

        verify(provider, times(2)).create(any(PaymentProvider.PaymentRequest.class));
        ArgumentCaptor<PaymentAttempt> attempts = ArgumentCaptor.forClass(PaymentAttempt.class);
        verify(paymentAttemptRepository, times(2)).save(attempts.capture());
        assertNotEquals(attempts.getAllValues().get(0).getMerchantTradeNo(),
                attempts.getAllValues().get(1).getMerchantTradeNo());
    }

    @Test
    void sendsTheIndependentMerchantTradeNumberAndAmountToTheProvider() {
        UUID orderId = UUID.randomUUID();
        Order order = pendingOrder(orderId, "10.00");
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(paymentChannelRepository.findByChannelCodeAndIsDeleted("alipay", 0))
                .thenReturn(Optional.of(channel("alipay")));
        when(paymentAttemptRepository.findFirstByOrderIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(orderId), eq(PaymentStatus.PENDING), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());
        when(provider.create(any(PaymentProvider.PaymentRequest.class)))
                .thenReturn(new PaymentProvider.PaymentResult(
                        "trade-2", new PaymentNextAction.QrCode("qr-token")));

        service.createPayment(orderId, "alipay", new BigDecimal("10.00"), "mobile");

        ArgumentCaptor<PaymentProvider.PaymentRequest> request =
                ArgumentCaptor.forClass(PaymentProvider.PaymentRequest.class);
        verify(provider).create(request.capture());
        assertNotNull(request.getValue().merchantTradeNo());
        assertFalse(request.getValue().merchantTradeNo().isBlank());
        assertEquals(new BigDecimal("10.00"), request.getValue().amount());
        assertEquals("mobile", request.getValue().device());
        assertEquals("secret", request.getValue().config().get("key"));
    }

    @Test
    void rejectsClientAmountBeforeProviderCall() {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(
                Optional.of(pendingOrder(orderId, "10.00")));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.createPayment(orderId, "alipay", new BigDecimal("0.01"), "pc"));

        assertEquals(ErrorCode.BAD_REQUEST, exception.getCode());
        verifyNoInteractions(provider, paymentChannelRepository, paymentAttemptRepository);
    }

    @Test
    void disabledChannelCannotCreateANewAttempt() {
        UUID orderId = UUID.randomUUID();
        PaymentChannel channel = channel("alipay");
        channel.setEnabled(false);
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(
                Optional.of(pendingOrder(orderId, "10.00")));
        when(paymentChannelRepository.findByChannelCodeAndIsDeleted("alipay", 0))
                .thenReturn(Optional.of(channel));
        when(paymentAttemptRepository.findFirstByOrderIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(orderId), eq(PaymentStatus.PENDING), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.createPayment(orderId, "alipay", new BigDecimal("10.00"), "pc"));

        assertEquals(ErrorCode.CHANNEL_UNAVAILABLE, exception.getCode());
        verifyNoInteractions(provider);
    }

    @Test
    void reusesAnExistingAttemptEvenWhenItsChannelIsNowDisabled() {
        UUID orderId = UUID.randomUUID();
        Order order = pendingOrder(orderId, "10.00");
        PaymentAttempt existing = attempt(orderId, "merchant-existing", new PaymentNextAction.Redirect(
                "https://pay.example/existing"));
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(paymentAttemptRepository.findFirstByOrderIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(orderId), eq(PaymentStatus.PENDING), any(LocalDateTime.class)))
                .thenReturn(Optional.of(existing));

        Map<String, Object> result = service.createPayment(orderId, "alipay", new BigDecimal("10.00"), "pc");

        assertEquals(existing.getId(), result.get("payment_id"));
        assertEquals(Map.of("type", "redirect", "url", "https://pay.example/existing"),
                result.get("next_action"));
        verifyNoInteractions(paymentChannelRepository, provider);
    }

    @Test
    void adminPickupReturnsPaidResultWithoutResolvingChannelOrCallingProvider() {
        UUID orderId = UUID.randomUUID();
        Order order = new Order();
        order.setId(orderId);
        order.setOrderType(OrderType.ADMIN_PICKUP);
        order.setPaymentMethod("ADMIN_PICKUP");
        order.setActualAmount(new BigDecimal("0.00"));
        order.setTotalAmount(new BigDecimal("10.00"));
        order.setStatus(OrderStatus.PAID);
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));

        Map<String, Object> result = service.createPayment(orderId, "ADMIN_PICKUP",
                new BigDecimal("0.00"), "pc");

        assertEquals(true, result.get("paid"));
        assertEquals(PaymentStatus.PAID, result.get("status"));
        assertEquals(OrderType.ADMIN_PICKUP, result.get("order_type"));
        verifyNoInteractions(paymentChannelRepository, provider, paymentAttemptRepository);
    }

    @Test
    void repayClosesOldPendingAttemptAndCreatesASeparateAttempt() {
        UUID orderId = UUID.randomUUID();
        String lookupToken = "payment-lookup-token-123456";
        Order order = pendingOrder(orderId, "10.00");
        order.setLookupTokenHash(LookupTokens.hash(lookupToken));
        order.setUpdatedAt(LocalDateTime.now().minusSeconds(20));
        PaymentAttempt oldAttempt = attempt(orderId, "merchant-old", new PaymentNextAction.Redirect(
                "https://pay.example/old"));
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(paymentChannelRepository.findByChannelCodeAndIsDeleted("alipay", 0))
                .thenReturn(Optional.of(channel("alipay")));
        when(paymentAttemptRepository.findByOrderIdAndStatusForUpdate(orderId, PaymentStatus.PENDING))
                .thenReturn(List.of(oldAttempt));
        when(provider.create(any(PaymentProvider.PaymentRequest.class)))
                .thenReturn(new PaymentProvider.PaymentResult(
                        "trade-new", new PaymentNextAction.Redirect("https://pay.example/new")));

        Map<String, Object> result = service.repay(orderId, "pc", null, lookupToken);

        assertEquals(PaymentStatus.CLOSED, oldAttempt.getStatus());
        assertEquals(PaymentStatus.PENDING, result.get("status"));
        assertNotEquals("merchant-old", capturedAttempt().getMerchantTradeNo());
        verify(paymentAttemptRepository).save(oldAttempt);
        verify(provider).create(any(PaymentProvider.PaymentRequest.class));
    }

    private PaymentAttempt capturedAttempt() {
        ArgumentCaptor<PaymentAttempt> captor = ArgumentCaptor.forClass(PaymentAttempt.class);
        verify(paymentAttemptRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues().get(captor.getAllValues().size() - 1);
    }

    private PaymentAttempt attempt(UUID orderId, String merchantTradeNo, PaymentNextAction action) {
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setOrderId(orderId);
        attempt.setChannelId(UUID.randomUUID());
        attempt.setChannelCodeSnapshot("alipay");
        attempt.setProviderType("epay");
        attempt.setPaymentType("alipay");
        attempt.setProviderConfigSnapshot("{\"pid\":\"pid\",\"key\":\"secret\",\"api_url\":\"https://pay.example\"}");
        attempt.setMerchantTradeNo(merchantTradeNo);
        attempt.setAmount(new BigDecimal("10.00"));
        attempt.setStatus(PaymentStatus.PENDING);
        try {
            attempt.setNextAction(new ObjectMapper().writeValueAsString(action));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
        attempt.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        return attempt;
    }

    private Order pendingOrder(UUID id, String amount) {
        Order order = new Order();
        order.setId(id);
        order.setOrderNo(100001L);
        order.setStatus(OrderStatus.PENDING);
        order.setActualAmount(new BigDecimal(amount));
        order.setTotalAmount(new BigDecimal(amount));
        order.setPaymentMethod("alipay");
        order.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        return order;
    }

    private PaymentChannel channel(String paymentType) {
        PaymentChannel channel = new PaymentChannel();
        channel.setId(UUID.randomUUID());
        channel.setChannelCode(paymentType);
        channel.setChannelName(paymentType);
        channel.setProviderType("epay");
        channel.setPaymentType(paymentType);
        channel.setConfigData("{\"pid\":\"pid\",\"key\":\"secret\",\"api_url\":\"https://pay.example\"}");
        channel.setEnabled(true);
        channel.setIsDeleted(0);
        return channel;
    }
}
