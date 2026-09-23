package com.myshop.controller;

import com.myshop.common.PageResult;
import com.myshop.model.response.AdministratorResponse;
import com.myshop.service.AdministratorService;
import com.myshop.service.OperationLogService;
import com.myshop.service.SalesChannelAdminService;
import com.myshop.saleschannel.model.SalesChannelDtos.QrLoginStartData;
import com.myshop.saleschannel.model.SalesChannelDtos.QrLoginStatusData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @MockitoBean
    private SalesChannelAdminService salesChannelAdminService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(springSecurityFilterChain)
                .build();
        when(administratorService.listAdministrators(null, null, 1, 20))
                .thenReturn(PageResult.of(java.util.List.<AdministratorResponse>of(), 1, 20, 0));
        when(operationLogService.listLogs(any(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(PageResult.of(java.util.List.of(), 1, 20, 0));
        when(salesChannelAdminService.accounts()).thenReturn(java.util.List.of());
        when(salesChannelAdminService.startQrLogin(anyString()))
                .thenReturn(new QrLoginStartData("11111111-1111-1111-1111-111111111111",
                        "data:image/png;base64,AA==", 300));
        when(salesChannelAdminService.getQrLoginStatus(anyString(), anyString()))
                .thenReturn(new QrLoginStatusData("WAITING", null, null, null));
    }

    @Test
    void applicationHasNoBootDefaultUserServiceAndRetainsItsJwtSecurityFilterChain() {
        assertTrue(webApplicationContext.getBeansOfType(InMemoryUserDetailsManager.class).isEmpty());
        assertTrue(webApplicationContext.getBeansOfType(UserDetailsService.class).isEmpty());
        assertFalse(webApplicationContext.getBeansOfType(SecurityFilterChain.class).isEmpty());
    }

    @Test
    void ablationShowsBootWouldCreateAnInMemoryUserIfTheExplicitExcludeWereAbsent() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        SecurityAutoConfiguration.class,
                        UserDetailsServiceAutoConfiguration.class))
                .withBean(SecurityFilterChain.class,
                        () -> org.mockito.Mockito.mock(SecurityFilterChain.class))
                .withPropertyValues(
                        "spring.security.user.name=ablation-only-user",
                        "spring.security.user.password=ablation-only-password")
                .run(context -> {
                    assertTrue(context.getBeansOfType(InMemoryUserDetailsManager.class).size() == 1);
                    assertTrue(context.getBeansOfType(UserDetailsService.class).size() == 1);
                });
    }

    @Test
    void unauthenticatedAdminRouteRemainsUnauthorizedAndLoginRemainsPublic() throws Exception {
        mockMvc.perform(get("/api/admin/operation-logs").contextPath("/api"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/xianyu/accounts").contextPath("/api"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/xianyu/accounts/login/qr")
                        .contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"request_id\":\"11111111-1111-1111-1111-111111111111\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/login")
                        .contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
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

        mockMvc.perform(get("/api/admin/xianyu/accounts")
                        .contextPath("/api")
                        .with(user("ordinary-admin").roles("ADMIN")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/xianyu/accounts")
                        .contextPath("/api")
                        .with(user("muchen-admin").roles("SUPER_ADMIN")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/admin/xianyu/accounts/login/qr")
                        .contextPath("/api")
                        .with(user("ordinary-admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"request_id\":\"11111111-1111-1111-1111-111111111111\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loginSessionId").value("11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.data.qrCodeDataUrl").value("data:image/png;base64,AA=="))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(300))
                .andExpect(jsonPath("$.data.login_session_id").doesNotExist())
                .andExpect(jsonPath("$.data.qr_code_data_url").doesNotExist());
        mockMvc.perform(post("/api/admin/xianyu/accounts/login/qr/11111111-1111-1111-1111-111111111111/status")
                        .contextPath("/api")
                        .with(user("ordinary-admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"request_id\":\"22222222-2222-2222-2222-222222222222\","
                                + "\"login_session_id\":\"11111111-1111-1111-1111-111111111111\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WAITING"))
                .andExpect(jsonPath("$.data.accountId").doesNotExist())
                .andExpect(jsonPath("$.data.isNewAccount").doesNotExist());
    }

    @Test
    void userCannotUseBackendAndOrdinaryAdminCannotManageAdministrators() throws Exception {
        mockMvc.perform(get("/api/admin/operation-logs")
                        .contextPath("/api")
                        .with(user("buyer").roles("USER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/xianyu/accounts")
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
