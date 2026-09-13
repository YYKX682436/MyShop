package com.myshop.controller;

import com.myshop.model.response.AdminOrderRefundResponse;
import com.myshop.service.AdminOrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
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

    @Test
    void afterSaleRejectApiRequiresReplyAndForwardsIt() throws Exception {
        UUID orderId = UUID.randomUUID();

        mockMvc().perform(post("/admin/orders/{id}/after-sale/reject", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rejection_reason\":\"请补充凭证\"}"))
                .andExpect(status().isOk());

        verify(adminOrderService).rejectAfterSale(orderId, "请补充凭证");

        mockMvc().perform(post("/admin/orders/{id}/after-sale/reject", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rejection_reason\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void afterSaleApproveApiRequiresExplicitConfirmationAndDefaultsReclaimOff() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(adminOrderService.approveAfterSale(orderId, true, false))
                .thenReturn(response(orderId, 0));

        mockMvc().perform(post("/admin/orders/{id}/after-sale/approve", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmed\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refund_destination").value("BALANCE"));

        verify(adminOrderService).approveAfterSale(orderId, true, false);

        mockMvc().perform(post("/admin/orders/{id}/after-sale/approve", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmed\":false}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void afterSaleMessageApiForwardsContentAndAllPrivateAttachments() throws Exception {
        UUID orderId = UUID.randomUUID();
        MockMultipartFile first = new MockMultipartFile(
                "attachments", "reply.png", "image/png", new byte[]{1, 2, 3});
        when(adminOrderService.replyAfterSale(eq(orderId), eq("补充说明"), anyList()))
                .thenReturn(java.util.Map.of("status", "PENDING"));

        mockMvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/admin/orders/{id}/after-sale/messages", orderId)
                        .file(first)
                        .param("content", "补充说明"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        org.mockito.ArgumentCaptor<List<MultipartFile>> files =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(adminOrderService).replyAfterSale(eq(orderId), eq("补充说明"), files.capture());
        org.junit.jupiter.api.Assertions.assertEquals("reply.png",
                files.getValue().get(0).getOriginalFilename());
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
