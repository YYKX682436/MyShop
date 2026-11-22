package com.myshop.model.request;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RegisterRequestValidationTest {

    @Test
    void requestUsesTheSharedRegistrationRulesForAllSubmittedFields() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            RegisterRequest valid = request("buyer_name@example.test");
            assertTrue(validator.validate(valid).isEmpty());

            RegisterRequest invalidEmail = request("buyer@localhost");
            assertTrue(validator.validate(invalidEmail).stream()
                    .anyMatch(error -> "email".equals(error.getPropertyPath().toString())));

            RegisterRequest invalidPassword = request("buyer@example.com");
            invalidPassword.setPassword("abc def");
            assertTrue(validator.validate(invalidPassword).stream()
                    .anyMatch(error -> "password".equals(error.getPropertyPath().toString())));

            RegisterRequest invalidPhone = request("buyer@example.com");
            invalidPhone.setPhone("+8613800138000");
            assertTrue(validator.validate(invalidPhone).stream()
                    .anyMatch(error -> "phone".equals(error.getPropertyPath().toString())));
        }
    }

    private RegisterRequest request(String email) {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("buyer123");
        request.setEmail(email);
        request.setPhone("13800138000");
        request.setPassword("secure-123");
        request.setCaptchaId("id");
        request.setCaptcha("1234");
        return request;
    }
}
