package com.myshop.service.impl;

import com.myshop.entity.Order;
import com.myshop.entity.SiteConfig;
import com.myshop.exception.BusinessException;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.SiteConfigRepository;
import com.myshop.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RiskConfigServiceImplTest {

    @Mock
    private SiteConfigRepository siteConfigRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private UserRepository userRepository;

    private RiskConfigServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RiskConfigServiceImpl(siteConfigRepository, orderRepository, orderItemRepository, userRepository);
    }

    @Test
    void missingRiskSettingsReturnCompleteEffectiveDefaults() {
        Map<String, Object> result = service.getRiskConfig();

        assertEquals(false, result.get("turnstile_enabled"));
        assertEquals(false, result.get("device_rate_limit_enabled"));
        assertEquals(25, result.get("rate_limit_per_second"));
        assertEquals(15, result.get("login_requests_per_minute"));
        assertEquals(5, result.get("register_requests_per_minute"));
        assertEquals(15, result.get("device_order_limit_per_hour"));
        assertEquals(10, result.get("device_login_limit_per_hour"));
        assertEquals(10, result.get("device_register_limit_per_hour"));
        assertEquals(50, result.get("max_purchase_per_user"));
        assertEquals(15, result.get("order_expire_minutes"));
        assertFalse(result.containsKey("login_attempt_limit"));
    }

    @Test
    void getRiskConfigOmitsRetiredCreationLimitKeys() {
        when(siteConfigRepository.findByConfigKey(anyString())).thenAnswer(invocation ->
                Optional.of(config(invocation.getArgument(0), "5")));

        Map<String, Object> result = service.getRiskConfig();

        assertTrue(result.containsKey("max_purchase_per_user"));
        assertFalse(result.containsKey("login_attempt_limit"));
        assertFalse(result.containsKey(retiredKey("ip")));
        assertFalse(result.containsKey(retiredKey("user")));
    }

    @Test
    void updateRiskConfigDoesNotPersistRetiredKeys() {
        service.updateRiskConfig(Map.of(
                retiredKey("ip"), 20,
                retiredKey("user"), 20));

        verify(siteConfigRepository, never()).save(any(SiteConfig.class));
    }

    @Test
    void invalidPartialUpdateIsRejectedBeforeAnyWrite() {
        assertThrows(BusinessException.class, () -> service.updateRiskConfig(Map.of(
                "order_expire_minutes", 15,
                "rate_limit_per_second", 0)));

        verify(siteConfigRepository, never()).save(any(SiteConfig.class));
    }

    @Test
    void turnstileCannotBeEnabledWithoutBothKeys() {
        assertThrows(BusinessException.class,
                () -> service.updateRiskConfig(Map.of("turnstile_enabled", true)));

        verify(siteConfigRepository, never()).save(any(SiteConfig.class));
    }

    @Test
    void turnstileCanBeEnabledWhenBothKeysAreConfigured() {
        ReflectionTestUtils.setField(service, "turnstileSecretKey", "server-secret");
        ReflectionTestUtils.setField(service, "turnstileSiteKey", "public-site-key");
        when(siteConfigRepository.findByConfigKey("turnstile_enabled")).thenReturn(Optional.empty());

        service.updateRiskConfig(Map.of("turnstile_enabled", true));

        ArgumentCaptor<SiteConfig> saved = ArgumentCaptor.forClass(SiteConfig.class);
        verify(siteConfigRepository).save(saved.capture());
        assertEquals("turnstile_enabled", saved.getValue().getConfigKey());
        assertEquals("true", saved.getValue().getConfigValue());
    }

    @Test
    void flaggedOrderResponseUsesBusinessOrderNumber() {
        UUID orderId = UUID.randomUUID();
        Order order = new Order();
        order.setId(orderId);
        order.setOrderNo(1000000001L);
        order.setRiskFlagged(true);
        var pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        when(orderRepository.findRiskFlaggedOrders(pageable))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(order), pageable, 1));

        var result = service.getFlaggedOrders(1, 20);
        Map<?, ?> flagged = (Map<?, ?>) result.getList().get(0);

        assertEquals(orderId, flagged.get("id"));
        assertEquals(1000000001L, flagged.get("order_no"));
        assertEquals(true, flagged.get("is_risk_flagged"));
    }

    private static String retiredKey(String dimension) {
        return "max_" + "pending_orders_per_" + dimension;
    }

    private static SiteConfig config(String key, String value) {
        SiteConfig config = new SiteConfig();
        config.setConfigKey(key);
        config.setConfigValue(value);
        return config;
    }
}
