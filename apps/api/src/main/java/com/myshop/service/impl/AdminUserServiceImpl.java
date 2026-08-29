package com.myshop.service.impl;

import com.myshop.common.PageResult;
import com.myshop.constant.BalanceAdjustmentType;
import com.myshop.constant.ErrorCode;
import com.myshop.constant.UserRole;
import com.myshop.context.RequestContext;
import com.myshop.entity.BalanceTransaction;
import com.myshop.entity.OperationLog;
import com.myshop.entity.Order;
import com.myshop.entity.OrderItem;
import com.myshop.entity.User;
import com.myshop.exception.BusinessException;
import com.myshop.model.request.AdminAdjustBalanceRequest;
import com.myshop.model.request.AdminResetPasswordRequest;
import com.myshop.model.request.AdminToggleUserStatusRequest;
import com.myshop.model.request.AdminUpdateUserProfileRequest;
import com.myshop.model.response.AdminOperationLogResponse;
import com.myshop.model.response.AdminUserDetailResponse;
import com.myshop.model.response.AdminUserListResponse;
import com.myshop.model.response.AdminUserOrderResponse;
import com.myshop.model.response.BalanceTransactionResponse;
import com.myshop.repository.OperationLogRepository;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.UserRepository;
import com.myshop.service.AdminUserService;
import com.myshop.service.BalanceTransactionService;
import com.myshop.service.OperationLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminUserServiceImpl implements AdminUserService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private static final BigDecimal MAX_BALANCE = new BigDecimal("9999999999.99");

    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final BalanceTransactionService balanceTransactionService;
    private final OperationLogService operationLogService;
    private final OperationLogRepository operationLogRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional(readOnly = true)
    public PageResult<AdminUserListResponse> listUsers(String keyword, Integer isDeleted, int page, int pageSize) {
        requirePage(page, pageSize);
        if (isDeleted != null && isDeleted != 0 && isDeleted != 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户状态只能为 0 或 1");
        }
        String normalizedKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        Page<User> userPage = userRepository.findAdminUsers(
                normalizedKeyword, isDeleted, PageRequest.of(page - 1, pageSize));
        List<AdminUserListResponse> list = userPage.getContent().stream()
                .map(this::toListResponse)
                .toList();
        return PageResult.of(userPage, list);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminUserDetailResponse getUserDetail(UUID id) {
        return toDetailResponse(requireRegularUser(id, false));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<AdminUserOrderResponse> listUserOrders(UUID id, int page, int pageSize) {
        requirePage(page, pageSize);
        requireRegularUser(id, false);
        Page<Order> orderPage = orderRepository.findByUserIdOrderByCreatedAtDesc(
                id, PageRequest.of(page - 1, pageSize));
        List<AdminUserOrderResponse> list = orderPage.getContent().stream()
                .map(this::toOrderResponse)
                .toList();
        return PageResult.of(orderPage, list);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<BalanceTransactionResponse> listBalanceTransactions(UUID id, int page, int pageSize) {
        requirePage(page, pageSize);
        requireRegularUser(id, false);
        Page<BalanceTransaction> transactionPage = balanceTransactionService
                .listByUser(id, PageRequest.of(page - 1, pageSize));
        List<BalanceTransactionResponse> list = transactionPage.getContent().stream()
                .map(this::toBalanceResponse)
                .toList();
        return PageResult.of(transactionPage, list);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<AdminOperationLogResponse> listUserOperationLogs(UUID id, int page, int pageSize) {
        requirePage(page, pageSize);
        requireRegularUser(id, false);
        Page<OperationLog> logPage = operationLogRepository
                .findByTargetTypeAndTargetIdOrderByCreatedAtDesc(
                        "USER", id.toString(), PageRequest.of(page - 1, pageSize));
        List<AdminOperationLogResponse> list = logPage.getContent().stream()
                .map(this::toOperationLogResponse)
                .toList();
        return PageResult.of(logPage, list);
    }

    @Override
    @Transactional
    public AdminUserDetailResponse updateProfile(UUID id, AdminUpdateUserProfileRequest request) {
        User user = requireRegularUser(id, true);
        String email = normalizeEmail(request == null ? null : request.getEmail());
        if (email == null || email.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邮箱不能为空");
        }
        if (userRepository.existsByEmailIgnoreCase(email)
                && !email.equalsIgnoreCase(user.getEmail())) {
            throw new BusinessException(ErrorCode.EMAIL_EXISTS, "该邮箱已被其他用户使用");
        }
        String oldEmail = user.getEmail();
        user.setEmail(email);
        userRepository.save(user);
        logUserOperation("user.update_profile", id,
                "管理员修改用户邮箱：" + oldEmail + " -> " + email);
        return toDetailResponse(user);
    }

    @Override
    @Transactional
    public BalanceTransactionResponse adjustBalance(UUID id, AdminAdjustBalanceRequest request) {
        User user = requireRegularUser(id, true);
        if (request == null || request.getType() == null || request.getAmount() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "余额调整参数不完整");
        }
        BigDecimal amount = normalizeMoney(request.getAmount());
        if (amount.signum() <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "调整金额必须大于 0");
        }
        String reason = request.getReason() == null ? "" : request.getReason().trim();
        if (reason.isBlank() || reason.length() > 500) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "调整原因不能为空且不能超过 500 个字符");
        }

        BigDecimal before = normalizeMoney(user.getBalance());
        BigDecimal delta = request.getType() == BalanceAdjustmentType.INCREASE
                ? amount : amount.negate();
        BigDecimal after = before.add(delta).setScale(2, RoundingMode.UNNECESSARY);
        if (after.signum() < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "扣减后余额不能小于 0");
        }
        if (after.compareTo(MAX_BALANCE) > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "调整后余额超出系统支持范围");
        }

        user.setBalance(after);
        userRepository.save(user);

        UUID operatorId = RequestContext.getUserId();
        if (operatorId == null) {
            throw new IllegalStateException("An authenticated administrator is required for a balance adjustment");
        }
        BalanceTransaction saved = balanceTransactionService.recordAdminAdjustment(
                id, amount, delta, before, after, reason, operatorId);

        String operation = request.getType() == BalanceAdjustmentType.INCREASE ? "增加" : "扣减";
        logUserOperation("user.adjust_balance", id,
                "管理员" + operation + "余额：" + formatMoney(amount)
                        + "，余额：" + formatMoney(before) + " -> " + formatMoney(after)
                        + "，原因：" + reason);
        return toBalanceResponse(saved);
    }

    @Override
    @Transactional
    public void toggleUser(UUID id, AdminToggleUserStatusRequest request) {
        User user = requireRegularUser(id, true);
        Integer isDeleted = request == null ? null : request.getIsDeleted();
        if (isDeleted == null || (isDeleted != 0 && isDeleted != 1)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户状态只能为 0 或 1");
        }
        int previous = user.getIsDeleted();
        user.setIsDeleted(isDeleted);
        userRepository.save(user);
        String action = isDeleted == 1 ? "user.disable" : "user.enable";
        String state = isDeleted == 1 ? "已禁用" : "正常";
        String previousState = previous == 1 ? "已禁用" : "正常";
        logUserOperation(action, id, "管理员修改用户状态：" + previousState + " -> " + state);
    }

    @Override
    @Transactional
    public void resetPassword(UUID id, AdminResetPasswordRequest request) {
        User user = requireRegularUser(id, true);
        String newPassword = request == null ? null : request.getNewPassword();
        if (newPassword == null || newPassword.length() < 12) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "新密码至少需要 12 个字符");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setFailedLoginAttempts(0);
        user.setLockUntil(null);
        userRepository.save(user);
        logUserOperation("user.reset_password", id, "管理员重置用户密码（密码内容不记录）");
    }

    private User requireRegularUser(UUID id, boolean locked) {
        User user = locked
                ? userRepository.findByIdForUpdate(id).orElse(null)
                : userRepository.findById(id).orElse(null);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        if (user.getRole() != UserRole.USER) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "管理员账号不属于普通用户管理范围");
        }
        return user;
    }

    private AdminUserListResponse toListResponse(User user) {
        return new AdminUserListResponse(
                user.getId(), user.getUserNo(), user.getUsername(), user.getEmail(),
                normalizeMoney(user.getBalance()),
                orderRepository.countSuccessfulByUserId(user.getId()),
                nonNullMoney(orderRepository.sumSuccessfulActualAmountByUserId(user.getId())),
                user.getIsDeleted(), user.getCreatedAt());
    }

    private AdminUserDetailResponse toDetailResponse(User user) {
        return new AdminUserDetailResponse(
                user.getId(), user.getUserNo(), user.getUsername(), user.getEmail(),
                normalizeMoney(user.getBalance()),
                orderRepository.countSuccessfulByUserId(user.getId()),
                nonNullMoney(orderRepository.sumSuccessfulActualAmountByUserId(user.getId())),
                user.getIsDeleted(), user.getCreatedAt());
    }

    private AdminUserOrderResponse toOrderResponse(Order order) {
        List<AdminUserOrderResponse.AdminUserOrderItemResponse> items = orderItemRepository
                .findByOrderId(order.getId()).stream()
                .map(this::toOrderItemResponse)
                .toList();
        return new AdminUserOrderResponse(
                order.getId(), order.getTotalAmount(), order.getActualAmount(), order.getStatus(),
                order.getOrderType(), order.getPaymentMethod(), order.getEmail(), order.getCreatedAt(),
                order.getPaidAt(), order.getDeliveredAt(), items);
    }

    private AdminUserOrderResponse.AdminUserOrderItemResponse toOrderItemResponse(OrderItem item) {
        return new AdminUserOrderResponse.AdminUserOrderItemResponse(
                item.getId(), item.getProductTitle(), item.getSpecName(), item.getQuantity(),
                item.getUnitPrice(), item.getSubtotal());
    }

    private BalanceTransactionResponse toBalanceResponse(BalanceTransaction transaction) {
        return new BalanceTransactionResponse(
                transaction.getId(), transaction.getType(), transaction.getAmount(), transaction.getDelta(),
                transaction.getBalanceBefore(), transaction.getBalanceAfter(), transaction.getReason(),
                transaction.getOperatorId(), transaction.getOrderId(), transaction.getCreatedAt());
    }

    private AdminOperationLogResponse toOperationLogResponse(OperationLog log) {
        return new AdminOperationLogResponse(
                log.getId(), log.getUserId(), log.getUsername(), log.getAction(), log.getTargetType(),
                log.getTargetId(), log.getDetail(), log.getIpAddress(), log.getCreatedAt());
    }

    private void logUserOperation(String action, UUID targetId, String detail) {
        operationLogService.logCurrentRequest(action, "USER", targetId.toString(), detail);
    }

    private void requirePage(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分页参数无效");
        }
    }

    private BigDecimal normalizeMoney(BigDecimal value) {
        if (value == null) return ZERO;
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "金额最多支持两位小数");
        }
    }

    private BigDecimal nonNullMoney(BigDecimal value) {
        return value == null ? ZERO : normalizeMoney(value);
    }

    private String formatMoney(BigDecimal value) {
        return value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    private String normalizeEmail(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
