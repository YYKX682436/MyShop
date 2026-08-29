package com.myshop.model.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** The only profile field an administrator may edit in this release. */
@Getter
@Setter
public class AdminUpdateUserProfileRequest {

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Size(max = 255, message = "邮箱长度不能超过 255 个字符")
    private String email;

    /** Let Jakarta Validation inspect the trimmed value; the service lowercases it. */
    public void setEmail(String email) {
        this.email = email == null ? null : email.trim();
    }
}
