package com.myshop.service.impl;

import com.myshop.common.PageResult;
import com.myshop.constant.ErrorCode;
import com.myshop.constant.UserRole;
import com.myshop.entity.User;
import com.myshop.exception.BusinessException;
import com.myshop.model.request.CreateAdministratorRequest;
import com.myshop.model.request.ResetAdministratorPasswordRequest;
import com.myshop.model.request.ToggleAdministratorStatusRequest;
import com.myshop.model.request.UpdateAdministratorRequest;
import com.myshop.model.response.AdministratorResponse;
import com.myshop.repository.OperationLogRepository;
import com.myshop.repository.UserRepository;
import com.myshop.service.OperationLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdministratorServiceImplTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private OperationLogService operationLogService;
    @Mock
    private OperationLogRepository operationLogRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    private AdministratorServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AdministratorServiceImpl(userRepository, operationLogService,
                operationLogRepository, passwordEncoder);
    }

    @Test
    void createsAdminWithoutContactsAndAcceptsSixCharacterPassword() {
        CreateAdministratorRequest request = new CreateAdministratorRequest();
        request.setUsername(" Ops_12-3 ");
        request.setPassword("abc123");
        request.setEmail("  ");
        request.setPhone("  ");
        when(passwordEncoder.encode("abc123")).thenReturn("bcrypt-hash");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        AdministratorResponse response = service.createAdministrator(request);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        User saved = captor.getValue();
        assertEquals("Ops_12-3", saved.getUsername());
        assertNull(saved.getEmail());
        assertNull(saved.getPhone());
        assertEquals("bcrypt-hash", saved.getPasswordHash());
        assertEquals(UserRole.ADMIN, saved.getRole());
        assertNull(saved.getUserNo());
        assertEquals(UserRole.ADMIN, response.role());
        assertNull(response.email());
        assertNull(response.phone());
        verify(operationLogService).logCurrentRequest(
                "administrator.create", "ADMINISTRATOR", saved.getId().toString(),
                "创建普通管理员：Ops_12-3");
    }

    @Test
    void normalizesContactsAndRejectsDuplicateEmailOrPhone() {
        CreateAdministratorRequest request = new CreateAdministratorRequest();
        request.setUsername("Ops456");
        request.setPassword("visible");
        request.setEmail(" NEW@Example.COM ");
        request.setPhone(" 13800138000 ");
        when(passwordEncoder.encode("visible")).thenReturn("hash");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        service.createAdministrator(request);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        assertEquals("new@example.com", captor.getValue().getEmail());
        assertEquals("13800138000", captor.getValue().getPhone());

        CreateAdministratorRequest duplicate = new CreateAdministratorRequest();
        duplicate.setUsername("Ops789");
        duplicate.setPassword("visible");
        duplicate.setEmail("other@example.com");
        when(userRepository.existsByEmailIgnoreCase("other@example.com")).thenReturn(true);

        BusinessException emailConflict = assertThrows(BusinessException.class,
                () -> service.createAdministrator(duplicate));
        assertEquals(ErrorCode.EMAIL_EXISTS, emailConflict.getCode());
        verify(userRepository, times(1)).saveAndFlush(any(User.class));

        duplicate.setEmail(null);
        duplicate.setPhone("13800138000");
        when(userRepository.existsByPhone("13800138000")).thenReturn(true);

        BusinessException phoneConflict = assertThrows(BusinessException.class,
                () -> service.createAdministrator(duplicate));
        assertEquals(ErrorCode.PHONE_EXISTS, phoneConflict.getCode());
        verify(userRepository, times(1)).saveAndFlush(any(User.class));
    }

    @Test
    void invalidAdminIdentityAndPasswordAreRejected() {
        CreateAdministratorRequest badUsername = new CreateAdministratorRequest();
        badUsername.setUsername("中文");
        badUsername.setPassword("abc123");
        BusinessException usernameException = assertThrows(BusinessException.class,
                () -> service.createAdministrator(badUsername));
        assertEquals(ErrorCode.BAD_REQUEST, usernameException.getCode());

        CreateAdministratorRequest badPassword = new CreateAdministratorRequest();
        badPassword.setUsername("Ops999");
        badPassword.setPassword("五个");
        BusinessException passwordException = assertThrows(BusinessException.class,
                () -> service.createAdministrator(badPassword));
        assertEquals(ErrorCode.BAD_REQUEST, passwordException.getCode());

        CreateAdministratorRequest badEmail = new CreateAdministratorRequest();
        badEmail.setUsername("Ops998");
        badEmail.setPassword("abc123");
        badEmail.setEmail("not-an-email");
        BusinessException emailException = assertThrows(BusinessException.class,
                () -> service.createAdministrator(badEmail));
        assertEquals(ErrorCode.BAD_REQUEST, emailException.getCode());
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void listIncludesSuperAdminAsReadOnlyDataButMutationRequiresOrdinaryAdmin() {
        User ordinary = user(UUID.randomUUID(), "Ops123", UserRole.ADMIN);
        User superAdmin = user(UUID.randomUUID(), "muchen-admin", UserRole.SUPER_ADMIN);
        PageRequest pageable = PageRequest.of(0, 20);
        when(userRepository.findAdministrators(null, null, pageable))
                .thenReturn(new PageImpl<>(List.of(ordinary, superAdmin), pageable, 2));

        PageResult<AdministratorResponse> result = service.listAdministrators(null, null, 1, 20);

        assertEquals(2, result.getList().size());
        assertEquals(UserRole.SUPER_ADMIN, result.getList().get(1).role());
        assertEquals(2, result.getPagination().getTotal());
    }

    @Test
    void ordinaryAdminProfileStatusAndPasswordChangesAreAudited() {
        UUID id = UUID.randomUUID();
        User admin = user(id, "Ops123", UserRole.ADMIN);
        admin.setEmail("old@example.com");
        admin.setPhone("13800138000");
        when(userRepository.findByIdForUpdate(id)).thenReturn(Optional.of(admin));
        when(userRepository.existsByEmailIgnoreCase("new@example.com")).thenReturn(false);
        when(userRepository.existsByPhone("13900139000")).thenReturn(false);
        when(passwordEncoder.encode("newpass")).thenReturn("new-hash");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateAdministratorRequest update = new UpdateAdministratorRequest();
        update.setEmail(" NEW@Example.COM ");
        update.setPhone(" 13900139000 ");
        AdministratorResponse response = service.updateAdministrator(id, update);

        assertEquals("new@example.com", response.email());
        assertEquals("13900139000", response.phone());

        ToggleAdministratorStatusRequest toggle = new ToggleAdministratorStatusRequest();
        toggle.setIsDeleted(1);
        service.toggleAdministrator(id, toggle);
        assertEquals(1, admin.getIsDeleted());

        ResetAdministratorPasswordRequest reset = new ResetAdministratorPasswordRequest();
        reset.setNewPassword("newpass");
        service.resetAdministratorPassword(id, reset);
        assertEquals("new-hash", admin.getPasswordHash());

        verify(operationLogService).logCurrentRequest(
                "administrator.update", "ADMINISTRATOR", id.toString(),
                "修改管理员联系方式：email old@example.com -> new@example.com，phone 13800138000 -> 13900139000");
        verify(operationLogService).logCurrentRequest(
                "administrator.disable", "ADMINISTRATOR", id.toString(),
                "修改管理员状态：正常 -> 已禁用");
        verify(operationLogService).logCurrentRequest(
                "administrator.reset_password", "ADMINISTRATOR", id.toString(),
                "重置管理员密码（密码内容不记录）");
    }

    @Test
    void fixedSuperAdminIsReadableButCannotBeChanged() {
        UUID id = UUID.randomUUID();
        User superAdmin = user(id, "muchen-admin", UserRole.SUPER_ADMIN);
        when(userRepository.findById(id)).thenReturn(Optional.of(superAdmin));
        when(userRepository.findByIdForUpdate(id)).thenReturn(Optional.of(superAdmin));

        assertEquals(UserRole.SUPER_ADMIN, service.getAdministrator(id).role());

        UpdateAdministratorRequest update = new UpdateAdministratorRequest();
        update.setEmail("new@example.com");
        assertEquals(ErrorCode.FORBIDDEN,
                assertThrows(BusinessException.class, () -> service.updateAdministrator(id, update)).getCode());

        ToggleAdministratorStatusRequest toggle = new ToggleAdministratorStatusRequest();
        toggle.setIsDeleted(1);
        assertEquals(ErrorCode.FORBIDDEN,
                assertThrows(BusinessException.class, () -> service.toggleAdministrator(id, toggle)).getCode());

        ResetAdministratorPasswordRequest reset = new ResetAdministratorPasswordRequest();
        reset.setNewPassword("newpass");
        assertEquals(ErrorCode.FORBIDDEN,
                assertThrows(BusinessException.class, () -> service.resetAdministratorPassword(id, reset)).getCode());

        verify(userRepository, never()).save(any(User.class));
        verify(userRepository, never()).saveAndFlush(any(User.class));
        verify(operationLogService, never()).logCurrentRequest(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void toggleRequiresAnExplicitStatus() {
        UUID id = UUID.randomUUID();
        User admin = user(id, "Ops123", UserRole.ADMIN);
        when(userRepository.findByIdForUpdate(id)).thenReturn(Optional.of(admin));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.toggleAdministrator(id, null));

        assertEquals(ErrorCode.BAD_REQUEST, exception.getCode());
        assertTrue(exception.getMessage().contains("状态不能为空"));
        verify(userRepository, never()).save(any(User.class));
    }

    private User user(UUID id, String username, UserRole role) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setRole(role);
        user.setIsDeleted(0);
        user.setUserNo(null);
        return user;
    }
}
