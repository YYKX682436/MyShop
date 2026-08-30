package com.myshop.model.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ToggleAdministratorStatusRequest {

    @NotNull(message = "管理员状态不能为空")
    @Min(value = 0, message = "管理员状态只能为 0 或 1")
    @Max(value = 1, message = "管理员状态只能为 0 或 1")
    private Integer isDeleted;
}
