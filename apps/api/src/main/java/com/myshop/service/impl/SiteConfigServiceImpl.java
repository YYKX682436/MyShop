package com.myshop.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class SiteConfigServiceImpl implements SiteConfigService {

    private final SiteConfigRepository siteConfigRepository;
    private final ObjectMapper objectMapper;
    private final UserNumberSequenceRepository userNumberSequenceRepository;
    private final UserRepository userRepository;

    @org.springframework.beans.factory.annotation.Value("${turnstile.site-key:}")
    private String turnstileSiteKey;

    private static final List<String> PUBLIC_KEYS = List.of(
            "site_name", "site_slogan", "site_description", "logo_url", "favicon_url",
            "announcement_enabled", "announcement", "popup_enabled", "popup_content",
            "contact_email", "contact_items",
            "maintenance_enabled", "maintenance_message", "footer_text", "custom_css"
    );

    /** F16: 管理员允许编辑的配置键白名单 — 防止写入系统内部键或注入任意配置 */
    private static final Set<String> EDITABLE_KEYS = Set.of(
            // 站点基础
            "site_name", "site_slogan", "site_description", "logo_url", "favicon_url",
            // 公告 / 弹窗
            "announcement_enabled", "announcement", "popup_enabled", "popup_content",
            // 联系方式
            "contact_email", "contact_items",
            // 维护模式
            "maintenance_enabled", "maintenance_message",
            // 页脚
            "footer_text",
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
                if ("contact_items".equals(key)) {
                    try {
                        result.put(key, normalizeContactItems(val));
                    } catch (BusinessException exception) {
                        // Values written through this service are valid. If an
                        // operator edited the generic config table manually,
                        // preserve the public array contract safely.
                        log.error("Invalid stored contact_items; exposing an empty array", exception);
                        result.put(key, List.of());
                    }
                } else if ("true".equalsIgnoreCase(val) || "false".equalsIgnoreCase(val)) {
                    result.put(key, Boolean.parseBoolean(val));
                } else {
                    result.put(key, val);
                }
            });
        }
        // Keep the public type stable while an empty database is initializing.
        result.putIfAbsent("contact_items", List.of());
        // F15: 对 custom_css 进行安全过滤，防止存储型 XSS
        if (result.containsKey("custom_css") && result.get("custom_css") instanceof String css) {
            result.put("custom_css", sanitizeCss(css));
        }
        // Turnstile Site Key：仅在后台开关启用时才返回给前端，确保前后端状态一致
        boolean turnstileEnabled = siteConfigRepository.findByConfigKey("turnstile_enabled")
                .map(c -> "true".equalsIgnoreCase(c.getConfigValue()))
                .orElse(false);
        if (turnstileEnabled && turnstileSiteKey != null && !turnstileSiteKey.isBlank()) {
            result.put("turnstile_site_key", turnstileSiteKey);
        }
        return result;
    }

    @Override
    public List<?> getAllConfigs() {
        return siteConfigRepository.findAll().stream()
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
        for (Map<String, String> item : configs) {
            String key = item.get("config_key");
            String value = item.get("config_value");
            // F16: 只允许白名单内的 key 被修改，防止注入系统内部配置
            if (key == null || !EDITABLE_KEYS.contains(key)) {
                log.warn("Rejected config update for non-editable key: {}", key);
                continue;
            }
            // F15: custom_css 写入时也做安全过滤
            if ("custom_css".equals(key) && value != null) {
                value = sanitizeCss(value);
            }
            if ("contact_items".equals(key)) {
                value = serializeContactItems(value);
            }
            SiteConfig config = siteConfigRepository.findByConfigKey(key)
                    .orElseGet(() -> {
                        SiteConfig c = new SiteConfig();
                        c.setConfigKey(key);
                        return c;
                    });
            config.setConfigValue(value);
            siteConfigRepository.save(config);
        }
    }

    private String serializeContactItems(String value) {
        try {
            return objectMapper.writeValueAsString(normalizeContactItems(value));
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "联系方式配置无法序列化");
        }
    }

    private List<Map<String, String>> normalizeContactItems(String value) {
        if (value == null || value.isBlank()) {
            throw invalidContactItems("必须是 JSON 数组");
        }

        final JsonNode root;
        try (JsonParser parser = objectMapper.getFactory().createParser(value)) {
            root = objectMapper.readTree(parser);
            if (parser.nextToken() != null) {
                throw invalidContactItems("必须是有效 JSON 数组");
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException exception) {
            throw invalidContactItems("必须是有效 JSON 数组");
        }
        if (root == null || !root.isArray()) {
            throw invalidContactItems("必须是 JSON 数组");
        }
        if (root.size() > 3) {
            throw invalidContactItems("最多配置 3 项");
        }

        List<Map<String, String>> normalized = new ArrayList<>(root.size());
        for (int index = 0; index < root.size(); index++) {
            JsonNode item = root.get(index);
            if (item == null || !item.isObject()) {
                throw invalidContactItems("第" + (index + 1) + "项必须是对象");
            }

            Iterator<String> fieldNames = item.fieldNames();
            while (fieldNames.hasNext()) {
                String fieldName = fieldNames.next();
                if (!Set.of("name", "url", "qr_url").contains(fieldName)) {
                    throw invalidContactItems("第" + (index + 1) + "项包含不支持的字段「" + fieldName + "」");
                }
            }

            String name = requiredText(item, "name", index);
            if (name.codePointCount(0, name.length()) > 30) {
                throw invalidContactItems("第" + (index + 1) + "项名称不能超过 30 个字符");
            }
            String url = optionalText(item, "url", index);
            if (url != null && !isHttpUrl(url)) {
                throw invalidContactItems("第" + (index + 1) + "项链接必须是合法的 http/https URL");
            }
            String qrUrl = optionalText(item, "qr_url", index);
            if (qrUrl != null && !isSafeQrUrl(qrUrl)) {
                throw invalidContactItems("第" + (index + 1) + "项二维码地址必须是合法的 http/https URL 或站内路径");
            }
            if (url == null && qrUrl == null) {
                throw invalidContactItems("第" + (index + 1) + "项必须填写链接或二维码");
            }

            Map<String, String> normalizedItem = new LinkedHashMap<>();
            normalizedItem.put("name", name);
            if (url != null) normalizedItem.put("url", url);
            if (qrUrl != null) normalizedItem.put("qr_url", qrUrl);
            normalized.add(normalizedItem);
        }
        return normalized;
    }

    private String requiredText(JsonNode item, String field, int index) {
        JsonNode value = item.get(field);
        if (value == null || !value.isTextual()) {
            throw invalidContactItems("第" + (index + 1) + "项名称必须是文本");
        }
        String normalized = value.textValue().trim();
        if (normalized.isEmpty()) {
            throw invalidContactItems("第" + (index + 1) + "项名称不能为空");
        }
        return normalized;
    }

    private String optionalText(JsonNode item, String field, int index) {
        JsonNode value = item.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) {
            throw invalidContactItems("第" + (index + 1) + "项「" + field + "」必须是文本");
        }
        String normalized = value.textValue().trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private boolean isHttpUrl(String value) {
        if (value.codePoints().anyMatch(Character::isISOControl)) return false;
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            return uri.isAbsolute()
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null
                    && !uri.getHost().isBlank();
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private boolean isSafeQrUrl(String value) {
        // Uploaded QR images use an application-relative path. External QR
        // images may use http(s), while script/data schemes are never exposed.
        return isHttpUrl(value) || (value.startsWith("/") && !value.startsWith("//")
                && !value.codePoints().anyMatch(Character::isISOControl));
    }

    private BusinessException invalidContactItems(String detail) {
        return new BusinessException(ErrorCode.BAD_REQUEST, "联系方式配置无效：" + detail);
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
