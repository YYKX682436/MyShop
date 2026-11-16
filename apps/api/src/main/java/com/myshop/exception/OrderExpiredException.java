package com.myshop.exception;

import com.myshop.constant.ErrorCode;

/** Expiry has been completed and must commit even though payment is rejected. */
public class OrderExpiredException extends BusinessException {
    public OrderExpiredException() {
        super(ErrorCode.ORDER_EXPIRED, "订单已过期");
    }
}
