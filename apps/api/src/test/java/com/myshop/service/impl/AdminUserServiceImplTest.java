package com.myshop.service.impl;

import com.myshop.common.PageResult;
import com.myshop.entity.User;
import com.myshop.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private AdminUserServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AdminUserServiceImpl(userRepository, passwordEncoder);
    }

    @Test
    void listUsersExposesRmbBalanceWithoutLegacyFields() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("buyer");
        user.setEmail("buyer@example.com");
        user.setBalance(new BigDecimal("12.30"));
        when(userRepository.findAll(any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(user), PageRequest.of(0, 20), 1));

        PageResult<?> result = service.listUsers(null, 1, 20);
        Map<?, ?> row = (Map<?, ?>) result.getList().get(0);

        assertEquals(new BigDecimal("12.30"), row.get("balance"));
        assertFalse(row.containsKey("points"));
    }
}
