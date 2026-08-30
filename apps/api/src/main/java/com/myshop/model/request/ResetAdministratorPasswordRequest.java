package com.myshop.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ResetAdministratorPasswordRequest {

    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, max = 72, message = "新密码长度必须为 6 至 72 个字符")
    @Pattern(regexp = "^[!-~]{6,72}$", message = "新密码只能使用 ASCII 可见字符")
    private String newPassword;
}
