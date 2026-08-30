package com.myshop.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChangePasswordRequest {

    @NotBlank(message = "Current password is required")
    private String oldPassword;

    @NotBlank(message = "New password is required")
    @Size(min = 6, max = 72, message = "新密码长度必须为 6 至 72 位")
    @Pattern(regexp = "^[!-~]{6,72}$", message = "新密码只能使用 ASCII 可见字符")
    private String newPassword;
}
