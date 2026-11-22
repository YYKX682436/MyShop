package com.myshop.config;

import com.myshop.constant.ErrorCode;
import com.myshop.exception.BusinessException;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Single source of truth for stored risk-setting defaults, validation and runtime fallback. */
public final class RiskConfigPolicy {

    private record IntegerRule(int defaultValue, int minimum, int maximum, String label) {}

    private static final Map<String, IntegerRule> INTEGER_RULES = Map.of(
            "rate_limit_per_second", new IntegerRule(25, 1, 1000, "全站每秒请求上限"),
            "login_requests_per_minute", new IntegerRule(15, 1, 120, "登录请求上限"),
            "register_requests_per_minute", new IntegerRule(5, 1, 60, "注册请求上限"),
            "device_order_limit_per_hour", new IntegerRule(15, 1, 1000, "设备下单上限"),
            "device_login_limit_per_hour", new IntegerRule(10, 1, 1000, "设备登录上限"),
            "device_register_limit_per_hour", new IntegerRule(10, 1, 1000, "设备注册上限"),
            "max_purchase_per_user", new IntegerRule(50, 1, 999, "单笔订单购买数量上限"),
            "order_expire_minutes", new IntegerRule(15, 1, 1440, "订单有效期")
    );
    private static final Set<String> BOOLEAN_KEYS = Set.of("turnstile_enabled", "device_rate_limit_enabled");
    private static final Set<String> RISK_KEYS;

    static {
        var keys = new java.util.LinkedHashSet<String>(BOOLEAN_KEYS);
        keys.addAll(INTEGER_RULES.keySet());
        RISK_KEYS = Set.copyOf(keys);
    }

    private RiskConfigPolicy() {}

    public static boolean isRiskKey(String key) {
        return RISK_KEYS.contains(key);
    }

    public static Map<String, Object> defaults() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("turnstile_enabled", false);
        result.put("device_rate_limit_enabled", false);
        INTEGER_RULES.forEach((key, rule) -> result.put(key, rule.defaultValue()));
        return result;
    }

    /** Parse an API JSON value and return its canonical database representation. */
    public static String validateRequestValue(String key, Object value) {
        if (key == null || !RISK_KEYS.contains(key)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的风控参数");
        }
        if (BOOLEAN_KEYS.contains(key)) {
            if (!(value instanceof Boolean booleanValue)) {
                throw invalid(key, "必须为布尔值");
            }
            return booleanValue.toString();
        }
        IntegerRule rule = INTEGER_RULES.get(key);
        if (rule == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的风控参数：" + key);
        }
        return Integer.toString(parseJsonInteger(key, value, rule));
    }

    /** Validate string values written through the general site-settings endpoint. */
    public static String validateSiteConfigValue(String key, String value) {
        if (BOOLEAN_KEYS.contains(key)) {
            if ("true".equalsIgnoreCase(value)) return "true";
            if ("false".equalsIgnoreCase(value)) return "false";
            throw invalid(key, "必须为 true 或 false");
        }
        IntegerRule rule = INTEGER_RULES.get(key);
        if (rule == null) return value;
        if (value == null || !value.matches("[0-9]+")) {
            throw invalid(key, rangeMessage(rule));
        }
        try {
            int parsed = Integer.parseInt(value);
            validateRange(key, parsed, rule);
            return Integer.toString(parsed);
        } catch (NumberFormatException exception) {
            throw invalid(key, rangeMessage(rule));
        }
    }

    /** Safe runtime parsing: corrupt historic values fall back to their documented default. */
    public static int runtimeInteger(String key, String value) {
        IntegerRule rule = INTEGER_RULES.get(key);
        if (rule == null || value == null || !value.matches("[0-9]+")) {
            return rule == null ? 1 : rule.defaultValue();
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= rule.minimum() && parsed <= rule.maximum() ? parsed : rule.defaultValue();
        } catch (NumberFormatException exception) {
            return rule.defaultValue();
        }
    }

    public static boolean runtimeBoolean(String key, String value) {
        if (!BOOLEAN_KEYS.contains(key)) return false;
        return "true".equalsIgnoreCase(value);
    }

    public static Object storedValue(String key, String value) {
        if (BOOLEAN_KEYS.contains(key)) return runtimeBoolean(key, value);
        IntegerRule rule = INTEGER_RULES.get(key);
        return rule == null ? value : runtimeInteger(key, value);
    }

    private static int parseJsonInteger(String key, Object value, IntegerRule rule) {
        if (!(value instanceof Number number)) {
            throw invalid(key, rangeMessage(rule));
        }
        try {
            int parsed = new BigDecimal(number.toString()).intValueExact();
            validateRange(key, parsed, rule);
            return parsed;
        } catch (ArithmeticException | NumberFormatException exception) {
            throw invalid(key, rangeMessage(rule));
        }
    }

    private static void validateRange(String key, int value, IntegerRule rule) {
        if (value < rule.minimum() || value > rule.maximum()) {
            throw invalid(key, rangeMessage(rule));
        }
    }

    private static String rangeMessage(IntegerRule rule) {
        return rule.label() + "必须为 " + rule.minimum() + "–" + rule.maximum() + " 的正整数";
    }

    private static BusinessException invalid(String key, String detail) {
        return new BusinessException(ErrorCode.BAD_REQUEST, "风控参数「" + key + "」" + detail);
    }
}
