package com.myshop.service.impl;

import com.myshop.constant.ErrorCode;
import com.myshop.entity.SiteConfig;
import com.myshop.entity.UserNumberSequence;
import com.myshop.exception.BusinessException;
import com.myshop.constant.UserRole;
import com.myshop.model.request.UserNumberConfigUpdateRequest;
import com.myshop.model.response.UserNumberConfigResponse;
import com.myshop.repository.SiteConfigRepository;
import com.myshop.repository.UserNumberSequenceRepository;
import com.myshop.repository.UserRepository;
import com.myshop.service.SiteConfigService;
import com.myshop.service.AnnouncementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class SiteConfigServiceImpl implements SiteConfigService {

    private final SiteConfigRepository siteConfigRepository;
    private final UserNumberSequenceRepository userNumberSequenceRepository;
    private final UserRepository userRepository;
    private final AnnouncementService announcementService;
    private com.myshop.service.MediaAssetRegistry mediaAssets;
    @org.springframework.beans.factory.annotation.Autowired
    public void setMediaAssets(com.myshop.service.MediaAssetRegistry value) { this.mediaAssets = value; }

    @org.springframework.beans.factory.annotation.Value("${turnstile.site-key:}")
    private String turnstileSiteKey;

    @org.springframework.beans.factory.annotation.Value("${turnstile.secret-key:}")
    private String turnstileSecretKey;

    @org.springframework.beans.factory.annotation.Value("${app.public-site-url:http://localhost:3000}")
    private String publicSiteUrl = "http://localhost:3000";

    private static final List<String> PUBLIC_KEYS = List.of(
            "site_name", "favicon_url", "maintenance_enabled", "maintenance_message", "custom_css"
    );

    /** F16: 管理员允许编辑的配置键白名单 — 防止写入系统内部键或注入任意配置 */
    private static final Set<String> EDITABLE_KEYS = Set.of(
            // 站点基础
            "site_name", "favicon_url",
            // 维护模式
            "maintenance_enabled", "maintenance_message",
            // 自定义样式
            "custom_css",
            // 系统参数
            "order_expire_minutes", "rate_limit_per_second"
    );

    /** F15: CSS 危险模式 — 用于过滤 custom_css 中的 XSS 向量 */
    private static final Pattern CSS_DANGEROUS_PATTERNS = Pattern.compile(
            "(?i)(expression\\s*\\(|javascript\\s*:|@import\\s|\\\\00|behavior\\s*:|" +
            "-moz-binding\\s*:|url\\s*\\(\\s*[\"']?\\s*javascript)",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public Map<String, Object> getPublicConfig() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : PUBLIC_KEYS) {
            siteConfigRepository.findByConfigKey(key).ifPresent(c -> {
                String val = c.getConfigValue();
                if ("maintenance_enabled".equals(key) && ("true".equalsIgnoreCase(val) || "false".equalsIgnoreCase(val))) {
                    result.put(key, Boolean.parseBoolean(val));
                } else {
                    result.put(key, val);
                }
            });
        }
        result.put("main_site_url", publicSiteUrl);
        // F15: 对 custom_css 进行安全过滤，防止存储型 XSS
        if (result.containsKey("custom_css") && result.get("custom_css") instanceof String css) {
            result.put("custom_css", sanitizeCss(css));
        }
        boolean turnstileEnabled = siteConfigRepository.findByConfigKey("turnstile_enabled")
                .map(config -> com.myshop.config.RiskConfigPolicy.runtimeBoolean(
                        "turnstile_enabled", config.getConfigValue()))
                .orElse(false);
        turnstileEnabled = turnstileEnabled && turnstileConfigured();
        result.put("turnstile_enabled", turnstileEnabled);
        if (turnstileEnabled) result.put("turnstile_site_key", turnstileSiteKey);
        var subsite = com.myshop.context.SiteRequestContext.getSite();
        if (subsite != null) {
            result.put("site_name", subsite.getName());
            // A subsite without a custom icon must not inherit the main site's icon.
            result.put("favicon_url", subsite.getFaviconUrl());
        }
        result.putAll(announcementService.channels(com.myshop.context.SiteRequestContext.getSiteId()));
        return result;
    }

    private boolean turnstileConfigured() {
        return turnstileSiteKey != null && !turnstileSiteKey.isBlank()
                && turnstileSecretKey != null && !turnstileSecretKey.isBlank();
    }

    @Override
    public List<?> getAllConfigs() {
        return siteConfigRepository.findAll().stream()
                .filter(c -> !Set.of("announcement_enabled", "announcement", "popup_enabled", "popup_content").contains(c.getConfigKey()))
                .map(c -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("config_key", c.getConfigKey());
                    map.put("config_value", c.getConfigValue());
                    map.put("config_group", c.getConfigGroup());
                    return map;
                }).toList();
    }

    @Override
    @Transactional
    public void updateConfigs(List<Map<String, String>> configs) {
        int lastFavicon = -1;
        for (int i = 0; i < configs.size(); i++) if ("favicon_url".equals(configs.get(i).get("config_key"))) lastFavicon = i;
        var faviconReferences = lastFavicon < 0 || mediaAssets == null ? null
                : mediaAssets.lockExistingSiteIcon(configs.get(lastFavicon).get("config_value"));
        for (int i = 0; i < configs.size(); i++) {
            Map<String, String> item = configs.get(i);
            String key = item.get("config_key");
            if ("favicon_url".equals(key) && i != lastFavicon) continue;
            String value = item.get("config_value");
            // F16: 只允许白名单内的 key 被修改，防止注入系统内部配置
            if (key == null || !EDITABLE_KEYS.contains(key)) {
                log.warn("Rejected config update for non-editable key: {}", key);
                continue;
            }
            if (com.myshop.config.RiskConfigPolicy.isRiskKey(key)) {
                value = com.myshop.config.RiskConfigPolicy.validateSiteConfigValue(key, value);
            }
            // F15: custom_css 写入时也做安全过滤
            if ("custom_css".equals(key) && value != null) {
                value = sanitizeCss(value);
            }
            SiteConfig config = ("favicon_url".equals(key) ? siteConfigRepository.findByConfigKeyForUpdate(key)
                    : siteConfigRepository.findByConfigKey(key))
                    .orElseGet(() -> {
                        SiteConfig c = new SiteConfig();
                        c.setConfigKey(key);
                        return c;
                    });
            if ("favicon_url".equals(key) && mediaAssets != null)
                mediaAssets.verifyHistoricalSiteIconReferences(config.getConfigValue(), faviconReferences);
            config.setConfigValue(value);
            SiteConfig saved = siteConfigRepository.saveAndFlush(config);
            if ("favicon_url".equals(key) && mediaAssets != null)
                mediaAssets.replaceReferences("SITE_CONFIG", saved.getId(), faviconReferences, "favicon_url");
        }
    }

    @Override
    @Transactional
    public void toggleMaintenance(boolean enabled) {
        SiteConfig config = siteConfigRepository.findByConfigKey("maintenance_enabled")
                .orElseGet(() -> {
                    SiteConfig c = new SiteConfig();
                    c.setConfigKey("maintenance_enabled");
                    c.setConfigGroup("site");
                    return c;
                });
        config.setConfigValue(String.valueOf(enabled));
        siteConfigRepository.save(config);
    }

    @Override
    @Transactional(readOnly = true)
    public UserNumberConfigResponse getUserNumberConfig() {
        UserNumberSequence sequence = requireUserNumberSequence(false);
        return new UserNumberConfigResponse(sequence.getStartUserNo(), sequence.getNextUserNo(),
                userRepository.countByRole(UserRole.USER) > 0);
    }

    @Override
    @Transactional
    public void updateUserNumberConfig(UserNumberConfigUpdateRequest request) {
        if (request == null || request.getStartUserNo() == null || request.getNextUserNo() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户编号配置不能为空");
        }
        long start = request.getStartUserNo();
        long next = request.getNextUserNo();
        if (start <= 0 || next <= 0 || next < start) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "下一个用户编号必须大于等于起始值，且编号必须大于 0");
        }

        UserNumberSequence sequence = requireUserNumberSequence(true);
        long existingUsers = userRepository.countByRole(UserRole.USER);
        Long maxUserNo = userRepository.findMaxUserNoByRole(UserRole.USER);
        if (existingUsers == 0 && next != start) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "还没有普通用户时，起始编号和下一个用户编号必须一致");
        }
        if (existingUsers > 0) {
            if (start != sequence.getStartUserNo()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "已有普通用户后，起始编号是历史值，只读且不允许修改");
            }
            if (maxUserNo == null || next <= maxUserNo) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "下一个用户编号必须严格大于当前最大用户编号");
            }
        }

        sequence.setStartUserNo(start);
        sequence.setNextUserNo(next);
        userNumberSequenceRepository.save(sequence);
    }

    private UserNumberSequence requireUserNumberSequence(boolean locked) {
        UserNumberSequence sequence = locked
                ? userNumberSequenceRepository.findByIdForUpdate(1)
                : userNumberSequenceRepository.findById(1).orElse(null);
        if (sequence == null) {
            throw new IllegalStateException("user_number_sequences is not initialized");
        }
        return sequence;
    }

    /**
     * 过滤 CSS 中的危险内容，防止存储型 XSS。
     * 移除 HTML 标签和已知 CSS XSS 向量（expression/javascript:/behavior 等）。
     */
    private String sanitizeCss(String css) {
        if (css == null) return null;
        // 移除所有 HTML 标签（防止 </style><script>... 注入）
        css = css.replaceAll("<[^>]*>", "");
        // 移除危险 CSS 模式
        css = CSS_DANGEROUS_PATTERNS.matcher(css).replaceAll("/* blocked */");
        return css;
    }
}
