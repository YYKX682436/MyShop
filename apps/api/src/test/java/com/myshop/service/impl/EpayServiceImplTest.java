package com.myshop.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.exception.BusinessException;
import com.myshop.payment.PaymentNextAction;
import com.myshop.payment.PaymentStatus;
import com.myshop.service.EpayService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EpayServiceImplTest {

    @Test
    void normalizesGatewayWithoutInventingAProtocol() {
        assertEquals("https://pay.example.com", EpayServiceImpl.normalizeGateway("https://pay.example.com/"));
        assertEquals("https://pay.example.com/submit.php", EpayServiceImpl.normalizeGateway("https://pay.example.com/submit.php/"));
        assertEquals("https://pay.example.com/mapi.php", EpayServiceImpl.normalizeGateway("https://pay.example.com/mapi.php/"));
        assertEquals("https://pay.example.com/pay/apisubmit", EpayServiceImpl.normalizeGateway("https://pay.example.com/pay/apisubmit/"));
        assertThrows(BusinessException.class, () -> EpayServiceImpl.normalizeGateway("ftp://pay.example.com"));
    }

    @Test
    void gatewayRootUsesUniversalSubmitFormWithoutServerSideFetch() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        EpayServiceImpl service = service(restTemplate);

        EpayService.EpayCreateResult result = service.createPayment(
                config("https://pay.example.com/"), "merchant-1", "wxpay", "商品", BigDecimal.TEN,
                "127.0.0.1", "pc", "https://shop.example/webhook", "https://shop.example/return");

        PaymentNextAction.FormPost action = assertInstanceOf(PaymentNextAction.FormPost.class, result.nextAction());
        assertNull(result.providerTradeNo());
        assertEquals("https://pay.example.com/submit.php", action.url());
        for (String field : List.of("pid", "type", "out_trade_no", "notify_url", "return_url", "name",
                "money", "clientip", "device", "sign", "sign_type")) {
            assertTrue(action.fields().containsKey(field), "missing form field: " + field);
        }
        assertEquals("MD5", action.fields().get("sign_type"));
        assertEquals(action.fields().get("sign"), service.buildSign("secret", action.fields()));
        verifyNoInteractions(restTemplate);
    }

    @Test
    void explicitSubmitPhpRemainsTheFormEndpoint() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        EpayService.EpayCreateResult result = service(restTemplate).createPayment(
                config("https://pay.example.com/submit.php"), "merchant-1", "alipay", "商品", BigDecimal.TEN,
                "127.0.0.1", "pc", "https://shop.example/webhook", "https://shop.example/return");

        PaymentNextAction.FormPost action = assertInstanceOf(PaymentNextAction.FormPost.class, result.nextAction());
        assertEquals("https://pay.example.com/submit.php", action.url());
        verifyNoInteractions(restTemplate);
    }

    @Test
    void mapiQrcodeIsAlwaysRawQrContentEvenWhenItIsHttp() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.postForEntity(eq("https://pay.example.com/mapi.php"), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"code\":1,\"trade_no\":\"trade-1\",\"qrcode\":\"https://pay.example.com/qr-content\"}"));

        EpayService.EpayCreateResult result = service(restTemplate).createPayment(
                config("https://pay.example.com/mapi.php"), "merchant-1", "wxpay", "商品", BigDecimal.TEN,
                "127.0.0.1", "pc", "https://shop.example/webhook", "https://shop.example/return");

        assertEquals("trade-1", result.providerTradeNo());
        assertEquals(new PaymentNextAction.QrCode("https://pay.example.com/qr-content"), result.nextAction());
    }

    @Test
    void apiQrCodeAliasIsAlsoReturnedWithoutEndpointRewriting() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.postForEntity(eq("https://pay.example.com/pay/apisubmit"), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"code\":1,\"qr_code\":\"/qr/raw-payload\"}"));

        EpayService.EpayCreateResult result = service(restTemplate).createPayment(
                config("https://pay.example.com/pay/apisubmit"), "merchant-1", "wxpay", "商品", BigDecimal.TEN,
                "127.0.0.1", "pc", "https://shop.example/webhook", "https://shop.example/return");

        assertEquals(new PaymentNextAction.QrCode("/qr/raw-payload"), result.nextAction());
    }

    @Test
    void apiSubmitPayurlIsATopLevelRedirectNeverQrContent() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.postForEntity(eq("https://pay.example.com/pay/apisubmit"), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"code\":1,\"payurl\":\"/cashier/order-1\"}"));

        EpayService.EpayCreateResult result = service(restTemplate).createPayment(
                config("https://pay.example.com/pay/apisubmit"), "merchant-1", "alipay", "商品", BigDecimal.TEN,
                "127.0.0.1", "pc", "https://shop.example/webhook", "https://shop.example/return");

        assertEquals(new PaymentNextAction.Redirect("https://pay.example.com/cashier/order-1"), result.nextAction());
    }

    @Test
    void apiSchemeUsesExplicitClientAction() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.postForEntity(eq("https://pay.example.com/mapi.php"), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"code\":1,\"urlscheme\":\"weixin://dl/business/?ticket=demo\"}"));

        EpayService.EpayCreateResult result = service(restTemplate).createPayment(
                config("https://pay.example.com/mapi.php"), "merchant-1", "wxpay", "商品", BigDecimal.TEN,
                "127.0.0.1", "pc", "https://shop.example/webhook", "https://shop.example/return");

        assertEquals(new PaymentNextAction.DeepLink("weixin://dl/business/?ticket=demo", "wechat"), result.nextAction());
    }

    @Test
    void explicitApiEndpointRejectsHtmlInsteadOfEmbeddingThePage() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.postForEntity(eq("https://pay.example.com/mapi.php"), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("<!doctype html><html><body>cashier</body></html>"));

        BusinessException exception = assertThrows(BusinessException.class, () -> service(restTemplate).createPayment(
                config("https://pay.example.com/mapi.php"), "merchant-1", "wxpay", "商品", BigDecimal.TEN,
                "127.0.0.1", "pc", "https://shop.example/webhook", "https://shop.example/return"));
        assertTrue(exception.getMessage().contains("支付网关请填写站点根地址或 submit.php"));
    }

    @Test
    void queryUsesGatewayBaseRegardlessOfCreateEndpoint() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        String queryUrl = "https://pay.example.com/api.php?act=order&pid=pid&key=secret&out_trade_no=merchant-1";
        when(restTemplate.getForEntity(eq(queryUrl), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"status\":\"TRADE_SUCCESS\",\"money\":\"10.00\",\"trade_no\":\"trade-1\"}"));

        for (String endpoint : List.of(
                "https://pay.example.com/",
                "https://pay.example.com/submit.php",
                "https://pay.example.com/mapi.php",
                "https://pay.example.com/pay/apisubmit")) {
            EpayService.OrderQueryResult result = service(restTemplate).queryOrder(
                    config(endpoint), "merchant-1");

            assertEquals(PaymentStatus.PAID, result.status(), endpoint);
            assertEquals(new BigDecimal("10.00"), result.amount(), endpoint);
            assertEquals("trade-1", result.providerTradeNo(), endpoint);
        }
        verify(restTemplate, times(4)).getForEntity(eq(queryUrl), eq(String.class));
    }

    private EpayServiceImpl service(RestTemplate restTemplate) {
        return new EpayServiceImpl(restTemplate, new ObjectMapper());
    }

    private EpayService.ChannelConfig config(String gateway) {
        return new EpayService.ChannelConfig(gateway, "pid", "secret");
    }
}
