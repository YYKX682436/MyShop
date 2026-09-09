package com.myshop.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.constant.OrderStatus;
import com.myshop.constant.OrderPaymentMethod;
import com.myshop.constant.OrderType;
import com.myshop.entity.Order;
import com.myshop.entity.PaymentAttempt;
import com.myshop.entity.WebhookEvent;
import com.myshop.payment.PaymentQueryUnavailableException;
import com.myshop.payment.PaymentProvider;
import com.myshop.payment.PaymentStatus;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.PaymentAttemptRepository;
import com.myshop.repository.WebhookEventRepository;
import com.myshop.service.OrderPaymentCompletionService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookServiceImplTest {

    @Mock private WebhookEventRepository webhookEventRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private PaymentAttemptRepository paymentAttemptRepository;
    @Mock private PaymentProvider provider;
    @Mock private OrderPaymentCompletionService orderPaymentCompletionService;

    private WebhookServiceImpl service;

    @BeforeEach
    void setUp() {
        when(provider.id()).thenReturn("epay");
        lenient().when(provider.successAcknowledgement())
                .thenReturn(PaymentProvider.WebhookAcknowledgement.success());
        lenient().when(provider.failureAcknowledgement())
                .thenReturn(PaymentProvider.WebhookAcknowledgement.failure());
        ObjectMapper objectMapper = new ObjectMapper();
        PaymentConfirmationTransactionService confirmationService = new PaymentConfirmationTransactionService(
                webhookEventRepository, orderRepository, paymentAttemptRepository,
                orderPaymentCompletionService, objectMapper);
        service = new WebhookServiceImpl(webhookEventRepository, paymentAttemptRepository,
                List.of(provider), objectMapper, confirmationService);
    }

    @Test
    void rejectsBadSignatureBeforeQueryOrStateMutation() {
        PaymentAttempt attempt = pendingAttempt("merchant-1", "trade-1");
        stubAttempt(attempt);
        when(provider.extractMerchantTradeNo(any(PaymentProvider.WebhookRequest.class)))
                .thenReturn("merchant-1");
        when(provider.verifyAndParseCallback(any(PaymentProvider.WebhookRequest.class), anyMap()))
                .thenReturn(null);

        PaymentProvider.WebhookAcknowledgement acknowledgement = service.handleWebhook(
                "epay", request("merchant-1", "trade-1"));

        assertEquals("FAIL", acknowledgement.body());
        verify(provider, never()).query(any(PaymentProvider.PaymentQueryRequest.class));
        verify(orderRepository, never()).findByIdForUpdate(any(UUID.class));
        verify(webhookEventRepository, never()).save(any(WebhookEvent.class));
    }

    @Test
    void rejectsAWebhookWhenThePathProviderDoesNotMatchTheAttemptSnapshot() {
        PaymentAttempt attempt = pendingAttempt("merchant-provider-mismatch", "trade-provider-mismatch");
        attempt.setProviderType("another-provider");
        stubAttempt(attempt);
        when(provider.extractMerchantTradeNo(any(PaymentProvider.WebhookRequest.class)))
                .thenReturn("merchant-provider-mismatch");

        PaymentProvider.WebhookAcknowledgement acknowledgement = service.handleWebhook(
                "epay", request("merchant-provider-mismatch", "trade-provider-mismatch"));

        assertEquals("FAIL", acknowledgement.body());
        verify(provider, never()).verifyAndParseCallback(any(PaymentProvider.WebhookRequest.class), anyMap());
        verify(webhookEventRepository, never()).save(any(WebhookEvent.class));
    }

    @Test
    void rejectsAmountMismatchWithoutConsumingEventIdentity() {
        PaymentAttempt attempt = pendingAttempt("merchant-2", "trade-2");
        stubAttempt(attempt);
        when(provider.extractMerchantTradeNo(any(PaymentProvider.WebhookRequest.class)))
                .thenReturn("merchant-2");
        when(provider.verifyAndParseCallback(any(PaymentProvider.WebhookRequest.class), anyMap()))
                .thenReturn(callback("event-2", "merchant-2", "trade-2", "9.99"));

        PaymentProvider.WebhookAcknowledgement acknowledgement = service.handleWebhook(
                "epay", request("merchant-2", "trade-2"));

        assertEquals("FAIL", acknowledgement.body());
        verify(webhookEventRepository, never()).save(any(WebhookEvent.class));
        verify(orderRepository, never()).findByIdForUpdate(any(UUID.class));
    }

    @Test
    void verifiesQueryAndConfirmsAttemptAndOrderUsingTheSnapshot() {
        PaymentAttempt attempt = pendingAttempt("merchant-3", "trade-3");
        Order order = pendingOrder(attempt.getOrderId());
        stubAttempt(attempt);
        when(provider.extractMerchantTradeNo(any(PaymentProvider.WebhookRequest.class)))
                .thenReturn("merchant-3");
        PaymentProvider.CallbackResult callback = callback("event-3", "merchant-3", "trade-3", "10.00");
        when(provider.verifyAndParseCallback(any(PaymentProvider.WebhookRequest.class), anyMap()))
                .thenReturn(callback);
        when(provider.supportsServerSideQuery()).thenReturn(true);
        when(provider.query(any(PaymentProvider.PaymentQueryRequest.class)))
                .thenReturn(new PaymentProvider.PaymentQueryResult(
                        PaymentStatus.PAID, new BigDecimal("10.00"), "trade-3"));
        when(paymentAttemptRepository.findById(attempt.getId())).thenReturn(Optional.of(attempt));
        when(paymentAttemptRepository.findByIdForUpdate(attempt.getId())).thenReturn(Optional.of(attempt));
        when(orderRepository.findByIdForUpdate(attempt.getOrderId())).thenReturn(Optional.of(order));
        when(webhookEventRepository.findByEventId(anyString())).thenReturn(Optional.empty());
        when(webhookEventRepository.save(any(WebhookEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentAttemptRepository.save(any(PaymentAttempt.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(orderPaymentCompletionService.completeFirstPayment(order)).thenAnswer(invocation -> {
            order.setStatus(OrderStatus.PAID);
            orderRepository.save(order);
            return true;
        });

        PaymentProvider.WebhookAcknowledgement acknowledgement = service.handleWebhook(
                "epay", request("merchant-3", "trade-3"));

        assertEquals("SUCCESS", acknowledgement.body());
        assertEquals(PaymentStatus.PAID, attempt.getStatus());
        assertEquals(OrderStatus.PAID, order.getStatus());
        verify(provider).query(new PaymentProvider.PaymentQueryRequest(
                "merchant-3", Map.of("pid", "pid", "key", "secret", "api_url", "https://pay.example")));
        verify(paymentAttemptRepository).save(attempt);
        verify(orderRepository).save(order);
        ArgumentCaptor<WebhookEvent> eventCaptor = ArgumentCaptor.forClass(WebhookEvent.class);
        verify(webhookEventRepository).save(eventCaptor.capture());
        WebhookEvent event = eventCaptor.getValue();
        assertEquals("old-channel", event.getChannelCode());
        assertEquals(attempt.getId(), event.getPaymentAttemptId());
        assertFalse(event.getPayload().contains("secret"));
        assertFalse(event.getPayload().contains("provider_config_snapshot"));
        verify(orderPaymentCompletionService).completeFirstPayment(order);
    }

    @Test
    void deletedOrDisabledLiveChannelsDoNotPreventSnapshotCallbackConfirmation() {
        PaymentAttempt attempt = pendingAttempt("merchant-history", "trade-history");
        Order order = pendingOrder(attempt.getOrderId());
        attempt.setChannelCodeSnapshot("history-channel");
        stubSuccessfulConfirmation(attempt, order);

        PaymentProvider.WebhookAcknowledgement acknowledgement = service.handleWebhook(
                "epay", request("merchant-history", "trade-history"));

        assertEquals("SUCCESS", acknowledgement.body());
        assertEquals(PaymentStatus.PAID, attempt.getStatus());
    }

    @Test
    void allowsSignedPaidCallbackWhenQueryEndpointIsKnownUnavailable() {
        PaymentAttempt attempt = pendingAttempt("merchant-4", "trade-4");
        Order order = pendingOrder(attempt.getOrderId());
        stubSuccessfulConfirmation(attempt, order);
        when(provider.supportsServerSideQuery()).thenReturn(true);
        when(provider.allowSignedCallbackWhenQueryUnavailable()).thenReturn(true);
        when(provider.query(any(PaymentProvider.PaymentQueryRequest.class)))
                .thenThrow(new PaymentQueryUnavailableException("unavailable"));

        PaymentProvider.WebhookAcknowledgement acknowledgement = service.handleWebhook(
                "epay", request("merchant-4", "trade-4"));

        assertEquals("SUCCESS", acknowledgement.body());
        assertEquals(PaymentStatus.PAID, attempt.getStatus());
        verify(provider).query(any(PaymentProvider.PaymentQueryRequest.class));
    }

    @Test
    void duplicateEventIsIdempotentAndDoesNotQueryOrMutate() {
        PaymentAttempt attempt = pendingAttempt("merchant-5", "trade-5");
        stubAttempt(attempt);
        when(provider.extractMerchantTradeNo(any(PaymentProvider.WebhookRequest.class)))
                .thenReturn("merchant-5");
        when(provider.verifyAndParseCallback(any(PaymentProvider.WebhookRequest.class), anyMap()))
                .thenReturn(callback("event-5", "merchant-5", "trade-5", "10.00"));
        when(webhookEventRepository.findByEventId("event-5"))
                .thenReturn(Optional.of(new WebhookEvent()));

        PaymentProvider.WebhookAcknowledgement acknowledgement = service.handleWebhook(
                "epay", request("merchant-5", "trade-5"));

        assertEquals("SUCCESS", acknowledgement.body());
        verify(provider, never()).query(any(PaymentProvider.PaymentQueryRequest.class));
        verify(paymentAttemptRepository, never()).save(any(PaymentAttempt.class));
        verify(orderRepository, never()).findByIdForUpdate(any(UUID.class));
    }

    @Test
    void closedAttemptRejectsLatePaidCallback() {
        PaymentAttempt attempt = pendingAttempt("merchant-closed", "trade-closed");
        attempt.setStatus(PaymentStatus.CLOSED);
        stubAttempt(attempt);
        when(provider.extractMerchantTradeNo(any(PaymentProvider.WebhookRequest.class)))
                .thenReturn("merchant-closed");
        when(provider.verifyAndParseCallback(any(PaymentProvider.WebhookRequest.class), anyMap()))
                .thenReturn(callback("event-closed", "merchant-closed", "trade-closed", "10.00"));
        when(webhookEventRepository.findByEventId(anyString())).thenReturn(Optional.empty());

        PaymentProvider.WebhookAcknowledgement acknowledgement = service.handleWebhook(
                "epay", request("merchant-closed", "trade-closed"));

        assertEquals("FAIL", acknowledgement.body());
        verify(provider, never()).query(any(PaymentProvider.PaymentQueryRequest.class));
    }

    @Test
    void expiredPendingAttemptCannotBeConfirmed() {
        PaymentAttempt attempt = pendingAttempt("merchant-expired", "trade-expired");
        attempt.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        stubAttempt(attempt);
        when(provider.extractMerchantTradeNo(any(PaymentProvider.WebhookRequest.class)))
                .thenReturn("merchant-expired");
        when(provider.verifyAndParseCallback(any(PaymentProvider.WebhookRequest.class), anyMap()))
                .thenReturn(callback("event-expired", "merchant-expired", "trade-expired", "10.00"));

        PaymentProvider.WebhookAcknowledgement acknowledgement = service.handleWebhook(
                "epay", request("merchant-expired", "trade-expired"));

        assertEquals("FAIL", acknowledgement.body());
        verify(orderRepository, never()).findByIdForUpdate(any(UUID.class));
        verify(paymentAttemptRepository, never()).save(any(PaymentAttempt.class));
    }

    private void stubSuccessfulConfirmation(PaymentAttempt attempt, Order order) {
        stubAttempt(attempt);
        when(provider.extractMerchantTradeNo(any(PaymentProvider.WebhookRequest.class)))
                .thenReturn(attempt.getMerchantTradeNo());
        when(provider.verifyAndParseCallback(any(PaymentProvider.WebhookRequest.class), anyMap()))
                .thenReturn(callback("event-" + attempt.getMerchantTradeNo(),
                        attempt.getMerchantTradeNo(), attempt.getProviderTradeNo(), "10.00"));
        when(webhookEventRepository.findByEventId(anyString())).thenReturn(Optional.empty());
        when(paymentAttemptRepository.findById(attempt.getId())).thenReturn(Optional.of(attempt));
        when(paymentAttemptRepository.findByIdForUpdate(attempt.getId())).thenReturn(Optional.of(attempt));
        when(orderRepository.findByIdForUpdate(attempt.getOrderId())).thenReturn(Optional.of(order));
        when(webhookEventRepository.save(any(WebhookEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentAttemptRepository.save(any(PaymentAttempt.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void stubAttempt(PaymentAttempt attempt) {
        when(paymentAttemptRepository.findByMerchantTradeNo(attempt.getMerchantTradeNo()))
                .thenReturn(Optional.of(attempt));
    }

    private PaymentProvider.CallbackResult callback(String eventId, String merchant,
                                                    String providerTradeNo, String amount) {
        return new PaymentProvider.CallbackResult(eventId, merchant, providerTradeNo,
                PaymentStatus.PAID, new BigDecimal(amount));
    }

    private PaymentProvider.WebhookRequest request(String merchant, String providerTradeNo) {
        return new PaymentProvider.WebhookRequest("POST", Map.of(), Map.of(), Map.of(
                "out_trade_no", List.of(merchant), "trade_no", List.of(providerTradeNo)), "raw-body");
    }

    private PaymentAttempt pendingAttempt(String merchantTradeNo, String providerTradeNo) {
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setOrderId(UUID.randomUUID());
        attempt.setChannelId(UUID.randomUUID());
        attempt.setChannelCodeSnapshot("old-channel");
        attempt.setProviderType("epay");
        attempt.setPaymentType("alipay");
        attempt.setProviderConfigSnapshot(
                "{\"pid\":\"pid\",\"key\":\"secret\",\"api_url\":\"https://pay.example\"}");
        attempt.setMerchantTradeNo(merchantTradeNo);
        attempt.setProviderTradeNo(providerTradeNo);
        attempt.setAmount(new BigDecimal("10.00"));
        attempt.setStatus(PaymentStatus.PENDING);
        attempt.setNextAction("{\"type\":\"redirect\",\"url\":\"https://pay.example\"}");
        attempt.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        return attempt;
    }

    private Order pendingOrder(UUID orderId) {
        Order order = new Order();
        order.setId(orderId);
        order.setStatus(OrderStatus.PENDING);
        order.setOrderType(OrderType.NORMAL);
        order.setPaymentMethod(OrderPaymentMethod.ONLINE);
        order.setPaymentChannelCode("old-channel");
        order.setActualAmount(new BigDecimal("10.00"));
        return order;
    }

}
