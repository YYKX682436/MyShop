package com.myshop.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.common.ApiResponse;
import com.myshop.entity.PaymentChannel;
import com.myshop.model.response.PublicPaymentChannelResponse;
import com.myshop.repository.PaymentChannelRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentChannelControllerTest {

    @Mock
    private PaymentChannelRepository paymentChannelRepository;

    @Test
    void publicResponseContainsDisplayFieldsButNeverProviderConfig() throws Exception {
        PaymentChannel channel = new PaymentChannel();
        channel.setId(UUID.randomUUID());
        channel.setChannelCode("wxpay");
        channel.setChannelName("微信支付");
        channel.setProviderType("epay");
        channel.setPaymentType("wxpay");
        channel.setPaymentHint("扫码支付");
        channel.setBadgeText("推荐");
        channel.setConfigData("{\"pid\":\"pid\",\"key\":\"raw-secret\",\"api_url\":\"https://pay.example\"}");
        channel.setEnabled(true);
        channel.setIsDeleted(0);
        when(paymentChannelRepository.findByEnabledAndIsDeletedOrderBySortOrderAsc(true, 0))
                .thenReturn(List.of(channel));

        PaymentChannelController controller = new PaymentChannelController(paymentChannelRepository);
        ApiResponse<List<PublicPaymentChannelResponse>> response = controller.listEnabledChannels();
        String json = new ObjectMapper().writeValueAsString(response.getData());

        assertEquals("wxpay", response.getData().get(0).type());
        assertEquals("微信支付", response.getData().get(0).name());
        assertEquals("扫码支付", response.getData().get(0).paymentHint());
        assertEquals("推荐", response.getData().get(0).badgeText());
        assertFalse(json.contains("raw-secret"));
        assertFalse(json.contains("config_data"));
        assertFalse(json.contains("provider_type"));
    }
}
