package com.myshop.controller;

import com.myshop.config.ClientIpResolver;
import com.myshop.model.request.LoginRequest;
import com.myshop.model.request.RegisterRequest;
import com.myshop.model.response.AuthResult;
import com.myshop.model.response.UserProfileResponse;
import com.myshop.service.AuthService;
import com.myshop.service.AuthCookieService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private AuthService authService;
    @Mock
    private ClientIpResolver clientIpResolver;
    @Mock
    private AuthCookieService authCookieService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(authService, clientIpResolver, authCookieService)).build();
    }

    @Test
    void loginUsesTheSharedControllerIpResolverAndPreservesEmailResponse() throws Exception {
        UserProfileResponse profile = new UserProfileResponse();
        profile.setEmail("buyer@example.com");
        when(clientIpResolver.resolve(any(HttpServletRequest.class))).thenReturn("2001:4860:4860::8888");
        when(authService.login(any(LoginRequest.class), eq("2001:4860:4860::8888")))
                .thenReturn(new AuthResult("token", profile));

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"buyer@example.com\",\"password\":\"secret1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.email").value("buyer@example.com"));

        verify(authService).login(any(LoginRequest.class), eq("2001:4860:4860::8888"));
    }

    @Test
    void registrationAlsoUsesTheSharedControllerIpResolver() throws Exception {
        when(clientIpResolver.resolve(any(HttpServletRequest.class))).thenReturn("203.0.113.10");
        when(authService.register(any(RegisterRequest.class), eq("203.0.113.10")))
                .thenReturn(new AuthResult("token", new UserProfileResponse()));

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"buyer\",\"password\":\"secret1\","
                                + "\"email\":\"buyer@example.com\",\"phone\":\"13800138000\","
                                + "\"captchaId\":\"id\",\"captcha\":\"1234\"}"))
                .andExpect(status().isOk());

        verify(authService).register(any(RegisterRequest.class), eq("203.0.113.10"));
    }
}
