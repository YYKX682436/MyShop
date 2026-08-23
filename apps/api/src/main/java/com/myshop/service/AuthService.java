package com.myshop.service;

import com.myshop.model.request.LoginRequest;
import com.myshop.model.request.RegisterRequest;
import com.myshop.model.response.AuthResponse;
import com.myshop.model.response.CaptchaResponse;

public interface AuthService {

    CaptchaResponse generateCaptcha();

    AuthResponse register(RegisterRequest request);

    AuthResponse login(LoginRequest request, String sessionToken);

    void logout();
}
