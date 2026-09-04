package com.myshop.service;

import com.myshop.model.request.LoginRequest;
import com.myshop.model.request.RegisterRequest;
import com.myshop.model.response.AuthResult;
import com.myshop.model.response.CaptchaResponse;

public interface AuthService {

    CaptchaResponse generateCaptcha();

    AuthResult register(RegisterRequest request, String clientIp);

    AuthResult login(LoginRequest request, String clientIp);

    void logout();
}
