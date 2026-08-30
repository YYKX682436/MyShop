package com.myshop.model.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** Input for creating an ordinary ADMIN account. */
@Getter
@Setter
public class CreateAdministratorRequest {

    @NotBlank(message = "管理员用户名不能为空")
    @Size(min = 3, max = 20, message = "管理员用户名长度必须为 3 至 20 位")
    @Pattern(regexp = "^[A-Za-z0-9_-]{3,20}$", message = "管理员用户名只能使用英文字母、数字、下划线和连字符")
    private String username;

    @NotBlank(message = "管理员密码不能为空")
    @Size(min = 6, max = 72, message = "管理员密码长度必须为 6 至 72 位")
    @Pattern(regexp = "^[!-~]{6,72}$", message = "管理员密码只能使用 ASCII 可见字符")
    private String password;

    @Email(message = "邮箱格式不正确")
    @Size(max = 255, message = "邮箱长度不能超过 255 个字符")
    private String email;

    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    public void setUsername(String username) {
        this.username = username == null ? null : username.trim();
    }

    public void setEmail(String email) {
        this.email = email == null ? null : email.trim();
    }

    public void setPhone(String phone) {
        this.phone = phone == null ? null : phone.trim();
    }
}
