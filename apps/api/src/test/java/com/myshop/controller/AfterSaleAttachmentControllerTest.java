package com.myshop.controller;

import com.myshop.service.AfterSaleAttachmentAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AfterSaleAttachmentControllerTest {

    @Mock
    private AfterSaleAttachmentAccessService accessService;

    @Test
    void streamsTheDatabaseAuthorizedAttachmentInlineWithoutExposingAStorageKey() throws Exception {
        UUID attachmentId = UUID.randomUUID();
        byte[] bytes = "private evidence".getBytes(StandardCharsets.UTF_8);
        when(accessService.open(attachmentId)).thenReturn(
                new AfterSaleAttachmentAccessService.AttachmentDownload(
                        new ByteArrayResource(bytes), "image/png", bytes.length, "证据.png"));

        MockMvcBuilders.standaloneSetup(new AfterSaleAttachmentController(accessService))
                .build()
                .perform(get("/after-sale-attachments/{id}", attachmentId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(bytes))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("inline")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("storage"))));

        verify(accessService).open(eq(attachmentId));
    }
}
