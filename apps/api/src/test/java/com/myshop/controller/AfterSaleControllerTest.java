package com.myshop.controller;

import com.myshop.context.RequestContext;
import com.myshop.service.AfterSaleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AfterSaleControllerTest {

    @Mock
    private AfterSaleService afterSaleService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AfterSaleController(afterSaleService)).build();
        RequestContext.set(new RequestContext.UserInfo(
                UUID.fromString("11111111-1111-1111-1111-111111111111"), "buyer", "USER"));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void multipartRequestForwardsTheAuthenticatedUserReasonAndAllAttachments() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID userId = RequestContext.getUserId();
        MockMultipartFile first = new MockMultipartFile(
                "attachments", "first.png", "image/png", new byte[]{1, 2, 3});
        MockMultipartFile second = new MockMultipartFile(
                "attachments", "second.jpg", "image/jpeg", new byte[]{4, 5, 6});
        when(afterSaleService.apply(eq(orderId), eq(userId), eq("凭证说明"), anyList()))
                .thenReturn(Map.of("status", "AFTER_SALE"));

        mockMvc.perform(multipart("/user/orders/{orderId}/after-sales", orderId)
                        .file(first)
                        .file(second)
                        .param("reason", "凭证说明"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("AFTER_SALE"));

        ArgumentCaptor<List<MultipartFile>> files = ArgumentCaptor.forClass(List.class);
        verify(afterSaleService).apply(eq(orderId), eq(userId), eq("凭证说明"), files.capture());
        assertEquals(List.of("first.png", "second.jpg"),
                files.getValue().stream().map(MultipartFile::getOriginalFilename).toList());
    }

    @Test
    void noAttachmentSubmissionStillReachesTheServiceWithAnEmptyList() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID userId = RequestContext.getUserId();
        when(afterSaleService.apply(eq(orderId), eq(userId), eq("没有附件的说明"), eq(List.of())))
                .thenReturn(Map.of("status", "AFTER_SALE"));

        mockMvc.perform(multipart("/user/orders/{orderId}/after-sales", orderId)
                        .param("reason", "没有附件的说明"))
                .andExpect(status().isOk());

        verify(afterSaleService).apply(orderId, userId, "没有附件的说明", List.of());
    }

    @Test
    void customerMessageEndpointForwardsContentAndOptionalAttachments() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID userId = RequestContext.getUserId();
        MockMultipartFile attachment = new MockMultipartFile(
                "attachments", "reply.webp", "image/webp", new byte[]{1, 2, 3});
        when(afterSaleService.replyFromCustomer(eq(orderId), eq(userId), eq("补充信息"), anyList()))
                .thenReturn(Map.of("status", "PENDING"));

        mockMvc.perform(multipart("/user/orders/{orderId}/after-sale/messages", orderId)
                        .file(attachment)
                        .param("content", "补充信息"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        ArgumentCaptor<List<MultipartFile>> files = ArgumentCaptor.forClass(List.class);
        verify(afterSaleService).replyFromCustomer(eq(orderId), eq(userId), eq("补充信息"), files.capture());
        assertEquals("reply.webp", files.getValue().get(0).getOriginalFilename());
    }
}
