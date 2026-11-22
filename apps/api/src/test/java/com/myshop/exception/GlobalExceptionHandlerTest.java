package com.myshop.exception;

import com.myshop.constant.ErrorCode;
import com.myshop.controller.AuthController;
import com.myshop.model.request.RegisterRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    @Test
    void registrationBeanValidationResponseIncludesStableFieldParameters() throws Exception {
        var method = AuthController.class.getMethod("register",
                RegisterRequest.class, HttpServletRequest.class, HttpServletResponse.class);
        MethodParameter parameter = new MethodParameter(method, 0);
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new RegisterRequest(), "request");
        binding.addError(new FieldError("request", "email", null, false, new String[] {"Pattern"},
                null, "Invalid email format"));

        var response = new GlobalExceptionHandler()
                .handleValidation(new MethodArgumentNotValidException(parameter, binding));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(ErrorCode.BAD_REQUEST, response.getBody().getCode());
        assertEquals("email", response.getBody().getParams().get("field"));
        assertEquals("format", response.getBody().getParams().get("validation"));
    }

    @Test
    void registrationRequiredErrorsTakePrecedenceAndCaptchaIdMapsToCaptchaRegion() throws Exception {
        var method = AuthController.class.getMethod("register",
                RegisterRequest.class, HttpServletRequest.class, HttpServletResponse.class);
        MethodParameter parameter = new MethodParameter(method, 0);
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        BeanPropertyBindingResult emailBinding = new BeanPropertyBindingResult(new RegisterRequest(), "request");
        emailBinding.addError(new FieldError("request", "email", null, false,
                new String[] {"Pattern"}, null, "Invalid email format"));
        emailBinding.addError(new FieldError("request", "email", null, false,
                new String[] {"NotBlank"}, null, "Email is required"));
        var missingEmail = handler.handleValidation(new MethodArgumentNotValidException(parameter, emailBinding));
        assertEquals("email", missingEmail.getBody().getParams().get("field"));
        assertEquals("required", missingEmail.getBody().getParams().get("validation"));

        BeanPropertyBindingResult captchaBinding = new BeanPropertyBindingResult(new RegisterRequest(), "request");
        captchaBinding.addError(new FieldError("request", "captchaId", null, false,
                new String[] {"NotBlank"}, null, "Captcha ID is required"));
        var missingCaptcha = handler.handleValidation(new MethodArgumentNotValidException(parameter, captchaBinding));
        assertEquals("captcha", missingCaptcha.getBody().getParams().get("field"));
        assertEquals("required", missingCaptcha.getBody().getParams().get("validation"));
    }
}
