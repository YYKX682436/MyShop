package com.myshop.constant;

/** Distinguishes real sales from an administrator's internal pickup order. */
public enum OrderType {
    NORMAL,
    ADMIN_PICKUP,
    SALES_CHANNEL
}
