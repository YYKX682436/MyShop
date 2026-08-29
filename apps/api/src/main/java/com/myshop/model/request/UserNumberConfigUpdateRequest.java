package com.myshop.model.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserNumberConfigUpdateRequest {

    @NotNull(message = "用户编号起始值不能为空")
    @Min(value = 1, message = "用户编号起始值必须大于 0")
    private Long startUserNo;

    @NotNull(message = "下一个用户编号不能为空")
    @Min(value = 1, message = "下一个用户编号必须大于 0")
    private Long nextUserNo;
}
