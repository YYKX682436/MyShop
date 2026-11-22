package com.myshop.service;

import com.myshop.constant.ErrorCode;
import com.myshop.entity.Order;
import com.myshop.exception.BusinessException;

import java.util.UUID;

/** Shared authorization for order reads and for attaching an order to support. */
public final class OrderOwnership {
    private OrderOwnership() {}

    public static void requireAccess(Order order, UUID userId, String sessionToken) {
        if (!SubsiteOrderAccess.permits(order, userId))
            throw new BusinessException(ErrorCode.FORBIDDEN, "订单不属于当前站点");
        if (order.getUserId() != null) {
            if (userId == null || !userId.equals(order.getUserId()))
                throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问此订单");
            return;
        }
        String provided = clean(sessionToken), stored = clean(order.getSessionToken());
        if (provided == null || stored == null || !stored.equals(provided))
            throw new BusinessException(ErrorCode.FORBIDDEN, "游客会话无效");
    }

    private static String clean(String value) {
        if (value == null) return null;
        String result = value.trim();
        return result.isEmpty() ? null : result;
    }
}
