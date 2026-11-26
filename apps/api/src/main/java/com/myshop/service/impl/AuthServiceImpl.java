package com.myshop.service.impl;

import com.myshop.constant.ErrorCode;
import com.myshop.constant.UserRole;
import com.myshop.entity.User;
import com.myshop.entity.UserLevel;
import com.myshop.exception.BusinessException;
import com.myshop.model.request.LoginRequest;
import com.myshop.model.request.RegisterRequest;
import com.myshop.model.response.AuthResult;
import com.myshop.model.response.CaptchaResponse;
import com.myshop.model.response.UserProfileResponse;
import com.myshop.repository.UserRepository;
import com.myshop.repository.UserNumberSequenceRepository;
import com.myshop.repository.UserLevelRepository;
import com.myshop.service.AuthService;
import com.myshop.utils.CaptchaUtils;
import com.myshop.validation.RegistrationRules;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Optional;
import java.util.Locale;
import java.time.LocalDateTime;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final Pattern USERNAME_PATTERN = RegistrationRules.USERNAME;
    private static final Pattern EMAIL_PATTERN = RegistrationRules.EMAIL;
    private static final Pattern PASSWORD_PATTERN = RegistrationRules.PASSWORD;
    private static final Pattern PHONE_PATTERN = RegistrationRules.PHONE;

    private final UserRepository userRepository;
    private final UserNumberSequenceRepository userNumberSequenceRepository;
    private final UserLevelRepository userLevelRepository;
    private final PasswordEncoder passwordEncoder;
    private final com.myshop.service.AuthSessionService authSessions;
    private final CaptchaUtils captchaUtils;

    @org.springframework.beans.factory.annotation.Value("${app.public-site-url:http://localhost:3000}")
    private String publicSiteUrl = "http://localhost:3000";
    private com.myshop.repository.SubsiteRepository subsites;
    @org.springframework.beans.factory.annotation.Autowired
    public void setSubsites(com.myshop.repository.SubsiteRepository subsites) { this.subsites = subsites; }

    @Override
    public CaptchaResponse generateCaptcha() {
        CaptchaUtils.CaptchaResult result = captchaUtils.generate();
        return new CaptchaResponse(result.captchaId(), result.imageBase64());
    }

    @Override
    @Transactional
    public AuthResult register(RegisterRequest request, String clientIp) {
        var site = com.myshop.context.SiteRequestContext.getSite();
        if (site != null) {
            site = subsites.findByIdForUpdate(site.getId()).orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN, "分站不存在"));
            if (!site.isEnabled()) throw new BusinessException(ErrorCode.FORBIDDEN, "该分站已停用，暂不能注册");
        }
        if (!captchaUtils.verify(request.getCaptchaId(), request.getCaptcha())) {
            throw new BusinessException(ErrorCode.CAPTCHA_INVALID, "验证码错误或已过期",
                    java.util.Map.of("field", "captcha", "validation", "captcha", "captcha_consumed", true));
        }
        String username = normalizeUsername(request.getUsername());
        String normalizedEmail = normalizeEmail(request.getEmail());
        String phone = normalizePhone(request.getPhone());
        validateRegistrationFields(username, normalizedEmail, request.getPassword(), phone);

        if (username.equals(phone)) {
            throw new BusinessException(ErrorCode.IDENTIFIER_CONFLICT, "用户名和手机号不能相同",
                    java.util.Map.of("field", "both", "validation", "sameIdentifier", "captcha_consumed", true));
        }

        // Registration, administrator identity writes and bootstrap share this
        // singleton lock before checking cross-field identifier rules. This
        // prevents username/phone checks from racing across those entry points.
        var sequence = userNumberSequenceRepository.findByIdForUpdate(1);
        if (sequence == null || sequence.getNextUserNo() == null) {
            throw new IllegalStateException("user_number_sequences is not initialized");
        }
        UserLevel defaultLevel = userLevelRepository.findActiveDefault()
                .orElseThrow(() -> new IllegalStateException("No active default user level is configured"));

        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new BusinessException(ErrorCode.USERNAME_EXISTS, "用户名已存在",
                    java.util.Map.of("field", "username", "validation", "duplicate", "captcha_consumed", true));
        }
        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new BusinessException(ErrorCode.EMAIL_EXISTS, "该邮箱已注册",
                    java.util.Map.of("field", "email", "validation", "duplicate", "captcha_consumed", true));
        }
        if (userRepository.existsByPhone(phone)) {
            throw new BusinessException(ErrorCode.PHONE_EXISTS, "该手机号已注册",
                    java.util.Map.of("field", "phone", "validation", "duplicate", "captcha_consumed", true));
        }
        if (userRepository.existsByPhone(username)) {
            throw new BusinessException(ErrorCode.IDENTIFIER_CONFLICT, "用户名不能与现有手机号相同",
                    java.util.Map.of("field", "username", "validation", "phoneConflict", "captcha_consumed", true));
        }
        if (userRepository.existsByUsernameIgnoreCase(phone)) {
            throw new BusinessException(ErrorCode.IDENTIFIER_CONFLICT, "手机号与现有用户名冲突",
                    java.util.Map.of("field", "phone", "validation", "usernameConflict", "captcha_consumed", true));
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
        user.setUsername(username);
        user.setEmail(normalizedEmail);
        user.setPhone(phone);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setRole(UserRole.USER);
        user.setUserNo(userNo);
        user.setUserLevelId(defaultLevel.getId());
        user.setSubsiteId(com.myshop.context.SiteRequestContext.getSiteId());
        recordSuccessfulLogin(user, clientIp);
        userRepository.saveAndFlush(user);

        var credential = authSessions.create(user);
        return new AuthResult(credential.value(), UserProfileResponse.from(user, defaultLevel), credential.lifetimeDays());
    }

    @Override
    @Transactional
    public AuthResult login(LoginRequest request, String clientIp) {
        String account = request == null ? null : request.getAccount();
        account = account == null ? null : account.trim();
        if (!StringUtils.hasText(account)) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "账号或密码错误");
        }

        Optional<User> userLookup;
        if (account.contains("@")) {
            userLookup = userRepository.findByEmailForUpdate(normalizeEmail(account));
        } else if (PHONE_PATTERN.matcher(account).matches()) {
            userLookup = userRepository.findByPhoneForUpdate(account);
            if (userLookup.isEmpty()) {
                userLookup = userRepository.findByUsernameForUpdate(account);
            }
        } else {
            userLookup = userRepository.findByUsernameForUpdate(account);
        }
        User user = userLookup
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS, "账号或密码错误"));

        if (user.getIsDeleted() == 1) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED, "该账号已被禁用");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "账号或密码错误");
        }

        if (!com.myshop.service.SubsiteAccess.permits(user)) {
            String home = publicSiteUrl;
            if (user.getSubsiteId() != null && subsites != null) {
                var homeSite = subsites.findById(user.getSubsiteId()).orElse(null);
                String host = java.net.URI.create(publicSiteUrl).getHost();
                if (homeSite != null && host != null && host.contains("."))
                    home = "https://" + homeSite.getPrefix() + host.substring(host.indexOf('.'));
            }
            throw new BusinessException(ErrorCode.FORBIDDEN, "请前往账号归属站点登录", java.util.Map.of("home_url", home));
        }

        recordSuccessfulLogin(user, clientIp);
        userRepository.save(user);

        UserLevel currentLevel = user.getRole() == UserRole.USER && user.getUserLevelId() != null
                ? userLevelRepository.findById(user.getUserLevelId()).orElse(null) : null;
        var credential = authSessions.create(user);
        return new AuthResult(credential.value(), UserProfileResponse.from(user, currentLevel), credential.lifetimeDays());
    }

    private String normalizeEmail(String email) {
        if (email == null) return null;
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeUsername(String username) {
        return username == null ? null : username.trim();
    }

    private String normalizePhone(String phone) {
        return phone == null ? null : phone.trim();
    }

    private void validateRegistrationFields(String username, String email, String password, String phone) {
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "用户名必须为 3-20 位，只能使用英文字母、数字、下划线和连字符",
                    java.util.Map.of("field", "username", "validation", "format", "captcha_consumed", true));
        }
        if (email == null || email.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邮箱不能为空",
                    java.util.Map.of("field", "email", "validation", "required", "captcha_consumed", true));
        }
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邮箱格式不正确",
                    java.util.Map.of("field", "email", "validation", "format", "captcha_consumed", true));
        }
        if (password == null || !PASSWORD_PATTERN.matcher(password).matches()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "密码必须为 6-72 位 ASCII 可见字符",
                    java.util.Map.of("field", "password", "validation", "format", "captcha_consumed", true));
        }
        if (phone == null || !PHONE_PATTERN.matcher(phone).matches()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "手机号格式不正确",
                    java.util.Map.of("field", "phone", "validation", "format", "captcha_consumed", true));
        }
    }

    private void recordSuccessfulLogin(User user, String clientIp) {
        String normalizedIp = clientIp == null ? null : clientIp.trim();
        if (normalizedIp != null && normalizedIp.length() > 45) {
            normalizedIp = null;
        }
        user.setLastLoginIp(normalizedIp == null || normalizedIp.isBlank() ? null : normalizedIp);
        user.setLastLoginAt(LocalDateTime.now());
    }
}
