package com.myshop.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.entity.PaymentChannel;
import com.myshop.constant.OrderStatus;
import com.myshop.constant.OrderType;
import com.myshop.exception.BusinessException;
import com.myshop.payment.FakePaymentProvider;
import com.myshop.model.response.AdminPaymentChannelResponse;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.PaymentChannelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class AdminPaymentChannelServiceImplTest {

    @Mock private PaymentChannelRepository repository;
    @Mock private OrderRepository orderRepository;
    private AdminPaymentChannelServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AdminPaymentChannelServiceImpl(repository, orderRepository,
                new ObjectMapper(), List.of(new FakePaymentProvider()));
        lenient().when(repository.findByChannelCodeAndIsDeleted(anyString(), eq(0))).thenReturn(Optional.empty());
        lenient().when(repository.save(any(PaymentChannel.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(orderRepository.existsByPaymentMethodAndStatusAndOrderType(anyString(), eq(OrderStatus.PENDING), eq(OrderType.NORMAL)))
                .thenReturn(false);
    }

    @Test
    void generatesCodeAndNameAndUsesTheOnlyRegisteredProvider() {
        Map<String, Object> request = request("ignored-by-server");
        request.remove("channel_code");
        request.remove("channel_name");
        request.remove("provider_type");

        service.createChannel(request);

        org.mockito.ArgumentCaptor<PaymentChannel> captor = org.mockito.ArgumentCaptor.forClass(PaymentChannel.class);
        verify(repository).save(captor.capture());
        PaymentChannel saved = captor.getValue();
        assertEquals("alipay", saved.getChannelCode());
        assertEquals("支付宝", saved.getChannelName());
        assertEquals("fakepay", saved.getProviderType());
        assertEquals(true, saved.isEnabled());
        assertEquals(0, saved.getSortOrder());
    }

    @Test
    void allowsTwoInstancesOfSameProviderAndPaymentType() {
        service.createChannel(request("fake-alipay-main"));
        service.createChannel(request("fake-alipay-backup"));

        verify(repository, times(2)).save(any(PaymentChannel.class));
    }

    @Test
    void validatesSchemaTypesAndRejectsUnknownFields() {
        Map<String, Object> valid = request("fake-alipay-types");
        valid.put("config_data", new LinkedHashMap<>(Map.of(
                "merchant", "merchant-1",
                "timeout", 30,
                "sandbox", true,
                "mode", "safe")));
        service.createChannel(valid);

        Map<String, Object> invalid = request("fake-alipay-invalid");
        invalid.put("config_data", Map.of(
                "merchant", "merchant-1",
                "timeout", 30,
                "sandbox", true,
                "mode", "safe",
                "epay_only_field", "should-not-be-accepted"));
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.createChannel(invalid));
        assertEquals(10001, exception.getCode());
    }

    @Test
    void requiresPaymentTypeForNewChannels() {
        Map<String, Object> invalid = request("fake-missing-type");
        invalid.remove("payment_type");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.createChannel(invalid));

        assertEquals(10001, exception.getCode());
        verify(repository, never()).save(any(PaymentChannel.class));
    }

    @Test
    void blocksTechnicalChangesWhileNormalPendingOrdersExist() {
        UUID id = UUID.randomUUID();
        PaymentChannel channel = existingChannel(id);
        when(repository.findById(id)).thenReturn(Optional.of(channel));
        when(orderRepository.existsByPaymentMethodAndStatusAndOrderType(
                "alipay", OrderStatus.PENDING, OrderType.NORMAL)).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.updateChannel(id, Map.of(
                        "config_data", Map.of(
                                "merchant", "changed-merchant",
                                "timeout", 30,
                                "sandbox", false,
                                "mode", "fast"))));

        assertEquals(70012, exception.getCode());
        assertEquals(org.springframework.http.HttpStatus.CONFLICT, exception.getHttpStatus());
        verify(repository, never()).save(any(PaymentChannel.class));
    }

    @Test
    void blocksDeletionWhileNormalPendingOrdersExist() {
        UUID id = UUID.randomUUID();
        PaymentChannel channel = existingChannel(id);
        when(repository.findById(id)).thenReturn(Optional.of(channel));
        when(orderRepository.existsByPaymentMethodAndStatusAndOrderType(
                "alipay", OrderStatus.PENDING, OrderType.NORMAL)).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.deleteChannel(id));

        assertEquals(70012, exception.getCode());
        assertEquals(org.springframework.http.HttpStatus.CONFLICT, exception.getHttpStatus());
        assertEquals(0, channel.getIsDeleted());
        verify(repository, never()).save(any(PaymentChannel.class));
    }

    @Test
    void allowsDisplayOnlyChangesWhileNormalPendingOrdersExist() {
        UUID id = UUID.randomUUID();
        PaymentChannel channel = existingChannel(id);
        when(repository.findById(id)).thenReturn(Optional.of(channel));

        service.updateChannel(id, Map.of(
                "channel_name", "自定义支付宝",
                "payment_hint", "扫码更快",
                "badge_text", "推荐",
                "sort_order", 4,
                "is_enabled", false));

        verify(repository).save(channel);
        assertEquals("自定义支付宝", channel.getChannelName());
        assertEquals("扫码更快", channel.getPaymentHint());
        assertEquals("推荐", channel.getBadgeText());
        assertEquals(4, channel.getSortOrder());
        assertEquals(false, channel.isEnabled());
        assertEquals("{\"merchant\":\"merchant-1\",\"timeout\":30,\"sandbox\":false,\"mode\":\"fast\"}", channel.getConfigData());
    }

    @Test
    void enforcesDisplayLengthLimits() {
        Map<String, Object> invalid = request("fake-badge-too-long");
        invalid.put("badge_text", "12345678901234567");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.createChannel(invalid));

        assertEquals(10001, exception.getCode());
    }

    @Test
    void masksSensitiveKeysInAdminReadResponsesRegardlessOfKeyCase() {
        PaymentChannel channel = existingChannel(UUID.randomUUID());
        channel.setConfigData("{\"pid\":\"743794\",\"KEY\":\"raw-secret-value\",\"api_url\":\"https://pay.example\"}");
        when(repository.findByIsDeletedOrderBySortOrderAsc(0)).thenReturn(List.of(channel));

        AdminPaymentChannelResponse response = service.listChannels().get(0);

        assertNotEquals("raw-secret-value", response.configData().get("KEY"));
        assertEquals("raw-****ue", response.configData().get("KEY"));
    }

    private Map<String, Object> request(String channelCode) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("channel_code", channelCode);
        request.put("channel_name", channelCode);
        request.put("provider_type", "fakepay");
        request.put("payment_type", "alipay");
        request.put("config_data", Map.of(
                "merchant", "merchant-1",
                "timeout", 30,
                "sandbox", false,
                "mode", "fast"));
        return request;
    }

    private PaymentChannel existingChannel(UUID id) {
        PaymentChannel channel = new PaymentChannel();
        channel.setId(id);
        channel.setChannelCode("alipay");
        channel.setChannelName("支付宝");
        channel.setProviderType("fakepay");
        channel.setPaymentType("alipay");
        channel.setConfigData("{\"merchant\":\"merchant-1\",\"timeout\":30,\"sandbox\":false,\"mode\":\"fast\"}");
        channel.setEnabled(true);
        channel.setIsDeleted(0);
        return channel;
    }
}
