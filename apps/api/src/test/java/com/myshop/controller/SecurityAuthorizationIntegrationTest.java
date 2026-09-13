package com.myshop.controller;

import com.myshop.common.PageResult;
import com.myshop.model.response.AdministratorResponse;
import com.myshop.service.AdministratorService;
import com.myshop.service.OperationLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class SecurityAuthorizationIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    private MockMvc mockMvc;

    @MockitoBean
    private AdministratorService administratorService;

    @MockitoBean
    private OperationLogService operationLogService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(springSecurityFilterChain)
                .build();
        when(administratorService.listAdministrators(null, null, 1, 20))
                .thenReturn(PageResult.of(java.util.List.<AdministratorResponse>of(), 1, 20, 0));
        when(operationLogService.listLogs(any(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(PageResult.of(java.util.List.of(), 1, 20, 0));
    }

    @Test
    void bothAdminRolesCanUseTheirAllowedBackendBoundaries() throws Exception {
        mockMvc.perform(get("/api/admin/operation-logs")
                        .contextPath("/api")
                        .with(user("ordinary-admin").roles("ADMIN")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/administrators")
                        .contextPath("/api")
                        .with(user("muchen-admin").roles("SUPER_ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void userCannotUseBackendAndOrdinaryAdminCannotManageAdministrators() throws Exception {
        mockMvc.perform(get("/api/admin/operation-logs")
                        .contextPath("/api")
                        .with(user("buyer").roles("USER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/administrators")
                        .contextPath("/api")
                        .with(user("ordinary-admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void avatarUploadRequiresLoginAndAdminImageUploadRemainsAdminOnly() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/user/avatar")
                        .contextPath("/api")
                        .file(file))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(multipart("/api/upload/image")
                        .contextPath("/api")
                        .file(file)
                .with(user("buyer").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void privateAfterSaleAttachmentReadRequiresAuthenticationBeforeDatabaseLookup() throws Exception {
        mockMvc.perform(get("/api/after-sale-attachments/11111111-1111-1111-1111-111111111111")
                        .contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }
}
