package com.myshop.controller;

import com.myshop.common.ApiResponse;
import com.myshop.model.request.LoginRequest;
import com.myshop.model.request.RegisterRequest;
import com.myshop.model.response.AuthResponse;
import com.myshop.model.response.AuthResult;
import com.myshop.model.response.CaptchaResponse;
import com.myshop.config.ClientIpResolver;
import com.myshop.service.AuthService;
import com.myshop.service.AuthCookieService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final ClientIpResolver clientIpResolver;
    private final AuthCookieService authCookieService;

    @GetMapping("/captcha")
    public ApiResponse<CaptchaResponse> getCaptcha() {
        return ApiResponse.success(authService.generateCaptcha());
    }

    @PostMapping("/register")
    public ApiResponse<AuthResponse> register(@Valid @RequestBody RegisterRequest request,
                                              HttpServletRequest servletRequest,
                                              HttpServletResponse response) {
        AuthResult result = authService.register(request, clientIpResolver.resolve(servletRequest));
        authCookieService.writeAuthCookies(response, result.token());
        return ApiResponse.success(new AuthResponse(result.user()));
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                           HttpServletRequest servletRequest,
                                           HttpServletResponse response) {
        AuthResult result = authService.login(request, clientIpResolver.resolve(servletRequest));
        authCookieService.writeAuthCookies(response, result.token());
        return ApiResponse.success(new AuthResponse(result.user()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletResponse response) {
        authService.logout();
        authCookieService.clearAuthCookies(response);
        return ApiResponse.success();
    }
}
