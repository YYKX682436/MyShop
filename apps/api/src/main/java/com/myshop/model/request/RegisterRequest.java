package com.myshop.model.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RegisterRequest {

    @NotBlank(message = "Username is required")
    @Size(min = 3, max = 20, message = "用户名长度必须为 3 至 20 位")
    @Pattern(regexp = "^[A-Za-z0-9_-]{3,20}$", message = "用户名只能使用英文字母、数字、下划线和连字符")
    private String username;

    @NotBlank(message = "Password is required")
    @Size(min = 6, max = 72, message = "密码长度必须为 6 至 72 位")
    @Pattern(regexp = "^[!-~]{6,72}$", message = "密码只能使用 ASCII 可见字符")
    private String password;

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    @Size(max = 255, message = "Email must be at most 255 characters")
    private String email;

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @NotBlank(message = "Captcha ID is required")
    private String captchaId;

    @NotBlank(message = "Captcha is required")
    private String captcha;

    /** Registration canonicalizes surrounding whitespace but preserves case. */
    public void setUsername(String username) {
        this.username = username == null ? null : username.trim();
    }

    /** Validate the canonical-looking value while the service applies lowercase normalization. */
    public void setEmail(String email) {
        this.email = email == null ? null : email.trim();
    }

    public void setPhone(String phone) {
        this.phone = phone == null ? null : phone.trim();
    }
}
