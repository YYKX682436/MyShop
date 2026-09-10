package com.myshop.service.impl;

import com.myshop.entity.Order;
import com.myshop.entity.SiteConfig;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.SiteConfigRepository;
import com.myshop.repository.UserRepository;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void getRiskConfigOmitsRetiredCreationLimitKeys() {
        when(siteConfigRepository.findByConfigKey(anyString())).thenAnswer(invocation ->
                Optional.of(config(invocation.getArgument(0), "5")));

        Map<String, Object> result = service.getRiskConfig();

        assertTrue(result.containsKey("max_purchase_per_user"));
        assertFalse(result.containsKey(retiredKey("ip")));
        assertFalse(result.containsKey(retiredKey("user")));
    }

    @Test
    void updateRiskConfigDoesNotPersistRetiredCreationLimitKeys() {
        service.updateRiskConfig(Map.of(
                retiredKey("ip"), 20,
                retiredKey("user"), 20));

        verify(siteConfigRepository, never()).save(any(SiteConfig.class));
    }

    @Test
    void flaggedOrderResponseUsesBusinessOrderNumber() {
        UUID orderId = UUID.randomUUID();
        Order order = new Order();
        order.setId(orderId);
        order.setOrderNo(1000000001L);
        order.setRiskFlagged(true);
        PageRequest pageable = PageRequest.of(0, 20);
        when(orderRepository.findRiskFlaggedOrders(pageable))
                .thenReturn(new PageImpl<>(List.of(order), pageable, 1));

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
