package com.myshop.controller;

import com.myshop.model.response.AdminOrderRefundResponse;
import com.myshop.service.AdminOrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminOrderControllerTest {

    @Mock
    private AdminOrderService adminOrderService;

    @Test
    void refundApiForwardsTheExplicitReclaimFlagAndReturnsTheReclaimCount() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(adminOrderService.refund(orderId, "  卡密未使用  ", true, true))
                .thenReturn(response(orderId, 2));

        mockMvc().perform(post("/admin/orders/{id}/refund", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmed\":true,\"reason\":\"  卡密未使用  \","
                                + "\"reclaim_card_keys\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reclaimed_card_key_count").value(2));

        verify(adminOrderService).refund(orderId, "  卡密未使用  ", true, true);
    }

    @Test
    void refundApiDefaultsReclaimToFalseAndRejectsClientControlledMoneyFields() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(adminOrderService.refund(orderId, null, true, false))
                .thenReturn(response(orderId, 0));

        mockMvc().perform(post("/admin/orders/{id}/refund", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmed\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reclaimed_card_key_count").value(0));
        verify(adminOrderService).refund(orderId, null, true, false);

        mockMvc().perform(post("/admin/orders/{id}/refund", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmed\":true,\"amount\":1}"))
                .andExpect(status().isBadRequest());
    }

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(new AdminOrderController(adminOrderService)).build();
    }

    private AdminOrderRefundResponse response(UUID orderId, int reclaimedCount) {
        return new AdminOrderRefundResponse(orderId, 1000000001L,
                com.myshop.constant.OrderStatus.REFUNDED, new BigDecimal("1.00"),
                LocalDateTime.of(2026, 9, 12, 12, 0), "BALANCE",
                BigDecimal.ZERO, new BigDecimal("1.00"), reclaimedCount);
    }
}
