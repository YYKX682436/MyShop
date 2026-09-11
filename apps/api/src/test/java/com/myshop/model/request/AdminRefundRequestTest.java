package com.myshop.model.request;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.myshop.constant.OrderStatus;
import com.myshop.model.response.AdminOrderRefundResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdminRefundRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void requestAcceptsOnlyConfirmationAndReasonFields() throws Exception {
        AdminRefundRequest request = objectMapper.readValue(
                "{\"confirmed\":true,\"reason\":\"客户申请整单退款\"}",
                AdminRefundRequest.class);

        assertEquals(Boolean.TRUE, request.confirmed());
        assertEquals("客户申请整单退款", request.reason());
        assertThrows(Exception.class, () -> objectMapper.readValue(
                "{\"confirmed\":true,\"reason\":\"有效原因\",\"amount\":1}",
                AdminRefundRequest.class));
    }

    @Test
    void responseUsesThePublicSnakeCaseFacts() throws Exception {
        String json = objectMapper.writeValueAsString(new AdminOrderRefundResponse(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                1000000001L,
                OrderStatus.REFUNDED,
                new BigDecimal("12.34"),
                LocalDateTime.of(2026, 9, 11, 23, 0),
                "BALANCE",
                new BigDecimal("5.66"),
                new BigDecimal("18.00")));

        org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"order_id\""));
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"refund_amount\""));
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"refunded_at\""));
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"balance_before\""));
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"balance_after\""));
    }
}
