package com.myshop.service.impl;

import com.myshop.constant.ErrorCode;
import com.myshop.constant.UserRole;
import com.myshop.entity.CartItem;
import com.myshop.entity.User;
import com.myshop.exception.BusinessException;
import com.myshop.model.request.LoginRequest;
import com.myshop.model.request.RegisterRequest;
import com.myshop.model.response.AuthResult;
import com.myshop.model.response.CaptchaResponse;
import com.myshop.model.response.UserProfileResponse;
import com.myshop.repository.CartItemRepository;
import com.myshop.repository.UserRepository;
import com.myshop.repository.UserNumberSequenceRepository;
import com.myshop.service.AuthService;
import com.myshop.service.ProductRules;
import com.myshop.utils.CaptchaUtils;
import com.myshop.utils.JwtUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final UserNumberSequenceRepository userNumberSequenceRepository;
    private final CartItemRepository cartItemRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final CaptchaUtils captchaUtils;

    @Override
    public CaptchaResponse generateCaptcha() {
        CaptchaUtils.CaptchaResult result = captchaUtils.generate();
        return new CaptchaResponse(result.captchaId(), result.imageBase64());
    }

    @Override
    @Transactional
    public AuthResult register(RegisterRequest request) {
        if (!captchaUtils.verify(request.getCaptchaId(), request.getCaptcha())) {
            throw new BusinessException(ErrorCode.CAPTCHA_INVALID, "验证码错误或已过期");
        }
        String normalizedEmail = normalizeEmail(request.getEmail());
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new BusinessException(ErrorCode.USERNAME_EXISTS, "用户名已存在");
        }
        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new BusinessException(ErrorCode.EMAIL_EXISTS, "该邮箱已注册");
        }

        var sequence = userNumberSequenceRepository.findByIdForUpdate(1);
        if (sequence == null || sequence.getNextUserNo() == null) {
            throw new IllegalStateException("user_number_sequences is not initialized");
        }
        long userNo = sequence.getNextUserNo();
        if (userNo <= 0 || userNo == Long.MAX_VALUE) {
            throw new IllegalStateException("No user number is available");
        }
        // The counter row is locked for the whole registration transaction.
        // The existence check is a defensive shield for an operator-edited
        // database; allocation itself never derives from MAX(user_no).
        while (userRepository.existsByRoleAndUserNo(UserRole.USER, userNo)) {
            userNo++;
            if (userNo <= 0 || userNo == Long.MAX_VALUE) {
                throw new IllegalStateException("No user number is available");
            }
        }
        sequence.setNextUserNo(userNo + 1);
        userNumberSequenceRepository.save(sequence);

        User user = new User();
        user.setUsername(request.getUsername());
        user.setEmail(normalizedEmail);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setRole(UserRole.USER);
        user.setUserNo(userNo);
        userRepository.saveAndFlush(user);

        String token = jwtUtils.generateToken(user.getId(), user.getUsername(), user.getRole().name());
        return new AuthResult(token, UserProfileResponse.from(user));
    }

    /** 连续登录失败上限 */
    private static final int MAX_FAILED_ATTEMPTS = 5;
    /** 账户锁定时长（分钟） */
    private static final int LOCK_DURATION_MINUTES = 15;

    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public AuthResult login(LoginRequest request, String sessionToken) {
        String account = request.getAccount().trim();
        User user = userRepository.findByUsername(account)
                .or(() -> userRepository.findByEmailIgnoreCase(normalizeEmailIfEmailLike(account)))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS, "用户名或密码错误"));

        if (user.getIsDeleted() == 1) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED, "该账号已被禁用");
        }

        // 账户锁定检查
        if (user.getLockUntil() != null) {
            if (user.getLockUntil().isAfter(java.time.LocalDateTime.now())) {
                long remainMinutes = java.time.Duration.between(java.time.LocalDateTime.now(), user.getLockUntil()).toMinutes() + 1;
                throw new BusinessException(ErrorCode.ACCOUNT_LOCKED,
                        "账号已被锁定，请 " + remainMinutes + " 分钟后再试");
            }
            // 锁定已过期：重置失败计数，给予完整的重试机会
            user.setFailedLoginAttempts(0);
            user.setLockUntil(null);
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            // 记录失败次数
            user.setFailedLoginAttempts(user.getFailedLoginAttempts() + 1);
            if (user.getFailedLoginAttempts() >= MAX_FAILED_ATTEMPTS) {
                user.setLockUntil(java.time.LocalDateTime.now().plusMinutes(LOCK_DURATION_MINUTES));
                userRepository.save(user);
                log.warn("Account locked due to {} failed login attempts: {}", MAX_FAILED_ATTEMPTS, user.getUsername());
                throw new BusinessException(ErrorCode.ACCOUNT_LOCKED,
                        "连续登录失败 " + MAX_FAILED_ATTEMPTS + " 次，账号已锁定 " + LOCK_DURATION_MINUTES + " 分钟");
            }
            userRepository.save(user);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "用户名或密码错误");
        }

        // 登录成功：重置失败计数和锁定状态
        if (user.getFailedLoginAttempts() > 0 || user.getLockUntil() != null) {
            user.setFailedLoginAttempts(0);
            user.setLockUntil(null);
            userRepository.save(user);
        }

        // Merge guest cart on login
        if (StringUtils.hasText(sessionToken)) {
            mergeCart(sessionToken, user.getId());
        }

        String token = jwtUtils.generateToken(user.getId(), user.getUsername(), user.getRole().name());
        return new AuthResult(token, UserProfileResponse.from(user));
    }

    @Override
    public void logout() {
        // Stateless JWT - client discards token
        log.debug("User logged out");
    }

    private void mergeCart(String sessionToken, java.util.UUID userId) {
        List<CartItem> guestItems = cartItemRepository.findBySessionToken(sessionToken);
        for (CartItem guestItem : guestItems) {
            Optional<CartItem> existing = cartItemRepository.findByUserIdAndProductId(userId, guestItem.getProductId())
                    .stream()
                    .filter(item -> item.getSpecId() == null
                            ? guestItem.getSpecId() == null
                            : item.getSpecId().equals(guestItem.getSpecId()))
                    .filter(item -> ProductRules.sameCustomerInputs(item.getCustomerInputs(), guestItem.getCustomerInputs()))
                    .findFirst();
            if (existing.isPresent()) {
                // Merge quantity into user's existing item, then delete the guest item
                existing.get().setQuantity(existing.get().getQuantity() + guestItem.getQuantity());
                cartItemRepository.save(existing.get());
                cartItemRepository.delete(guestItem);
            } else {
                // Reassign guest item to user
                guestItem.setUserId(userId);
                guestItem.setSessionToken(null);
                cartItemRepository.save(guestItem);
            }
        }
    }

    private String normalizeEmail(String email) {
        if (email == null) return null;
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeEmailIfEmailLike(String account) {
        // findByEmailIgnoreCase is safe for usernames too, but keeping the
        // normalized path explicit documents the one canonical email form.
        return normalizeEmail(account);
    }
}
