package com.myshop.service.impl;

import com.myshop.common.PageResult;
import com.myshop.constant.BalanceAdjustmentType;
import com.myshop.constant.BalanceTransactionType;
import com.myshop.constant.ErrorCode;
import com.myshop.constant.UserRole;
import com.myshop.context.RequestContext;
import com.myshop.entity.BalanceTransaction;
import com.myshop.entity.User;
import com.myshop.exception.BusinessException;
import com.myshop.model.request.AdminAdjustBalanceRequest;
import com.myshop.model.request.AdminResetPasswordRequest;
import com.myshop.model.request.AdminToggleUserStatusRequest;
import com.myshop.model.request.AdminUpdateUserProfileRequest;
import com.myshop.model.response.AdminUserListResponse;
import com.myshop.model.response.BalanceTransactionResponse;
import com.myshop.repository.OperationLogRepository;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.UserRepository;
import com.myshop.service.BalanceTransactionService;
import com.myshop.service.OperationLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceImplTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private BalanceTransactionService balanceTransactionService;
    @Mock
    private OperationLogService operationLogService;
    @Mock
    private OperationLogRepository operationLogRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    private AdminUserServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AdminUserServiceImpl(userRepository, orderRepository, orderItemRepository,
                balanceTransactionService, operationLogService, operationLogRepository, passwordEncoder);
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    @Test
    void listUsersUsesOrdinaryUserQueryAndExposesBusinessMetrics() {
        UUID id = UUID.randomUUID();
        User user = user(id, "buyer", "buyer@example.com", 10000L, "12.30");
        PageRequest pageable = PageRequest.of(0, 20);
        when(userRepository.findAdminUsers(null, null, pageable))
                .thenReturn(new PageImpl<>(List.of(user), pageable, 1));
        when(orderRepository.countSuccessfulByUserId(id)).thenReturn(2L);
        when(orderRepository.sumSuccessfulActualAmountByUserId(id)).thenReturn(new BigDecimal("25.50"));

        PageResult<AdminUserListResponse> result = service.listUsers(null, null, 1, 20);

        AdminUserListResponse row = result.getList().get(0);
        assertEquals(10000L, row.userNo());
        assertEquals(2L, row.orderCount());
        assertEquals(new BigDecimal("25.50"), row.totalSpent());
        verify(userRepository).findAdminUsers(null, null, pageable);
    }

    @Test
    void increaseBalanceWritesSignedLedgerAndAuditRecord() {
        UUID userId = UUID.randomUUID();
        UUID operatorId = UUID.randomUUID();
        User user = user(userId, "buyer", "buyer@example.com", 10000L, "10.00");
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        stubLedgerService();
        RequestContext.set(new RequestContext.UserInfo(operatorId, "admin", "ADMIN"));

        AdminAdjustBalanceRequest request = new AdminAdjustBalanceRequest();
        request.setType(BalanceAdjustmentType.INCREASE);
        request.setAmount(new BigDecimal("2.50"));
        request.setReason("人工补偿");

        BalanceTransactionResponse result = service.adjustBalance(userId, request);

        assertEquals(new BigDecimal("12.50"), user.getBalance());
        assertEquals(new BigDecimal("2.50"), result.delta());
        assertEquals(new BigDecimal("10.00"), result.balanceBefore());
        assertEquals(new BigDecimal("12.50"), result.balanceAfter());
        verify(balanceTransactionService).recordAdminAdjustment(
                userId, new BigDecimal("2.50"), new BigDecimal("2.50"),
                new BigDecimal("10.00"), new BigDecimal("12.50"), "人工补偿", operatorId);
        verify(operationLogService).logCurrentRequest(
                "user.adjust_balance", "USER", userId.toString(),
                "管理员增加余额：2.50，余额：10.00 -> 12.50，原因：人工补偿");
    }

    @Test
    void decreaseBalanceWritesNegativeDeltaAndAuditRecord() {
        UUID userId = UUID.randomUUID();
        UUID operatorId = UUID.randomUUID();
        User user = user(userId, "buyer", "buyer@example.com", 10000L, "10.00");
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        stubLedgerService();
        RequestContext.set(new RequestContext.UserInfo(operatorId, "admin", "ADMIN"));

        AdminAdjustBalanceRequest request = new AdminAdjustBalanceRequest();
        request.setType(BalanceAdjustmentType.DECREASE);
        request.setAmount(new BigDecimal("2.50"));
        request.setReason("订单补扣");

        BalanceTransactionResponse result = service.adjustBalance(userId, request);

        assertEquals(new BigDecimal("7.50"), user.getBalance());
        assertEquals(new BigDecimal("-2.50"), result.delta());
        assertEquals(new BigDecimal("7.50"), result.balanceAfter());
        verify(balanceTransactionService).recordAdminAdjustment(
                userId, new BigDecimal("2.50"), new BigDecimal("-2.50"),
                new BigDecimal("10.00"), new BigDecimal("7.50"), "订单补扣", operatorId);
        verify(operationLogService).logCurrentRequest(
                "user.adjust_balance", "USER", userId.toString(),
                "管理员扣减余额：2.50，余额：10.00 -> 7.50，原因：订单补扣");
    }

    @Test
    void decreaseBelowZeroIsRejectedBeforeAnyLedgerWrite() {
        UUID userId = UUID.randomUUID();
        User user = user(userId, "buyer", "buyer@example.com", 10000L, "10.00");
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        AdminAdjustBalanceRequest request = new AdminAdjustBalanceRequest();
        request.setType(BalanceAdjustmentType.DECREASE);
        request.setAmount(new BigDecimal("10.01"));
        request.setReason("错误扣减");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.adjustBalance(userId, request));

        assertEquals(ErrorCode.BAD_REQUEST, exception.getCode());
        assertEquals(new BigDecimal("10.00"), user.getBalance());
        verify(balanceTransactionService, never()).recordAdminAdjustment(any(), any(), any(), any(), any(), any(), any());
        verify(operationLogService, never()).logCurrentRequest(any(), any(), any(), any());
    }

    @Test
    void administratorTargetIsRejectedByEveryMutationBoundary() {
        UUID adminId = UUID.randomUUID();
        User admin = user(adminId, "admin", "admin@example.com", null, "0.00");
        admin.setRole(UserRole.ADMIN);
        when(userRepository.findByIdForUpdate(adminId)).thenReturn(Optional.of(admin));

        AdminAdjustBalanceRequest request = new AdminAdjustBalanceRequest();
        request.setType(BalanceAdjustmentType.INCREASE);
        request.setAmount(new BigDecimal("1.00"));
        request.setReason("should reject");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.adjustBalance(adminId, request));

        assertEquals(ErrorCode.FORBIDDEN, exception.getCode());
        verify(balanceTransactionService, never()).recordAdminAdjustment(any(), any(), any(), any(), any(), any(), any());
        assertTrue(exception.getMessage().contains("普通用户"));
    }

    @Test
    void emailUpdateNormalizesAndRejectsAnotherUsersEmail() {
        UUID userId = UUID.randomUUID();
        User user = user(userId, "buyer", "old@example.com", 10000L, "0.00");
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(userRepository.existsByEmailIgnoreCase("new@example.com")).thenReturn(false);
        when(orderRepository.sumSuccessfulActualAmountByUserId(userId)).thenReturn(BigDecimal.ZERO);

        AdminUpdateUserProfileRequest request = new AdminUpdateUserProfileRequest();
        request.setEmail("  NEW@Example.COM ");
        RequestContext.set(new RequestContext.UserInfo(UUID.randomUUID(), "admin", "ADMIN"));

        service.updateProfile(userId, request);

        assertEquals("new@example.com", user.getEmail());
        verify(operationLogService).logCurrentRequest(
                "user.update_profile", "USER", userId.toString(),
                "管理员修改用户邮箱：old@example.com -> new@example.com");
    }

    @Test
    void emailUpdateRejectsCaseInsensitiveConflictBeforeSaving() {
        UUID userId = UUID.randomUUID();
        User user = user(userId, "buyer", "old@example.com", 10000L, "0.00");
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(userRepository.existsByEmailIgnoreCase("other@example.com")).thenReturn(true);

        AdminUpdateUserProfileRequest request = new AdminUpdateUserProfileRequest();
        request.setEmail("OTHER@example.com");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.updateProfile(userId, request));

        assertEquals(ErrorCode.EMAIL_EXISTS, exception.getCode());
        verify(userRepository, never()).save(any(User.class));
        verify(operationLogService, never()).logCurrentRequest(any(), any(), any(), any());
    }

    @Test
    void toggleAndResetPasswordKeepTheirMutationAndAuditBoundaries() {
        UUID userId = UUID.randomUUID();
        UUID operatorId = UUID.randomUUID();
        User user = user(userId, "buyer", "buyer@example.com", 10000L, "0.00");
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("a-new-password")).thenReturn("bcrypt-hash");
        RequestContext.set(new RequestContext.UserInfo(operatorId, "admin", "ADMIN"));

        AdminToggleUserStatusRequest toggle = new AdminToggleUserStatusRequest();
        toggle.setIsDeleted(1);
        service.toggleUser(userId, toggle);
        assertEquals(1, user.getIsDeleted());

        AdminResetPasswordRequest reset = new AdminResetPasswordRequest();
        reset.setNewPassword("a-new-password");
        service.resetPassword(userId, reset);
        assertEquals("bcrypt-hash", user.getPasswordHash());
        verify(operationLogService).logCurrentRequest(
                "user.disable", "USER", userId.toString(), "管理员修改用户状态：正常 -> 已禁用");
        verify(operationLogService).logCurrentRequest(
                "user.reset_password", "USER", userId.toString(), "管理员重置用户密码（密码内容不记录）");
    }

    private void stubLedgerService() {
        when(balanceTransactionService.recordAdminAdjustment(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    BalanceTransaction transaction = new BalanceTransaction();
                    transaction.setUserId(invocation.getArgument(0));
                    transaction.setAmount(invocation.getArgument(1));
                    transaction.setDelta(invocation.getArgument(2));
                    transaction.setBalanceBefore(invocation.getArgument(3));
                    transaction.setBalanceAfter(invocation.getArgument(4));
                    transaction.setReason(invocation.getArgument(5));
                    transaction.setOperatorId(invocation.getArgument(6));
                    transaction.setType(BalanceTransactionType.ADMIN_ADJUSTMENT);
                    transaction.setId(UUID.randomUUID());
                    return transaction;
                });
    }

    private User user(UUID id, String username, String email, Long userNo, String balance) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(email);
        user.setUserNo(userNo);
        user.setRole(UserRole.USER);
        user.setBalance(new BigDecimal(balance));
        return user;
    }
}
