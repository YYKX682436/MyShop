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
import com.myshop.entity.UserLevel;
import com.myshop.exception.BusinessException;
import com.myshop.model.request.AdminAdjustBalanceRequest;
import com.myshop.model.request.AdminToggleUserStatusRequest;
import com.myshop.model.request.AdminUpdateUserProfileRequest;
import com.myshop.model.response.AdminOperationLogResponse;
import com.myshop.model.response.AdminResetPasswordResponse;
import com.myshop.model.response.AdminUserDetailResponse;
import com.myshop.model.response.AdminUserListResponse;
import com.myshop.model.response.AdminUserOrderResponse;
import com.myshop.model.response.BalanceTransactionResponse;
import com.myshop.model.response.IpLocationResponse;
import com.myshop.repository.OperationLogRepository;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.UserOrderMetricsProjection;
import com.myshop.repository.UserRepository;
import com.myshop.repository.UserLevelRepository;
import com.myshop.service.AdminUserService;
import com.myshop.service.BalanceTransactionService;
import com.myshop.service.IpLocationService;
import com.myshop.service.OperationLogService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AdminUserServiceImpl implements AdminUserService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private static final BigDecimal MAX_BALANCE = new BigDecimal("9999999999.99");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final BalanceTransactionService balanceTransactionService;
    private final OperationLogService operationLogService;
    private final OperationLogRepository operationLogRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserLevelRepository userLevelRepository;
    private final IpLocationService ipLocationService;

    @Autowired
    public AdminUserServiceImpl(UserRepository userRepository, OrderRepository orderRepository,
                                OrderItemRepository orderItemRepository,
                                BalanceTransactionService balanceTransactionService,
                                OperationLogService operationLogService,
                                OperationLogRepository operationLogRepository,
                                PasswordEncoder passwordEncoder,
                                UserLevelRepository userLevelRepository,
                                IpLocationService ipLocationService) {
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.balanceTransactionService = balanceTransactionService;
        this.operationLogService = operationLogService;
        this.operationLogRepository = operationLogRepository;
        this.passwordEncoder = passwordEncoder;
        this.userLevelRepository = userLevelRepository;
        this.ipLocationService = ipLocationService;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<AdminUserListResponse> listUsers(String keyword, Integer isDeleted, int page, int pageSize) {
        return listUsersInternal(keyword, isDeleted, null, page, pageSize);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<AdminUserListResponse> listUsers(String keyword, Integer isDeleted, UUID userLevelId,
                                                       int page, int pageSize) {
        return listUsersInternal(keyword, isDeleted, userLevelId, page, pageSize);
    }

    private PageResult<AdminUserListResponse> listUsersInternal(String keyword, Integer isDeleted,
                                                                UUID userLevelId, int page, int pageSize) {
        requirePage(page, pageSize);
        if (isDeleted != null && isDeleted != 0 && isDeleted != 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户状态只能为 0 或 1");
        }
        String normalizedKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        PageRequest pageable = PageRequest.of(page - 1, pageSize);
        Page<User> userPage = userLevelId == null
                ? userRepository.findAdminUsers(normalizedKeyword, isDeleted, pageable)
                : userRepository.findAdminUsersByLevel(normalizedKeyword, isDeleted, userLevelId, pageable);
        Map<UUID, UserOrderMetricsProjection> metricsByUserId = loadOrderMetrics(userPage.getContent());
        Map<UUID, UserLevel> levelsById = loadLevels(userPage.getContent());
        List<AdminUserListResponse> list = userPage.getContent().stream()
                .map(user -> toListResponse(user, metricsByUserId.get(user.getId()), levelsById.get(user.getUserLevelId())))
                .toList();
        return PageResult.of(userPage, list);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminUserDetailResponse getUserDetail(UUID id) {
        User user = requireRegularUser(id, false);
        UserLevel level = user.getUserLevelId() == null
                ? null : userLevelRepository.findById(user.getUserLevelId()).orElse(null);
        return toDetailResponse(user, loadOrderMetrics(List.of(user)).get(user.getId()), level);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<AdminUserOrderResponse> listUserOrders(UUID id, int page, int pageSize) {
        requirePage(page, pageSize);
        requireRegularUser(id, false);
        Page<Order> orderPage = orderRepository.findByUserIdOrderByCreatedAtDesc(
                id, PageRequest.of(page - 1, pageSize));
        Map<UUID, List<OrderItem>> itemsByOrderId = loadOrderItems(orderPage.getContent());
        List<AdminUserOrderResponse> list = orderPage.getContent().stream()
                .map(order -> toOrderResponse(order,
                        itemsByOrderId.getOrDefault(order.getId(), List.of())))
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
        Map<UUID, String> operatorNames = loadOperatorNames(transactionPage.getContent());
        List<BalanceTransactionResponse> list = transactionPage.getContent().stream()
                .map(transaction -> toBalanceResponse(transaction, operatorNames.get(transaction.getOperatorId())))
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
        UserLevel level = user.getUserLevelId() == null
                ? null : userLevelRepository.findById(user.getUserLevelId()).orElse(null);
        return toDetailResponse(user, loadOrderMetrics(List.of(user)).get(user.getId()), level);
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
        String reason = normalizeReason(request.getReason());
        if (reason != null && reason.length() > 500) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "调整原因不能超过 500 个字符");
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

        UUID operatorId = RequestContext.getUserId();
        if (operatorId == null) {
            throw new IllegalStateException("An authenticated administrator is required for a balance adjustment");
        }
        user.setBalance(after);
        userRepository.save(user);

        BalanceTransaction saved = balanceTransactionService.recordAdminAdjustment(
                id, amount, delta, before, after, reason, operatorId);

        String operation = request.getType() == BalanceAdjustmentType.INCREASE ? "增加" : "扣减";
        logUserOperation("user.adjust_balance", id,
                adjustmentLogDetail(operation, amount, before, after, reason));
        String operatorUsername = RequestContext.get() == null ? null : RequestContext.get().getUsername();
        return toBalanceResponse(saved, operatorUsername);
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
        if (previous != isDeleted) {
            bumpAuthVersion(user);
        }
        userRepository.save(user);
        String action = isDeleted == 1 ? "user.disable" : "user.enable";
        String state = isDeleted == 1 ? "已禁用" : "正常";
        String previousState = previous == 1 ? "已禁用" : "正常";
        logUserOperation(action, id, "管理员修改用户状态：" + previousState + " -> " + state);
    }

    @Override
    @Transactional
    public AdminResetPasswordResponse resetPassword(UUID id) {
        User user = requireRegularUser(id, true);
        String temporaryPassword = generateTemporaryPassword();
        user.setPasswordHash(passwordEncoder.encode(temporaryPassword));
        user.setFailedLoginAttempts(0);
        user.setLockUntil(null);
        bumpAuthVersion(user);
        userRepository.save(user);
        logUserOperation("user.reset_password", id, "管理员重置用户密码（密码内容不记录）");
        return new AdminResetPasswordResponse(temporaryPassword);
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

    private Map<UUID, UserOrderMetricsProjection> loadOrderMetrics(Collection<User> users) {
        if (users.isEmpty()) {
            return Collections.emptyMap();
        }
        List<UUID> userIds = users.stream().map(User::getId).toList();
        return loadOrderMetrics(userIds);
    }

    private Map<UUID, UserOrderMetricsProjection> loadOrderMetrics(List<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<UserOrderMetricsProjection> metrics = orderRepository.findSuccessfulMetricsByUserIdIn(userIds);
        if (metrics == null || metrics.isEmpty()) {
            return Collections.emptyMap();
        }
        return metrics.stream().collect(Collectors.toMap(
                UserOrderMetricsProjection::getUserId, Function.identity()));
    }

    private Map<UUID, List<OrderItem>> loadOrderItems(List<Order> orders) {
        if (orders.isEmpty()) {
            return Collections.emptyMap();
        }
        List<UUID> orderIds = orders.stream().map(Order::getId).toList();
        return orderItemRepository.findByOrderIdIn(orderIds).stream()
                .collect(Collectors.groupingBy(OrderItem::getOrderId));
    }

    private Map<UUID, String> loadOperatorNames(List<BalanceTransaction> transactions) {
        List<UUID> operatorIds = transactions.stream()
                .map(BalanceTransaction::getOperatorId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (operatorIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return userRepository.findAllById(operatorIds).stream()
                .collect(Collectors.toMap(User::getId, User::getUsername));
    }

    private AdminUserListResponse toListResponse(User user, UserOrderMetricsProjection metrics, UserLevel level) {
        return new AdminUserListResponse(
                user.getId(), user.getUserNo(), user.getUsername(), user.getEmail(),
                user.getUserLevelId(), level == null ? null : level.getName(),
                normalizeMoney(user.getBalance()),
                orderCount(metrics),
                totalSpent(metrics),
                user.getIsDeleted(), user.getCreatedAt());
    }

    private AdminUserDetailResponse toDetailResponse(User user, UserOrderMetricsProjection metrics, UserLevel level) {
        return new AdminUserDetailResponse(
                user.getId(), user.getUserNo(), user.getUsername(), user.getEmail(), user.getPhone(),
                user.getUserLevelId(), level == null ? null : level.getName(),
                normalizeMoney(user.getBalance()),
                orderCount(metrics),
                totalSpent(metrics),
                user.getIsDeleted(), user.getCreatedAt(), user.getLastLoginIp(), user.getLastLoginAt(),
                ipLocation(user.getLastLoginIp()));
    }

    private Map<UUID, UserLevel> loadLevels(Collection<User> users) {
        if (users.isEmpty()) return Collections.emptyMap();
        List<UUID> ids = users.stream().map(User::getUserLevelId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Collections.emptyMap();
        return userLevelRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(UserLevel::getId, Function.identity()));
    }

    private long orderCount(UserOrderMetricsProjection metrics) {
        return metrics == null || metrics.getOrderCount() == null ? 0L : metrics.getOrderCount();
    }

    private BigDecimal totalSpent(UserOrderMetricsProjection metrics) {
        return metrics == null ? ZERO : nonNullMoney(metrics.getTotalSpent());
    }

    private AdminUserOrderResponse toOrderResponse(Order order, List<OrderItem> orderItems) {
        List<AdminUserOrderResponse.AdminUserOrderItemResponse> items = orderItems.stream()
                .map(this::toOrderItemResponse)
                .toList();
        return new AdminUserOrderResponse(
                order.getId(), order.getOrderNo(), order.getTotalAmount(), order.getActualAmount(), order.getStatus(),
                order.getOrderType(), order.getPaymentMethod(), order.getPaymentChannelCode(), order.getCreatedAt(),
                order.getPaidAt(), order.getDeliveredAt(), items);
    }

    private AdminUserOrderResponse.AdminUserOrderItemResponse toOrderItemResponse(OrderItem item) {
        return new AdminUserOrderResponse.AdminUserOrderItemResponse(
                item.getId(), item.getProductNo(), item.getProductTitle(), item.getSpecName(), item.getQuantity(),
                item.getUnitPrice(), item.getSubtotal());
    }

    private BalanceTransactionResponse toBalanceResponse(BalanceTransaction transaction, String operatorUsername) {
        return new BalanceTransactionResponse(
                transaction.getId(), transaction.getType(), transaction.getAmount(), transaction.getDelta(),
                transaction.getBalanceBefore(), transaction.getBalanceAfter(), transaction.getReason(),
                transaction.getOperatorId(), operatorUsername, transaction.getOrderId(), transaction.getCreatedAt());
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

    private String normalizeReason(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private String adjustmentLogDetail(String operation, BigDecimal amount,
                                       BigDecimal before, BigDecimal after, String reason) {
        String detail = "管理员" + operation + "余额：" + formatMoney(amount)
                + "，余额：" + formatMoney(before) + " -> " + formatMoney(after);
        return reason == null ? detail : detail + "，原因：" + reason;
    }

    private String generateTemporaryPassword() {
        return "AIYUN" + String.format(Locale.ROOT, "%08d",
                SECURE_RANDOM.nextInt(100_000_000));
    }

    private void bumpAuthVersion(User user) {
        if (user.getAuthVersion() == Integer.MAX_VALUE) {
            throw new IllegalStateException("User auth version overflow");
        }
        user.setAuthVersion(user.getAuthVersion() + 1);
    }

    private IpLocationResponse ipLocation(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return IpLocationResponse.unknown();
        }
        try {
            IpLocationResponse response = ipLocationService.resolve(ipAddress);
            return response == null ? IpLocationResponse.unknown() : response;
        } catch (RuntimeException exception) {
            return IpLocationResponse.unknown();
        }
    }

    private String normalizeEmail(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
