package com.myshop.config;

import com.myshop.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RiskConfigPolicyTest {

    @Test
    void defaultsAreCompletePositiveAndDeviceAndTurnstileAreOff() {
        Map<String, Object> config = RiskConfigPolicy.defaults();

        assertEquals(false, config.get("turnstile_enabled"));
        assertEquals(false, config.get("device_rate_limit_enabled"));
        assertEquals(25, config.get("rate_limit_per_second"));
        assertEquals(15, config.get("login_requests_per_minute"));
        assertEquals(5, config.get("register_requests_per_minute"));
        assertEquals(15, config.get("device_order_limit_per_hour"));
        assertEquals(10, config.get("device_login_limit_per_hour"));
        assertEquals(10, config.get("device_register_limit_per_hour"));
        assertEquals(50, config.get("max_purchase_per_user"));
        assertEquals(15, config.get("order_expire_minutes"));
    }

    @Test
    void invalidStoredNumbersFallBackToTheSafeDefault() {
        for (String invalid : new String[] {"0", "-1", "abc", "1.5", "999999999999"}) {
            assertEquals(25, RiskConfigPolicy.runtimeInteger("rate_limit_per_second", invalid), invalid);
        }
        assertEquals(15, RiskConfigPolicy.runtimeInteger("order_expire_minutes", "0"));
        assertEquals(50, RiskConfigPolicy.runtimeInteger("max_purchase_per_user", "1000"));
        assertEquals(5, RiskConfigPolicy.runtimeInteger("register_requests_per_minute", "61"));
    }

    @Test
    void requestAndSiteSettingsRequirePositiveIntegersWithinRange() {
        for (Object invalid : new Object[] {0, -1, 61, 1.5, "5", null}) {
            assertThrows(BusinessException.class,
                    () -> RiskConfigPolicy.validateRequestValue("register_requests_per_minute", invalid));
        }
        assertEquals("15", RiskConfigPolicy.validateRequestValue("login_requests_per_minute", 15));
        assertEquals("25", RiskConfigPolicy.validateSiteConfigValue("rate_limit_per_second", "25"));
        for (String invalid : new String[] {"0", "-1", "not-a-number", "1001", "1.5"}) {
            assertThrows(BusinessException.class,
                    () -> RiskConfigPolicy.validateSiteConfigValue("rate_limit_per_second", invalid));
        }
    }

    @Test
    void booleanSettingsRequireBooleanValuesAndInvalidStoredValuesDefaultOff() {
        assertEquals("true", RiskConfigPolicy.validateRequestValue("device_rate_limit_enabled", true));
        assertThrows(BusinessException.class,
                () -> RiskConfigPolicy.validateRequestValue("device_rate_limit_enabled", "true"));
        assertEquals(false, RiskConfigPolicy.runtimeBoolean("device_rate_limit_enabled", "not-boolean"));
        assertEquals(false, RiskConfigPolicy.runtimeBoolean("turnstile_enabled", null));
    }
}
