package com.myshop.service;

import com.myshop.model.request.LoginRequest;
import com.myshop.model.request.RegisterRequest;
import com.myshop.model.response.AuthResult;
import com.myshop.model.response.CaptchaResponse;

public interface AuthService {

    CaptchaResponse generateCaptcha();

    AuthResult register(RegisterRequest request);

    AuthResult login(LoginRequest request);

    void logout();
}
