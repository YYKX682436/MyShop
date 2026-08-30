package com.myshop.service.impl;

import com.myshop.constant.ErrorCode;
import com.myshop.constant.OrderStatus;
import com.myshop.constant.OrderType;
import com.myshop.entity.Order;
import com.myshop.entity.PaymentChannel;
import com.myshop.exception.BusinessException;
import com.myshop.payment.PaymentProvider;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.PaymentChannelRepository;
import com.myshop.service.EpayService;
import com.myshop.service.ManualStockService;
import com.myshop.utils.LookupTokens;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class PaymentServiceImplTest {

    @Mock private PaymentChannelRepository paymentChannelRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private PaymentProvider provider;
    @Mock private ManualStockService manualStockService;

    private PaymentServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(provider.id()).thenReturn("epay");
        lenient().when(provider.paymentTypes()).thenReturn(List.of("alipay", "wechat"));
        lenient().when(orderItemRepository.findByOrderId(any())).thenReturn(List.of());
        lenient().when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        service = new PaymentServiceImpl(paymentChannelRepository, orderRepository, orderItemRepository,
                List.of(provider), new ObjectMapper(), manualStockService);
    }

    @Test
    void createsPaymentOnceAndReusesCachedGatewayResult() {
        UUID orderId = UUID.randomUUID();
        Order order = pendingOrder(orderId, "10.00");
        PaymentChannel channel = channel("alipay");
        when(paymentChannelRepository.findByChannelCodeAndIsDeleted("alipay", 0))
                .thenReturn(Optional.of(channel));
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(provider.create(any(PaymentProvider.PaymentRequest.class)))
                .thenReturn(new PaymentProvider.PaymentResult("trade-1", "https://pay/1", "qr-1"));

        Map<String, Object> first = service.createPayment(orderId, "alipay", new BigDecimal("10.00"), "pc");
        Map<String, Object> second = service.createPayment(orderId, "alipay", new BigDecimal("10.00"), "pc");

        assertEquals("https://pay/1", first.get("pay_url"));
        assertEquals("https://pay/1", second.get("pay_url"));
        assertEquals("trade-1", order.getEpayTradeNo());
        verify(provider).create(any(PaymentProvider.PaymentRequest.class));
    }

    @Test
    void rejectsClientAmountInsteadOfTrustingIt() {
        UUID orderId = UUID.randomUUID();
        when(paymentChannelRepository.findByChannelCodeAndIsDeleted("alipay", 0))
                .thenReturn(Optional.of(channel("alipay")));
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(pendingOrder(orderId, "10.00")));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.createPayment(orderId, "alipay", new BigDecimal("0.01"), "pc"));

        assertEquals(ErrorCode.BAD_REQUEST, exception.getCode());
        verify(provider, never()).create(any(PaymentProvider.PaymentRequest.class));
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
        assertEquals(OrderType.ADMIN_PICKUP, result.get("order_type"));
        verifyNoInteractions(paymentChannelRepository, provider);
    }

    @Test
    void repayRejectsAdminPickupBeforeAnyExternalPayment() {
        UUID orderId = UUID.randomUUID();
        String lookupToken = "admin-pickup-lookup-token";
        Order order = new Order();
        order.setId(orderId);
        order.setOrderType(OrderType.ADMIN_PICKUP);
        order.setPaymentMethod("ADMIN_PICKUP");
        order.setActualAmount(BigDecimal.ZERO.setScale(2));
        order.setStatus(OrderStatus.PAID);
        order.setLookupTokenHash(LookupTokens.hash(lookupToken));
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.repay(orderId, "pc", null, lookupToken));

        assertEquals(ErrorCode.BAD_REQUEST, exception.getCode());
        verifyNoInteractions(paymentChannelRepository, provider);
    }

    @Test
    void repayRequiresOwnerOrGuestLookupToken() {
        UUID orderId = UUID.randomUUID();
        String lookupToken = "payment-lookup-token-123456";
        Order order = pendingOrder(orderId, "10.00");
        order.setLookupTokenHash(LookupTokens.hash(lookupToken));
        order.setUpdatedAt(LocalDateTime.now().minusSeconds(20));
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));

        BusinessException forbidden = assertThrows(BusinessException.class,
                () -> service.repay(orderId, "pc", null, "wrong-token"));
        assertEquals(ErrorCode.FORBIDDEN, forbidden.getCode());

        when(paymentChannelRepository.findByChannelCodeAndIsDeleted("alipay", 0))
                .thenReturn(Optional.of(channel("alipay")));
        when(provider.create(any(PaymentProvider.PaymentRequest.class)))
                .thenReturn(new PaymentProvider.PaymentResult("trade-2", "https://pay/2", null));

        Map<String, Object> result = service.repay(orderId, "pc", null, lookupToken);
        assertEquals("https://pay/2", result.get("pay_url"));
        verify(provider).create(any(PaymentProvider.PaymentRequest.class));
    }

    private Order pendingOrder(UUID id, String amount) {
        Order order = new Order();
        order.setId(id);
        order.setStatus(OrderStatus.PENDING);
        order.setActualAmount(new BigDecimal(amount));
        order.setTotalAmount(new BigDecimal(amount));
        order.setPaymentMethod("alipay");
        order.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        return order;
    }

    private PaymentChannel channel(String paymentType) {
        PaymentChannel channel = new PaymentChannel();
        channel.setChannelCode(paymentType);
        channel.setChannelName(paymentType);
        channel.setProviderType("epay");
        channel.setPaymentType(paymentType);
        channel.setConfigData("{}");
        channel.setEnabled(true);
        channel.setIsDeleted(0);
        return channel;
    }
}
