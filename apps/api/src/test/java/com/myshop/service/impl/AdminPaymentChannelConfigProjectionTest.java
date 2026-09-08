package com.myshop.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.entity.PaymentChannel;
import com.myshop.model.response.AdminPaymentChannelResponse;
import com.myshop.payment.EpayPaymentProvider;
import com.myshop.repository.PaymentChannelRepository;
import com.myshop.service.EpayService;
import com.myshop.service.PaymentUrlService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminPaymentChannelConfigProjectionTest {

    @Test
    void undeclaredStoredFieldsAreNotReturnedAndNextSaveCanonicalizesConfig() throws Exception {
        PaymentChannelRepository repository = mock(PaymentChannelRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();
        EpayPaymentProvider provider = new EpayPaymentProvider(
                mock(EpayService.class), new PaymentUrlService("https://shop.example.com"));
        AdminPaymentChannelServiceImpl service = new AdminPaymentChannelServiceImpl(
                repository, objectMapper, List.of(provider));

        UUID id = UUID.randomUUID();
        PaymentChannel channel = new PaymentChannel();
        channel.setId(id);
        channel.setChannelCode("wxpay");
        channel.setChannelName("微信支付");
        channel.setProviderType("epay");
        channel.setPaymentType("wxpay");
        channel.setConfigData("{\"api_url\":\"https://pay.example.com\",\"pid\":\"merchant-1\","
                + "\"key\":\"secret-value\",\"create_mode\":\"api\",\"carrier_mode\":\"redirect\"}");
        when(repository.findByIsDeletedOrderBySortOrderAsc(0)).thenReturn(List.of(channel));
        when(repository.findById(id)).thenReturn(Optional.of(channel));

        AdminPaymentChannelResponse response = service.listChannels().getFirst();

        assertEquals(List.of("api_url", "pid", "key"), response.configData().keySet().stream().toList());
        assertNotEquals("secret-value", response.configData().get("key"));
        assertTrue(String.valueOf(response.configData().get("key")).contains("****"));

        service.updateChannel(id, Map.of("config_data", response.configData()));

        Map<String, Object> stored = objectMapper.readValue(channel.getConfigData(), new TypeReference<>() { });
        assertEquals(List.of("api_url", "pid", "key"), stored.keySet().stream().toList());
        assertEquals("https://pay.example.com", stored.get("api_url"));
        assertEquals("merchant-1", stored.get("pid"));
        assertEquals("secret-value", stored.get("key"));
    }
}
