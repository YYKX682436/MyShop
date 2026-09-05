package com.myshop.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.exception.BusinessException;
import com.myshop.service.EpayService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EpayServiceImplTest {

    @Test
    void appendsMapiPhpToAGatewayRootExactlyOnce() {
        assertEquals("https://pay.example.com/mapi.php",
                EpayServiceImpl.normalizeApiUrl("https://pay.example.com"));
        assertEquals("https://pay.example.com/mapi.php",
                EpayServiceImpl.normalizeApiUrl("https://pay.example.com/"));
        assertEquals("https://pay.example.com/mapi.php",
                EpayServiceImpl.normalizeApiUrl("https://pay.example.com/mapi.php"));
        assertEquals("https://pay.example.com/mapi.php",
                EpayServiceImpl.normalizeApiUrl("https://pay.example.com/mapi.php/mapi.php"));
        assertEquals("https://pay.example.com/gateway/mapi.php",
                EpayServiceImpl.normalizeApiUrl("https://pay.example.com/gateway/mapi.php/"));
        assertEquals("https://pay.example.com/gateway/mapi.php",
                EpayServiceImpl.normalizeApiUrl("https://pay.example.com/gateway"));
    }

    @Test
    void rejectsNonHttpApiUrls() {
        assertThrows(BusinessException.class,
                () -> EpayServiceImpl.normalizeApiUrl("ftp://pay.example.com"));
        assertThrows(BusinessException.class,
                () -> EpayServiceImpl.normalizeApiUrl("https://pay.example.com/mapi.php?x=1"));
    }

    @Test
    void createsAgainstTheCanonicalMapiEndpoint() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.postForEntity(eq("https://pay.example.com/mapi.php"),
                org.mockito.ArgumentMatchers.any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"code\":1,\"trade_no\":\"trade-1\",\"payurl\":\"https://pay.example/pay\"}"));
        EpayServiceImpl service = new EpayServiceImpl(restTemplate, new ObjectMapper());

        service.createPayment(new EpayService.ChannelConfig(
                        "pid", "secret", "https://pay.example.com/mapi.php"),
                "order-1", "alipay", "商品", BigDecimal.TEN, "127.0.0.1", "pc",
                "https://shop.example.com/api/payments/webhook/epay",
                "https://shop.example.com/pay/order-1?orderNo=100001&method=alipay");

        verify(restTemplate).postForEntity(eq("https://pay.example.com/mapi.php"),
                org.mockito.ArgumentMatchers.any(), eq(String.class));
    }

    @Test
    void keepsTheExistingApiPhpOrderQueryEndpointAtTheGatewayRoot() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.getForEntity(eq("https://pay.example.com/api.php?act=order&pid=pid&key=secret&out_trade_no=order-1"), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"status\":\"TRADE_SUCCESS\",\"money\":\"10.00\",\"trade_no\":\"trade-1\"}"));
        EpayServiceImpl service = new EpayServiceImpl(restTemplate, new ObjectMapper());

        EpayService.OrderQueryResult result = service.queryOrder(
                new EpayService.ChannelConfig("pid", "secret", "https://pay.example.com/mapi.php"),
                "order-1");

        assertEquals("TRADE_SUCCESS", result.tradeStatus());
        verify(restTemplate).getForEntity(
                eq("https://pay.example.com/api.php?act=order&pid=pid&key=secret&out_trade_no=order-1"),
                eq(String.class));
    }
}
