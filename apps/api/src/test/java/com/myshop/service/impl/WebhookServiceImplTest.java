package com.myshop.service.impl;

import com.myshop.constant.OrderStatus;
import com.myshop.entity.Order;
import com.myshop.entity.PaymentChannel;
import com.myshop.entity.WebhookEvent;
import com.myshop.payment.PaymentProvider;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.PaymentChannelRepository;
import com.myshop.repository.WebhookEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookServiceImplTest {

    @Mock private WebhookEventRepository webhookEventRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private PaymentChannelRepository paymentChannelRepository;
    @Mock private PaymentProvider provider;

    private WebhookServiceImpl service;

    @BeforeEach
    void setUp() {
        when(provider.id()).thenReturn("epay");
        service = new WebhookServiceImpl(webhookEventRepository, orderRepository,
                paymentChannelRepository, List.of(provider), new ObjectMapper());
    }

    @Test
    void rejectsBadSignatureBeforeQueryOrStateMutation() {
        UUID orderId = UUID.randomUUID();
        Order order = pendingOrder(orderId);
        stubChannelAndOrder(order);
        when(webhookEventRepository.findByEventId("epay:trade-1")).thenReturn(Optional.empty());
        when(provider.verifyCallback(any(PaymentProvider.CallbackRequest.class))).thenReturn(false);

        assertEquals("FAIL", service.processEpayCallback(callback(orderId, "trade-1", "10.00")));

        verify(provider, never()).query(any(PaymentProvider.PaymentQueryRequest.class));
        verify(orderRepository, never()).findByIdForUpdate(any(UUID.class));
        verify(webhookEventRepository, never()).save(any(WebhookEvent.class));
        assertEquals(OrderStatus.PENDING, order.getStatus());
    }

    @Test
    void rejectsAmountMismatchWithoutConsumingRetryIdentity() {
        UUID orderId = UUID.randomUUID();
        Order order = pendingOrder(orderId);
        stubChannelAndOrder(order);
        when(webhookEventRepository.findByEventId("epay:trade-2")).thenReturn(Optional.empty());
        when(provider.verifyCallback(any(PaymentProvider.CallbackRequest.class))).thenReturn(true);

        assertEquals("FAIL", service.processEpayCallback(callback(orderId, "trade-2", "9.99")));

        verify(provider, never()).query(any(PaymentProvider.PaymentQueryRequest.class));
        verify(webhookEventRepository, never()).save(any(WebhookEvent.class));
        assertEquals(OrderStatus.PENDING, order.getStatus());
    }

    @Test
    void verifiesAmountAndGatewayStatusBeforeMarkingPaid() {
        UUID orderId = UUID.randomUUID();
        Order order = pendingOrder(orderId);
        stubChannelAndOrder(order);
        when(webhookEventRepository.findByEventId("epay:trade-3"))
                .thenReturn(Optional.empty(), Optional.empty());
        when(provider.verifyCallback(any(PaymentProvider.CallbackRequest.class))).thenReturn(true);
        when(provider.query(any(PaymentProvider.PaymentQueryRequest.class)))
                .thenReturn(new PaymentProvider.PaymentQueryResult("TRADE_SUCCESS", "10.00", "trade-3"));
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(webhookEventRepository.save(any(WebhookEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertEquals("SUCCESS", service.processEpayCallback(callback(orderId, "trade-3", "10.00")));

        assertEquals(OrderStatus.PAID, order.getStatus());
        verify(orderRepository).save(order);
        verify(webhookEventRepository).save(any(WebhookEvent.class));
    }

    @Test
    void duplicateCallbackIsIdempotent() {
        UUID orderId = UUID.randomUUID();
        Order order = pendingOrder(orderId);
        stubChannelAndOrder(order);
        when(webhookEventRepository.findByEventId("epay:trade-4"))
                .thenReturn(Optional.empty(), Optional.empty(), Optional.of(new WebhookEvent()));
        when(provider.verifyCallback(any(PaymentProvider.CallbackRequest.class))).thenReturn(true);
        when(provider.query(any(PaymentProvider.PaymentQueryRequest.class)))
                .thenReturn(new PaymentProvider.PaymentQueryResult("TRADE_SUCCESS", "10.00", "trade-4"));
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(webhookEventRepository.save(any(WebhookEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, String> params = callback(orderId, "trade-4", "10.00");
        assertEquals("SUCCESS", service.processEpayCallback(params));
        assertEquals("SUCCESS", service.processEpayCallback(params));

        verify(provider).query(any(PaymentProvider.PaymentQueryRequest.class));
        verify(webhookEventRepository).save(any(WebhookEvent.class));
    }

    private void stubChannelAndOrder(Order order) {
        PaymentChannel channel = new PaymentChannel();
        channel.setChannelCode("alipay");
        channel.setProviderType("epay");
        channel.setPaymentType("alipay");
        channel.setEnabled(true);
        channel.setIsDeleted(0);
        channel.setConfigData("{\"pid\":\"pid\",\"key\":\"secret\",\"api_url\":\"https://pay.example\",\"notify_url\":\"https://shop.example/api/payments/webhook/epay\",\"return_url\":\"https://shop.example/order\"}");
        when(paymentChannelRepository.findByChannelCodeAndIsDeleted("alipay", 0))
                .thenReturn(Optional.of(channel));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
    }

    private Order pendingOrder(UUID id) {
        Order order = new Order();
        order.setId(id);
        order.setStatus(OrderStatus.PENDING);
        order.setActualAmount(new BigDecimal("10.00"));
        order.setPaymentMethod("alipay");
        return order;
    }

    private Map<String, String> callback(UUID orderId, String tradeNo, String money) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("trade_no", tradeNo);
        params.put("out_trade_no", orderId.toString());
        params.put("trade_status", "TRADE_SUCCESS");
        params.put("money", money);
        params.put("sign", "valid-signature");
        return params;
    }
}
