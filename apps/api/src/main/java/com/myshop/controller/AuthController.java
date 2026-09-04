package com.myshop.controller;

import com.myshop.common.ApiResponse;
import com.myshop.model.request.LoginRequest;
import com.myshop.model.request.RegisterRequest;
import com.myshop.model.response.AuthResponse;
import com.myshop.model.response.AuthResult;
import com.myshop.model.response.CaptchaResponse;
import com.myshop.config.ClientIpResolver;
import com.myshop.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import com.myshop.utils.LookupTokens;
import java.time.Duration;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final ClientIpResolver clientIpResolver;

    @Value("${security.cookie-secure:false}")
    private boolean cookieSecure;

    @GetMapping("/captcha")
    public ApiResponse<CaptchaResponse> getCaptcha() {
        return ApiResponse.success(authService.generateCaptcha());
    }

    @PostMapping("/register")
    public ApiResponse<AuthResponse> register(@Valid @RequestBody RegisterRequest request,
                                              HttpServletRequest servletRequest,
                                              HttpServletResponse response) {
        AuthResult result = authService.register(request, clientIpResolver.resolve(servletRequest));
        writeAuthCookies(response, result.token());
        return ApiResponse.success(new AuthResponse(result.user()));
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                           HttpServletRequest servletRequest,
                                           HttpServletResponse response) {
        AuthResult result = authService.login(request, clientIpResolver.resolve(servletRequest));
        writeAuthCookies(response, result.token());
        return ApiResponse.success(new AuthResponse(result.user()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletResponse response) {
        authService.logout();
        response.addHeader("Set-Cookie", ResponseCookie.from("myshop_access_token", "")
                .httpOnly(true).secure(cookieSecure).sameSite("Lax").path("/").maxAge(Duration.ZERO).build().toString());
        response.addHeader("Set-Cookie", ResponseCookie.from("myshop_csrf", "")
                .secure(cookieSecure).sameSite("Lax").path("/").maxAge(Duration.ZERO).build().toString());
        return ApiResponse.success();
    }

    private void writeAuthCookies(HttpServletResponse response, String token) {
        response.addHeader("Set-Cookie", ResponseCookie.from("myshop_access_token", token)
                .httpOnly(true).secure(cookieSecure).sameSite("Lax").path("/").maxAge(Duration.ofHours(24)).build().toString());
        response.addHeader("Set-Cookie", ResponseCookie.from("myshop_csrf", LookupTokens.issue().plaintext())
                .secure(cookieSecure).sameSite("Lax").path("/").maxAge(Duration.ofHours(24)).build().toString());
    }
}
