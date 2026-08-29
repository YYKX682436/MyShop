package com.myshop.service.impl;

import com.myshop.entity.User;
import com.myshop.entity.UserNumberSequence;
import com.myshop.model.request.RegisterRequest;
import com.myshop.model.response.AuthResult;
import com.myshop.repository.CartItemRepository;
import com.myshop.repository.UserNumberSequenceRepository;
import com.myshop.repository.UserRepository;
import com.myshop.utils.CaptchaUtils;
import com.myshop.utils.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserNumberSequenceRepository userNumberSequenceRepository;
    @Mock
    private CartItemRepository cartItemRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtUtils jwtUtils;
    @Mock
    private CaptchaUtils captchaUtils;

    private AuthServiceImpl service;
    private UserNumberSequence sequence;

    @BeforeEach
    void setUp() {
        service = new AuthServiceImpl(userRepository, userNumberSequenceRepository, cartItemRepository,
                passwordEncoder, jwtUtils, captchaUtils);
        sequence = new UserNumberSequence(1, 10000L, 10000L);
        when(captchaUtils.verify(anyString(), anyString())).thenReturn(true);
        when(userRepository.existsByUsername(anyString())).thenReturn(false);
        when(userRepository.existsByEmailIgnoreCase(anyString())).thenReturn(false);
        when(userRepository.existsByRoleAndUserNo(any(), anyLong())).thenReturn(false);
        when(userNumberSequenceRepository.findByIdForUpdate(1)).thenReturn(sequence);
        when(passwordEncoder.encode(anyString())).thenReturn("bcrypt-hash");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            if (user.getId() == null) user.setId(UUID.randomUUID());
            return user;
        });
        when(jwtUtils.generateToken(any(), anyString(), anyString())).thenReturn("token");
    }

    @Test
    void firstRegistrationGetsDefaultUserNumberAndCanonicalEmail() {
        AuthResult result = service.register(request("  Buyer@Example.COM "));

        assertEquals(10000L, result.user().getUserNo());
        assertEquals("buyer@example.com", result.user().getEmail());
        assertEquals(10001L, sequence.getNextUserNo());
    }

    @Test
    void subsequentRegistrationsConsumeTheLockedCounterSequentially() {
        AuthResult first = service.register(request("first@example.com"));
        AuthResult second = service.register(request("second@example.com"));

        assertEquals(10000L, first.user().getUserNo());
        assertEquals(10001L, second.user().getUserNo());
        assertEquals(10002L, sequence.getNextUserNo());
    }

    private RegisterRequest request(String email) {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("buyer-" + UUID.randomUUID());
        request.setEmail(email);
        request.setPassword("a-secure-password");
        request.setCaptchaId("captcha-id");
        request.setCaptcha("1234");
        return request;
    }
}
