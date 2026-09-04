package com.myshop.model.request;

import com.myshop.constant.BalanceAdjustmentType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class AdminAdjustBalanceRequest {

    @NotNull(message = "调整类型不能为空")
    private BalanceAdjustmentType type;

    @NotNull(message = "调整金额不能为空")
    @DecimalMin(value = "0.01", message = "调整金额必须大于 0")
    @Digits(integer = 10, fraction = 2, message = "调整金额最多 10 位整数和 2 位小数")
    private BigDecimal amount;

    @Size(max = 500, message = "调整原因不能超过 500 个字符")
    private String reason;
}
