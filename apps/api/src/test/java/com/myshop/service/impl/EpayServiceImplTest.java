package com.myshop.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.exception.BusinessException;
import com.myshop.payment.PaymentQueryUnavailableException;
import com.myshop.service.EpayService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @Test
    void reportsAnExplicitlyUnsupportedHtmlQueryEndpoint() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_HTML);
        when(restTemplate.getForEntity(
                eq("https://pay.example.com/api.php?act=order&pid=pid&key=secret&out_trade_no=order-1"),
                eq(String.class)))
                .thenReturn(new ResponseEntity<>("<!doctype html><html></html>", headers,
                        org.springframework.http.HttpStatus.OK));
        EpayServiceImpl service = new EpayServiceImpl(restTemplate, new ObjectMapper());

        assertThrows(PaymentQueryUnavailableException.class, () -> service.queryOrder(
                new EpayService.ChannelConfig("pid", "secret", "https://pay.example.com"),
                "order-1"));
    }

    @Test
    void usesUrlschemeWhenTheStandardQrFieldIsBlank() {
        EpayService.EpayResult result = createPaymentResponse(
                "{\"code\":1,\"msg\":\"success\",\"trade_no\":\"123\","
                        + "\"payurl\":\"\",\"qrcode\":\"\","
                        + "\"urlscheme\":\"weixin://dl/business/?ticket=xxx\"}");

        assertEquals("weixin://dl/business/?ticket=xxx", result.qrcodeUrl());
        assertNull(result.payUrl());
    }

    @Test
    void fallsBackToQrCodeWhenPayUrlIsBlank() {
        EpayService.EpayResult result = createPaymentResponse(
                "{\"code\":1,\"payurl\":\" \",\"qrcode\":\"weixin://qr/123\"}");

        assertEquals("weixin://qr/123", result.qrcodeUrl());
        assertNull(result.payUrl());
    }

    @Test
    void rejectsAReportedSuccessWithoutAnyPaymentCarrier() {
        BusinessException exception = assertThrows(BusinessException.class, () -> createPaymentResponse(
                "{\"code\":1,\"payurl\":\" \",\"payurl2\":\"\",\"url\":null,"
                        + "\"pay_action_url\":\"  \",\"qrcode\":\"\",\"qr_code\":\" \","
                        + "\"urlscheme\":\"\"}"));

        assertEquals("支付创建失败：网关未返回支付链接或二维码", exception.getMessage());
    }

    @Test
    void usesTheExplicitlyEnabledSubmitPageForVerifiedNoCarrierGateways() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.postForEntity(eq("https://pay.example.com/mapi.php"),
                org.mockito.ArgumentMatchers.any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(
                        "{\"code\":1,\"msg\":\"success\",\"trade_no\":\"trade-1\","
                                + "\"payurl\":\"\",\"qrcode\":\"\"}"));
        EpayServiceImpl service = new EpayServiceImpl(restTemplate, new ObjectMapper(), true);

        EpayService.EpayResult result = service.createPayment(
                new EpayService.ChannelConfig("pid", "secret", "https://pay.example.com"),
                "order-1", "wxpay", "商品", BigDecimal.TEN, "127.0.0.1", "pc",
                "https://shop.example.com/api/payments/webhook/epay",
                "https://shop.example.com/pay/order-1?orderNo=100001&method=wxpay");

        assertTrue(result.payUrl().startsWith("https://pay.example.com/submit.php?"));
        assertNull(result.qrcodeUrl());
        assertTrue(result.payUrl().contains("pid=pid"));
        assertTrue(result.payUrl().contains("out_trade_no=order-1"));
        assertTrue(result.payUrl().contains("sign_type=MD5"));
        assertFalse(result.payUrl().contains("key=secret"));
        verify(restTemplate).postForEntity(eq("https://pay.example.com/mapi.php"),
                org.mockito.ArgumentMatchers.any(), eq(String.class));
    }

    @Test
    void stillRejectsNoCarrierSuccessWithoutTradeNoWhenSubmitFallbackIsEnabled() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.postForEntity(eq("https://pay.example.com/mapi.php"),
                org.mockito.ArgumentMatchers.any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(
                        "{\"code\":1,\"msg\":\"success\",\"trade_no\":\" \","
                                + "\"payurl\":\"\",\"qrcode\":\"\"}"));
        EpayServiceImpl service = new EpayServiceImpl(restTemplate, new ObjectMapper(), true);

        BusinessException exception = assertThrows(BusinessException.class, () -> service.createPayment(
                new EpayService.ChannelConfig("pid", "secret", "https://pay.example.com"),
                "order-1", "wxpay", "商品", BigDecimal.TEN, "127.0.0.1", "pc",
                "https://shop.example.com/api/payments/webhook/epay",
                "https://shop.example.com/pay/order-1?orderNo=100001&method=wxpay"));

        assertEquals("支付创建失败：网关未返回支付链接或二维码", exception.getMessage());
    }

    @Test
    void acceptsStringSuccessCodeWithoutAcceptingFuzzySuccessValues() {
        EpayService.EpayResult result = createPaymentResponse(
                "{\"code\":\"1\",\"payurl\":\"https://pay.example.com/checkout\"}");
        assertEquals(1, result.code());

        BusinessException exception = assertThrows(BusinessException.class, () -> createPaymentResponse(
                "{\"code\":true,\"payurl\":\"https://pay.example.com/checkout\"}"));
        assertEquals("支付创建失败：", exception.getMessage());
    }

    @Test
    void supportsSecondaryPaymentAndQrCarrierNames() {
        EpayService.EpayResult payUrlFallback = createPaymentResponse(
                "{\"code\":1,\"payurl\":\"\",\"payurl2\":\"https://pay.example.com/second\"}");
        assertEquals("https://pay.example.com/second", payUrlFallback.payUrl());

        EpayService.EpayResult qrFallback = createPaymentResponse(
                "{\"code\":1,\"qrcode\":\"\",\"qr_code\":\"weixin://qr/secondary\"}");
        assertEquals("weixin://qr/secondary", qrFallback.qrcodeUrl());
    }

    @Test
    void normalizesRelativeUrlCarriersAgainstTheGatewayOriginOnly() {
        EpayService.EpayResult relative = createPaymentResponse(
                "{\"code\":1,\"qrcode\":\"/api/pay/toapp/123\"}");
        assertEquals("https://pay.example.com/api/pay/toapp/123", relative.qrcodeUrl());

        EpayService.EpayResult scheme = createPaymentResponse(
                "{\"code\":1,\"qrcode\":\"weixin://dl/business/?ticket=xxx\"}");
        assertEquals("weixin://dl/business/?ticket=xxx", scheme.qrcodeUrl());

        EpayService.EpayResult absolute = createPaymentResponse(
                "{\"code\":1,\"payurl\":\"https://other.example.com/checkout\"}");
        assertEquals("https://other.example.com/checkout", absolute.payUrl());
    }

    private EpayService.EpayResult createPaymentResponse(String responseBody) {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.postForEntity(eq("https://pay.example.com/mapi.php"),
                org.mockito.ArgumentMatchers.any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(responseBody));
        EpayServiceImpl service = new EpayServiceImpl(restTemplate, new ObjectMapper());
        return service.createPayment(
                new EpayService.ChannelConfig("pid", "secret", "https://pay.example.com/mapi.php"),
                "order-id", "wxpay", "商品", BigDecimal.TEN, "127.0.0.1", "pc",
                "https://shop.example.com/api/payments/webhook/epay",
                "https://shop.example.com/pay/order-id?orderNo=100001&method=wxpay");
    }
}
