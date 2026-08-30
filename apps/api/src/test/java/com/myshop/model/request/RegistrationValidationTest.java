package com.myshop.model.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistrationValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void acceptedUsernamesCoverLettersDigitsMixedCaseAndTheTwentyCharacterLimit() {
        for (String username : new String[]{"abc", "123", "abc123", "abcdefghijklmnopqrst"}) {
            assertValid(request(username, "abcdef", "13800138000"), username);
        }
    }

    @Test
    void usernamesOutsideAsciiThreeToTwentyCharactersAreRejected() {
        for (String username : new String[]{"ab", "abcdefghijklmnopqrstu", "中文", "ab c", "abc_def", "abc-def", "abc@def"}) {
            assertInvalid(request(username, "abcdef", "13800138000"), username);
        }
    }

    @Test
    void emailIsRequiredAndCappedAtTheDatabaseColumnLength() {
        RegisterRequest maxLength = request("abc", "abcdef", "13800138000");
        maxLength.setEmail("a".repeat(63) + "@" + "b".repeat(63) + "."
                + "c".repeat(63) + "." + "d".repeat(60) + ".co");
        assertValid(maxLength, "255-character email");

        RegisterRequest tooLong = request("abc", "abcdef", "13800138000");
        tooLong.setEmail("a".repeat(63) + "@" + "b".repeat(63) + "."
                + "c".repeat(63) + "." + "d".repeat(61) + ".co");
        assertInvalid(tooLong, "256-character email");
    }

    @Test
    void sixToSeventyTwoVisibleAsciiPasswordIsAcceptedWithoutComplexityRequirements() {
        for (String password : new String[]{"666666", "abcdef", "abc123", "Abc@12", "Abc@123"}) {
            assertValid(request("abc", password, "13800138000"), password);
        }
    }

    @Test
    void passwordOutsideVisibleAsciiRangeOrLengthIsRejected() {
        for (String password : new String[]{"12345", "中文密码", "abc def", "abc😀12", "a".repeat(73)}) {
            assertInvalid(request("abc", password, "13800138000"), password);
        }
    }

    @Test
    void mainlandPhoneRuleAcceptsNormalAndRepeatedDigitNumbers() {
        assertValid(request("abc", "abcdef", "13800138000"), "normal phone");
        assertValid(request("abc", "abcdef", "18888888888"), "repeated-digit phone");
    }

    @Test
    void phoneMustBeAnElevenDigitMainlandNumberWithoutCountryCode() {
        for (String phone : new String[]{"1380013800", "138001380000", "12800138000", "中文手机号", "1380013800a", "+8613800138000"}) {
            assertInvalid(request("abc", "abcdef", phone), phone);
        }
    }

    @Test
    void ordinaryUserPasswordChangeUsesTheSameSixToSeventyTwoVisibleAsciiRule() {
        ChangePasswordRequest valid = new ChangePasswordRequest();
        valid.setOldPassword("old-password");
        valid.setNewPassword("abcdef");
        assertTrue(validator.validate(valid).isEmpty());

        for (String password : new String[]{"12345", "中文密码", "abc def", "abc😀12", "a".repeat(73)}) {
            ChangePasswordRequest invalid = new ChangePasswordRequest();
            invalid.setOldPassword("old-password");
            invalid.setNewPassword(password);
            assertFalse(validator.validate(invalid).isEmpty(), password);
        }
    }

    @Test
    void adminResetPasswordKeepsItsIndependentTwelveCharacterRule() {
        AdminResetPasswordRequest valid = new AdminResetPasswordRequest();
        valid.setNewPassword("abcdefghijkl");
        assertTrue(validator.validate(valid).isEmpty());

        AdminResetPasswordRequest tooShort = new AdminResetPasswordRequest();
        tooShort.setNewPassword("abcdefghijk");
        assertFalse(validator.validate(tooShort).isEmpty());
    }

    private RegisterRequest request(String username, String password, String phone) {
        RegisterRequest request = new RegisterRequest();
        request.setUsername(username);
        request.setPassword(password);
        request.setEmail("buyer@example.com");
        request.setPhone(phone);
        request.setCaptchaId("captcha-id");
        request.setCaptcha("1234");
        return request;
    }

    private void assertValid(RegisterRequest request, String label) {
        assertTrue(validator.validate(request).isEmpty(), label);
    }

    private void assertInvalid(RegisterRequest request, String label) {
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(request);
        assertFalse(violations.isEmpty(), label);
    }
}
