package com.myshop.service.impl;

import com.myshop.constant.UserRole;
import com.myshop.entity.User;
import com.myshop.model.request.ChangePasswordRequest;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.UserLevelRepository;
import com.myshop.repository.UserRepository;
import com.myshop.service.CardKeyReclaimService;
import com.myshop.service.ManualStockService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private ManualStockService manualStockService;
    @Mock
    private UserLevelRepository userLevelRepository;
    @Mock
    private CardKeyReclaimService cardKeyReclaimService;

    @Test
    void changingPasswordIncrementsAuthVersionSoTheOriginalJwtCannotBeReused() {
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        user.setRole(UserRole.USER);
        user.setPasswordHash("old-hash");
        user.setAuthVersion(4);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("old-password", "old-hash")).thenReturn(true);
        when(passwordEncoder.encode("new-password")).thenReturn("new-hash");

        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setOldPassword("old-password");
        request.setNewPassword("new-password");

        new UserServiceImpl(userRepository, orderRepository, orderItemRepository,
                passwordEncoder, manualStockService, userLevelRepository, cardKeyReclaimService)
                .changePassword(userId, request);

        assertEquals("new-hash", user.getPasswordHash());
        assertEquals(5, user.getAuthVersion());
        verify(userRepository).save(user);
    }
}
