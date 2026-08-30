package com.myshop.service.impl;

import com.myshop.common.PageResult;
import com.myshop.constant.ErrorCode;
import com.myshop.constant.UserRole;
import com.myshop.entity.OperationLog;
import com.myshop.entity.User;
import com.myshop.exception.BusinessException;
import com.myshop.model.request.CreateAdministratorRequest;
import com.myshop.model.request.ResetAdministratorPasswordRequest;
import com.myshop.model.request.ToggleAdministratorStatusRequest;
import com.myshop.model.request.UpdateAdministratorRequest;
import com.myshop.model.response.AdminOperationLogResponse;
import com.myshop.model.response.AdministratorResponse;
import com.myshop.repository.OperationLogRepository;
import com.myshop.repository.UserRepository;
import com.myshop.service.AdministratorService;
import com.myshop.service.OperationLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AdministratorServiceImpl implements AdministratorService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9]{3,20}$");
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("^[!-~]{6,72}$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final UserRepository userRepository;
    private final OperationLogService operationLogService;
    private final OperationLogRepository operationLogRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional(readOnly = true)
    public PageResult<AdministratorResponse> listAdministrators(String keyword, Integer isDeleted,
                                                                  int page, int pageSize) {
        requirePage(page, pageSize);
        validateStatus(isDeleted);
        String normalizedKeyword = blankToNull(keyword);
        Page<User> result = userRepository.findAdministrators(normalizedKeyword, isDeleted,
                PageRequest.of(page - 1, pageSize));
        return PageResult.of(result, result.getContent().stream().map(this::toResponse).toList());
    }

    @Override
    @Transactional(readOnly = true)
    public AdministratorResponse getAdministrator(UUID id) {
        return toResponse(requireAdministrator(id, false));
    }

    @Override
    @Transactional
    public AdministratorResponse createAdministrator(CreateAdministratorRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "管理员创建参数不能为空");
        }
        String username = normalizeRequired(request.getUsername(), "管理员用户名不能为空");
        String password = request.getPassword();
        validateUsername(username);
        validatePassword(password);
        String email = normalizeEmail(request.getEmail());
        String phone = normalizePhone(request.getPhone());
        validateOptionalContact(email, phone);
        validateIdentityAvailability(username, email, phone, null);

        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPhone(phone);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole(UserRole.ADMIN);
        user.setUserNo(null);
        try {
            User saved = userRepository.saveAndFlush(user);
            logAdministratorOperation("administrator.create", saved.getId(),
                    "创建普通管理员：" + saved.getUsername());
            return toResponse(saved);
        } catch (DataIntegrityViolationException exception) {
            throw conflictFromConstraint(exception);
        }
    }

    @Override
    @Transactional
    public AdministratorResponse updateAdministrator(UUID id, UpdateAdministratorRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "管理员修改参数不能为空");
        }
        User user = requireMutableAdministrator(id);
        String email = normalizeEmail(request.getEmail());
        String phone = normalizePhone(request.getPhone());
        validateOptionalContact(email, phone);
        validateIdentityAvailability(user.getUsername(), email, phone, id);
        String oldEmail = user.getEmail();
        String oldPhone = user.getPhone();
        user.setEmail(email);
        user.setPhone(phone);
        try {
            User saved = userRepository.saveAndFlush(user);
            logAdministratorOperation("administrator.update", id,
                    "修改管理员联系方式：email " + printable(oldEmail) + " -> " + printable(email)
                            + "，phone " + printable(oldPhone) + " -> " + printable(phone));
            return toResponse(saved);
        } catch (DataIntegrityViolationException exception) {
            throw conflictFromConstraint(exception);
        }
    }

    @Override
    @Transactional
    public void toggleAdministrator(UUID id, ToggleAdministratorStatusRequest request) {
        User user = requireMutableAdministrator(id);
        Integer isDeleted = request == null ? null : request.getIsDeleted();
        if (isDeleted == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "管理员状态不能为空");
        }
        validateStatus(isDeleted);
        int previous = user.getIsDeleted();
        user.setIsDeleted(isDeleted);
        userRepository.save(user);
        String action = isDeleted == 1 ? "administrator.disable" : "administrator.enable";
        logAdministratorOperation(action, id,
                "修改管理员状态：" + (previous == 1 ? "已禁用" : "正常") + " -> "
                        + (isDeleted == 1 ? "已禁用" : "正常"));
    }

    @Override
    @Transactional
    public void resetAdministratorPassword(UUID id, ResetAdministratorPasswordRequest request) {
        User user = requireMutableAdministrator(id);
        String password = request == null ? null : request.getNewPassword();
        validatePassword(password);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setFailedLoginAttempts(0);
        user.setLockUntil(null);
        userRepository.save(user);
        logAdministratorOperation("administrator.reset_password", id,
                "重置管理员密码（密码内容不记录）");
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<AdminOperationLogResponse> listOperationLogs(UUID id, int page, int pageSize) {
        requirePage(page, pageSize);
        requireAdministrator(id, false);
        Page<OperationLog> result = operationLogRepository
                .findByTargetTypeAndTargetIdOrderByCreatedAtDesc(
                        "ADMINISTRATOR", id.toString(), PageRequest.of(page - 1, pageSize));
        return PageResult.of(result, result.getContent().stream().map(this::toLogResponse).toList());
    }

    private User requireAdministrator(UUID id, boolean locked) {
        User user = locked
                ? userRepository.findByIdForUpdate(id).orElse(null)
                : userRepository.findById(id).orElse(null);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "管理员不存在");
        }
        if (user.getRole() != UserRole.ADMIN && user.getRole() != UserRole.SUPER_ADMIN) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "目标账号不是管理员");
        }
        return user;
    }

    private User requireMutableAdministrator(UUID id) {
        User user = requireAdministrator(id, true);
        if (user.getRole() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "固定超级管理员只能查看，不能被此页面管理");
        }
        return user;
    }

    private void validateIdentityAvailability(String username, String email, String phone, UUID excludedId) {
        if (userRepository.existsByUsernameIgnoreCase(username)
                && (excludedId == null || !usernameBelongsToExcluded(username, excludedId))) {
            throw new BusinessException(ErrorCode.USERNAME_EXISTS, "用户名已存在");
        }
        if (email != null && userRepository.existsByEmailIgnoreCase(email)
                && (excludedId == null || !emailBelongsToExcluded(email, excludedId))) {
            throw new BusinessException(ErrorCode.EMAIL_EXISTS, "该邮箱已被其他用户使用");
        }
        if (phone != null && userRepository.existsByPhone(phone)
                && (excludedId == null || !phoneBelongsToExcluded(phone, excludedId))) {
            throw new BusinessException(ErrorCode.PHONE_EXISTS, "该手机号已注册");
        }
        if (phone != null && userRepository.existsByUsernameIgnoreCase(phone)
                && (excludedId == null || !usernameBelongsToExcluded(phone, excludedId))) {
            throw new BusinessException(ErrorCode.PHONE_EXISTS, "该手机号已被其他账号使用");
        }
        if (userRepository.existsByPhone(username)
                && (excludedId == null || !phoneBelongsToExcluded(username, excludedId))) {
            throw new BusinessException(ErrorCode.IDENTIFIER_CONFLICT, "管理员用户名不能与现有手机号相同");
        }
    }

    private boolean usernameBelongsToExcluded(String value, UUID excludedId) {
        return userRepository.findByUsernameIgnoreCase(value)
                .map(user -> user.getId().equals(excludedId)).orElse(false);
    }

    private boolean emailBelongsToExcluded(String value, UUID excludedId) {
        return userRepository.findByEmailIgnoreCase(value)
                .map(user -> user.getId().equals(excludedId)).orElse(false);
    }

    private boolean phoneBelongsToExcluded(String value, UUID excludedId) {
        return userRepository.findByPhone(value)
                .map(user -> user.getId().equals(excludedId)).orElse(false);
    }

    private void validateUsername(String username) {
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "管理员用户名必须为 3-20 位英文字母或数字");
        }
    }

    private void validatePassword(String password) {
        if (password == null || !PASSWORD_PATTERN.matcher(password).matches()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "管理员密码必须为 6-72 位 ASCII 可见字符");
        }
    }

    private void validateOptionalContact(String email, String phone) {
        if (email != null && !EMAIL_PATTERN.matcher(email).matches()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邮箱格式不正确");
        }
        if (phone != null && !PHONE_PATTERN.matcher(phone).matches()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "手机号格式不正确");
        }
    }

    private String normalizeRequired(String value, String message) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, message);
        }
        return normalized;
    }

    private String normalizeEmail(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private String normalizePhone(String value) {
        return blankToNull(value);
    }

    private String blankToNull(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private void validateStatus(Integer isDeleted) {
        if (isDeleted != null && isDeleted != 0 && isDeleted != 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "管理员状态只能为 0 或 1");
        }
    }

    private void requirePage(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分页参数无效");
        }
    }

    private void logAdministratorOperation(String action, UUID targetId, String detail) {
        operationLogService.logCurrentRequest(action, "ADMINISTRATOR", targetId.toString(), detail);
    }

    private AdministratorResponse toResponse(User user) {
        return new AdministratorResponse(user.getId(), user.getUsername(), user.getEmail(),
                user.getPhone(), user.getRole(), user.getIsDeleted(), user.getCreatedAt());
    }

    private AdminOperationLogResponse toLogResponse(OperationLog log) {
        return new AdminOperationLogResponse(log.getId(), log.getUserId(), log.getUsername(), log.getAction(),
                log.getTargetType(), log.getTargetId(), log.getDetail(), log.getIpAddress(), log.getCreatedAt());
    }

    private String printable(String value) {
        return value == null || value.isBlank() ? "空" : value;
    }

    private BusinessException conflictFromConstraint(DataIntegrityViolationException exception) {
        String detail = String.valueOf(exception.getMostSpecificCause().getMessage());
        if (detail.contains("uq_users_email_normalized")) {
            return new BusinessException(ErrorCode.EMAIL_EXISTS, "该邮箱已被其他用户使用");
        }
        if (detail.contains("uq_users_phone")) {
            return new BusinessException(ErrorCode.PHONE_EXISTS, "该手机号已注册");
        }
        if (detail.contains("uq_users_username_ci")) {
            return new BusinessException(ErrorCode.USERNAME_EXISTS, "用户名已存在");
        }
        return new BusinessException(ErrorCode.BAD_REQUEST, "管理员信息已被其他请求占用，请重试");
    }
}
