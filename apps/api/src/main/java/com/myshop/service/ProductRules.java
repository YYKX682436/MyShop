package com.myshop.service;

import com.myshop.constant.CustomerInputType;
import com.myshop.constant.ErrorCode;
import com.myshop.constant.StockMode;
import com.myshop.entity.Product;
import com.myshop.exception.BusinessException;

import java.util.Locale;

/** Shared product configuration and customer-input validation rules. */
public final class ProductRules {

    private ProductRules() {
    }

    public static CustomerInputType parseCustomerInputType(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) return CustomerInputType.NONE;
        try {
            return CustomerInputType.valueOf(String.valueOf(raw).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "客户填写信息类型无效");
        }
    }

    public static StockMode parseStockMode(Object raw, StockMode fallback) {
        if (raw == null || String.valueOf(raw).isBlank()) return fallback;
        try {
            return StockMode.valueOf(String.valueOf(raw).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "库存模式无效");
        }
    }

    public static String normalizeDeliveryType(Object raw, String fallback) {
        String value = raw == null || String.valueOf(raw).isBlank()
                ? (fallback == null || fallback.isBlank() ? "AUTO" : fallback)
                : String.valueOf(raw).trim().toUpperCase(Locale.ROOT);
        if (!"AUTO".equals(value) && !"MANUAL".equals(value)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "发货方式无效");
        }
        return value;
    }

    public static StockMode compatibleStockMode(String deliveryType, StockMode requested) {
        if ("AUTO".equals(deliveryType) && requested != StockMode.CARD_KEY) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "自动发货商品只能使用卡券库存");
        }
        if ("MANUAL".equals(deliveryType) && requested == StockMode.CARD_KEY) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "人工商品只能使用手动库存或无限库存");
        }
        return requested;
    }

    /** Returns null for NONE and a trimmed value for a required input. */
    public static String validateCustomerInput(Product product, String rawValue) {
        CustomerInputType type = product.getCustomerInputType() == null
                ? CustomerInputType.NONE : product.getCustomerInputType();
        if (type == CustomerInputType.NONE) return null;

        String value = rawValue == null ? "" : rawValue.strip();
        boolean valid = switch (type) {
            case QQ -> value.matches("\\d{5,12}");
            case PHONE -> value.matches("(?:\\+?\\d{6,15}|1\\d{10})");
            case QQ_OR_PHONE -> value.matches("\\d{5,12}")
                    || value.matches("(?:\\+?\\d{6,15}|1\\d{10})");
            case NONE -> true;
        };
        if (!valid) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请填写有效的客户信息");
        }
        return value;
    }

    public static String customerInputLabel(CustomerInputType type) {
        if (type == null) return "客户信息";
        return switch (type) {
            case QQ -> "QQ号码";
            case PHONE -> "手机号";
            case QQ_OR_PHONE -> "QQ或手机号";
            case NONE -> "";
        };
    }
}
