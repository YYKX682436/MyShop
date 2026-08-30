package com.myshop.service.impl;

import com.myshop.entity.User;
import com.myshop.entity.UserNumberSequence;
import com.myshop.exception.BusinessException;
import com.myshop.model.request.LoginRequest;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
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
        lenient().when(captchaUtils.verify(anyString(), anyString())).thenReturn(true);
        lenient().when(userRepository.existsByUsernameIgnoreCase(anyString())).thenReturn(false);
        lenient().when(userRepository.existsByEmailIgnoreCase(anyString())).thenReturn(false);
        lenient().when(userRepository.existsByPhone(anyString())).thenReturn(false);
        lenient().when(userRepository.existsByRoleAndUserNo(any(), anyLong())).thenReturn(false);
        lenient().when(userNumberSequenceRepository.findByIdForUpdate(1)).thenReturn(sequence);
        lenient().when(passwordEncoder.encode(anyString())).thenReturn("bcrypt-hash");
        lenient().when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            if (user.getId() == null) user.setId(UUID.randomUUID());
            return user;
        });
        lenient().when(jwtUtils.generateToken(any(), anyString(), anyString())).thenReturn("token");
    }

    @Test
    void firstRegistrationGetsDefaultUserNumberAndCanonicalEmail() {
        AuthResult result = service.register(request("  Buyer@Example.COM "));

        assertEquals(10000L, result.user().getUserNo());
        assertEquals("buyer@example.com", result.user().getEmail());
        assertEquals(10001L, sequence.getNextUserNo());
        verify(userRepository).saveAndFlush(argThat(user -> "13800138000".equals(user.getPhone())));
        verify(cartItemRepository, never()).findBySessionToken(anyString());
    }

    @Test
    void subsequentRegistrationsConsumeTheLockedCounterSequentially() {
        AuthResult first = service.register(request("first@example.com"));
        AuthResult second = service.register(request("second@example.com"));

        assertEquals(10000L, first.user().getUserNo());
        assertEquals(10001L, second.user().getUserNo());
        assertEquals(10002L, sequence.getNextUserNo());
    }

    @Test
    void registrationPreservesUsernameCaseAfterTrimming() {
        RegisterRequest request = request("case@example.com");
        request.setUsername("  Tom123  ");
        request.setPhone(" 13900139000 ");

        AuthResult result = service.register(request);

        assertEquals("Tom123", result.user().getUsername());
    }

    @Test
    void registrationRejectsUsernameThatMatchesAnExistingPhone() {
        RegisterRequest request = request("username-phone@example.com");
        request.setUsername("13800138000");
        request.setPhone("13900139000");
        when(userRepository.existsByPhone("13800138000")).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class, () -> service.register(request));

        assertEquals("用户名不能与现有手机号相同", exception.getMessage());
    }

    @Test
    void registrationRejectsPhoneThatMatchesAnExistingUsername() {
        RegisterRequest request = request("phone-username@example.com");
        request.setPhone("13900139000");
        when(userRepository.existsByUsernameIgnoreCase("13900139000")).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class, () -> service.register(request));

        assertEquals("该手机号已注册", exception.getMessage());
    }

    @Test
    void registrationRejectsSameUsernameAndPhone() {
        RegisterRequest request = request("same-identifier@example.com");
        request.setUsername("13800138000");
        request.setPhone("13800138000");

        BusinessException exception = assertThrows(BusinessException.class, () -> service.register(request));

        assertEquals("用户名和手机号不能相同", exception.getMessage());
    }

    @Test
    void registrationReportsCaseInsensitiveUsernameEmailAndPhoneDuplicates() {
        RegisterRequest usernameRequest = request("unique-username@example.com");
        usernameRequest.setUsername("Tom123");
        when(userRepository.existsByUsernameIgnoreCase("Tom123")).thenReturn(true);
        BusinessException usernameException = assertThrows(BusinessException.class,
                () -> service.register(usernameRequest));
        assertEquals("用户名已存在", usernameException.getMessage());

        RegisterRequest emailRequest = request("Duplicate@Example.COM");
        when(userRepository.existsByEmailIgnoreCase("duplicate@example.com")).thenReturn(true);
        BusinessException emailException = assertThrows(BusinessException.class,
                () -> service.register(emailRequest));
        assertEquals("该邮箱已注册", emailException.getMessage());

        RegisterRequest phoneRequest = request("unique-phone@example.com");
        phoneRequest.setPhone("13900139000");
        when(userRepository.existsByPhone("13900139000")).thenReturn(true);
        BusinessException phoneException = assertThrows(BusinessException.class,
                () -> service.register(phoneRequest));
        assertEquals("该手机号已注册", phoneException.getMessage());
    }

    @Test
    void loginSelectsCaseInsensitiveUsernameWithoutTryingOtherIdentifierTypes() {
        User user = user("Tom123", "tom@example.com", "13800138000");
        when(userRepository.findByUsernameIgnoreCase("ToM123")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("abcdef", user.getPasswordHash())).thenReturn(true);

        service.login(login("ToM123", "abcdef"), null);

        verify(userRepository).findByUsernameIgnoreCase("ToM123");
        verify(userRepository, never()).findByPhone(anyString());
        verify(userRepository, never()).findByEmailIgnoreCase(anyString());
    }

    @Test
    void loginSelectsNormalizedEmailWhenAccountContainsAtSign() {
        User user = user("buyer", "buyer@example.com", "13800138001");
        when(userRepository.findByEmailIgnoreCase("buyer@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("abcdef", user.getPasswordHash())).thenReturn(true);

        service.login(login("  Buyer@Example.COM ", "abcdef"), null);

        verify(userRepository).findByEmailIgnoreCase("buyer@example.com");
        verify(userRepository, never()).findByUsernameIgnoreCase(anyString());
        verify(userRepository, never()).findByPhone(anyString());
    }

    @Test
    void loginFallsBackToCaseInsensitiveUsernameWhenPhoneLookupMisses() {
        User user = user("13800138000", "numeric-username@example.com", "13900139000");
        when(userRepository.findByPhone("13800138000")).thenReturn(Optional.empty());
        when(userRepository.findByUsernameIgnoreCase("13800138000")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("abcdef", user.getPasswordHash())).thenReturn(true);

        service.login(login("13800138000", "abcdef"), null);

        InOrder lookupOrder = inOrder(userRepository);
        lookupOrder.verify(userRepository).findByPhone("13800138000");
        lookupOrder.verify(userRepository).findByUsernameIgnoreCase("13800138000");
        verify(userRepository, never()).findByEmailIgnoreCase(anyString());
    }

    @Test
    void loginPrefersExactPhoneWhenPhoneLookupHits() {
        User user = user("buyer", "buyer-phone@example.com", "13800138000");
        when(userRepository.findByPhone("13800138000")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("abcdef", user.getPasswordHash())).thenReturn(true);

        service.login(login(" 13800138000 ", "abcdef"), null);

        verify(userRepository).findByPhone("13800138000");
        verify(userRepository, never()).findByUsernameIgnoreCase(anyString());
        verify(userRepository, never()).findByEmailIgnoreCase(anyString());
    }

    @Test
    void loginUsesOneCredentialMessageForUnknownAccountsAndWrongPasswords() {
        BusinessException blankAccount = assertThrows(BusinessException.class,
                () -> service.login(login("  ", "abcdef"), null));
        assertEquals("账号或密码错误", blankAccount.getMessage());

        BusinessException unknownAccount = assertThrows(BusinessException.class,
                () -> service.login(login("missing-user", "abcdef"), null));
        assertEquals("账号或密码错误", unknownAccount.getMessage());

        User user = user("buyer", "buyer@example.com", "13800138003");
        when(userRepository.findByUsernameIgnoreCase("buyer")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-password", user.getPasswordHash())).thenReturn(false);

        BusinessException wrongPassword = assertThrows(BusinessException.class,
                () -> service.login(login("buyer", "wrong-password"), null));
        assertEquals("账号或密码错误", wrongPassword.getMessage());
    }

    private RegisterRequest request(String email) {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("buyer" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        request.setEmail(email);
        request.setPassword("a-secure-password");
        request.setPhone("13800138000");
        request.setCaptchaId("captcha-id");
        request.setCaptcha("1234");
        return request;
    }

    private LoginRequest login(String account, String password) {
        LoginRequest request = new LoginRequest();
        request.setAccount(account);
        request.setPassword(password);
        return request;
    }

    private User user(String username, String email, String phone) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setEmail(email);
        user.setPhone(phone);
        user.setPasswordHash("stored-hash");
        return user;
    }
}
