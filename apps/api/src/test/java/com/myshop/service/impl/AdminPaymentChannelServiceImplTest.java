package com.myshop.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.entity.PaymentChannel;
import com.myshop.exception.BusinessException;
import com.myshop.payment.FakePaymentProvider;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminPaymentChannelServiceImplTest {

    @Mock private PaymentChannelRepository repository;
    private AdminPaymentChannelServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AdminPaymentChannelServiceImpl(repository, new ObjectMapper(), List.of(new FakePaymentProvider()));
        when(repository.findByChannelCodeAndIsDeleted(anyString(), eq(0))).thenReturn(Optional.empty());
        when(repository.save(any(PaymentChannel.class))).thenAnswer(invocation -> invocation.getArgument(0));
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
}
