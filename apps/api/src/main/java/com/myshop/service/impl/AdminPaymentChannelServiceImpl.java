package com.myshop.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.constant.ErrorCode;
import com.myshop.entity.PaymentChannel;
import com.myshop.exception.BusinessException;
import com.myshop.model.response.AdminPaymentChannelResponse;
import com.myshop.payment.PaymentProvider;
import com.myshop.repository.PaymentChannelRepository;
import com.myshop.service.AdminPaymentChannelService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AdminPaymentChannelServiceImpl implements AdminPaymentChannelService {

    private static final int PAYMENT_HINT_MAX_LENGTH = 160;
    private static final int BADGE_TEXT_MAX_LENGTH = 16;
    private static final int CHANNEL_NAME_MAX_LENGTH = 120;
    private static final Set<String> SUPPORTED_MAINLAND_PAYMENT_TYPES = Set.of("alipay", "wxpay");
    private static final Set<String> FALLBACK_SENSITIVE_KEYS = Set.of(
            "api_token", "key", "secret", "password", "private_key", "token");

    private final PaymentChannelRepository paymentChannelRepository;
    private final ObjectMapper objectMapper;
    private final List<PaymentProvider> paymentProviders;

    @Autowired
    public AdminPaymentChannelServiceImpl(PaymentChannelRepository paymentChannelRepository,
                                          ObjectMapper objectMapper,
                                          List<PaymentProvider> paymentProviders) {
        this.paymentChannelRepository = paymentChannelRepository;
        this.objectMapper = objectMapper;
        this.paymentProviders = paymentProviders == null ? List.of() : List.copyOf(paymentProviders);
    }

    @Override
    public List<AdminPaymentChannelResponse> listChannels() {
        return paymentChannelRepository.findByIsDeletedOrderBySortOrderAsc(0).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public void createChannel(Map<String, Object> req) {
        PaymentProvider provider = providerForRequest(req.get("provider_type"), null);
        String paymentType = normalizePaymentType(req.get("payment_type"), provider, true);
        String channelCode = nextGeneratedChannelCode(paymentType);

        PaymentChannel channel = new PaymentChannel();
        channel.setChannelCode(channelCode);
        channel.setChannelName(displayText(req.get("channel_name"),
                defaultChannelName(paymentType), CHANNEL_NAME_MAX_LENGTH, "渠道名称"));
        channel.setProviderType(provider.id());
        channel.setPaymentType(paymentType);
        channel.setConfigData(validateAndSerializeConfig(req.get("config_data"), provider, null));
        channel.setPaymentHint(optionalText(req.get("payment_hint"), PAYMENT_HINT_MAX_LENGTH, "支付提示"));
        channel.setBadgeText(optionalText(req.get("badge_text"), BADGE_TEXT_MAX_LENGTH, "标签"));
        channel.setEnabled(booleanValue(req.get("is_enabled"), true));
        channel.setSortOrder(integerValue(req.get("sort_order"), 0));
        paymentChannelRepository.save(channel);
    }

    @Override
    @Transactional
    public void updateChannel(UUID id, Map<String, Object> req) {
        PaymentChannel channel = paymentChannelRepository.findById(id)
                .filter(value -> value.getIsDeleted() == 0)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "支付渠道不存在"));

        String oldProviderType = channel.getProviderType();
        String oldPaymentType = channel.getPaymentType();
        String oldConfigData = channel.getConfigData();

        String channelCode = channel.getChannelCode();
        if (req.containsKey("channel_code")) {
            channelCode = requiredText(req.get("channel_code"), "渠道代码不能为空");
            validateChannelCode(channelCode);
            ensureChannelCodeAvailable(channelCode, channel.getId());
        }

        PaymentProvider provider = providerForRequest(
                req.containsKey("provider_type") ? req.get("provider_type") : oldProviderType,
                oldProviderType);
        String paymentType = oldPaymentType;
        if (req.containsKey("payment_type") || req.containsKey("provider_type")) {
            Object requestedType = req.containsKey("payment_type")
                    ? req.get("payment_type") : oldPaymentType;
            paymentType = normalizePaymentType(requestedType, provider, true);
        }

        String configData = oldConfigData;
        if (req.containsKey("config_data") || req.containsKey("provider_type")) {
            Object requestedConfig = req.containsKey("config_data")
                    ? req.get("config_data") : oldConfigData;
            configData = validateAndSerializeConfig(requestedConfig, provider, oldConfigData);
        }

        channel.setChannelCode(channelCode);
        channel.setProviderType(provider.id());
        channel.setPaymentType(paymentType);
        channel.setConfigData(configData);
        if (req.containsKey("channel_name")) {
            channel.setChannelName(displayText(req.get("channel_name"),
                    defaultChannelName(paymentType), CHANNEL_NAME_MAX_LENGTH, "渠道名称"));
        }
        if (req.containsKey("payment_hint")) {
            channel.setPaymentHint(optionalText(req.get("payment_hint"),
                    PAYMENT_HINT_MAX_LENGTH, "支付提示"));
        }
        if (req.containsKey("badge_text")) {
            channel.setBadgeText(optionalText(req.get("badge_text"),
                    BADGE_TEXT_MAX_LENGTH, "标签"));
        }
        if (req.containsKey("is_enabled")) {
            channel.setEnabled(booleanValue(req.get("is_enabled"), channel.isEnabled()));
        }
        if (req.containsKey("sort_order")) {
            channel.setSortOrder(integerValue(req.get("sort_order"), channel.getSortOrder()));
        }
        paymentChannelRepository.save(channel);
    }

    @Override
    @Transactional
    public void deleteChannel(UUID id) {
        PaymentChannel channel = paymentChannelRepository.findById(id)
                .filter(value -> value.getIsDeleted() == 0)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "支付渠道不存在"));
        channel.setIsDeleted(1);
        paymentChannelRepository.save(channel);
    }

    private AdminPaymentChannelResponse toResponse(PaymentChannel channel) {
        return new AdminPaymentChannelResponse(
                channel.getId(),
                channel.getChannelCode(),
                channel.getChannelName(),
                channel.getProviderType(),
                channel.getPaymentType(),
                maskSensitiveConfigData(deserializeConfigData(channel.getConfigData()),
                        provider(channel.getProviderType())),
                channel.getPaymentHint(),
                channel.getBadgeText(),
                channel.isEnabled(),
                channel.getSortOrder(),
                channel.getCreatedAt());
    }

    private Map<String, Object> maskSensitiveConfigData(Map<String, Object> config,
                                                         PaymentProvider provider) {
        if (config == null) return null;
        Set<String> sensitive = provider.configSchema().stream()
                .filter(this::isSensitive)
                .map(PaymentProvider.ConfigField::name)
                .collect(Collectors.toSet());
        sensitive.addAll(FALLBACK_SENSITIVE_KEYS);
        Map<String, Object> result = new LinkedHashMap<>(config);
        for (Map.Entry<String, Object> entry : result.entrySet()) {
            String key = entry.getKey();
            if (!sensitive.stream().anyMatch(name -> name.equalsIgnoreCase(key))
                    && !isSensitiveConfigName(key)) continue;
            Object value = entry.getValue();
            if (value == null) continue;
            String text = String.valueOf(value);
            if (text.isBlank()) continue;
            entry.setValue(mask(text));
        }
        return result;
    }

    private boolean isSensitiveConfigName(String key) {
        if (key == null) return false;
        String normalized = key.toLowerCase(Locale.ROOT);
        return FALLBACK_SENSITIVE_KEYS.contains(normalized)
                || normalized.contains("secret")
                || normalized.contains("password")
                || normalized.contains("private_key")
                || normalized.endsWith("_token")
                || normalized.equals("token");
    }

    private String mask(String value) {
        if (value.length() <= 8) return value.substring(0, Math.min(2, value.length())) + "****";
        return value.substring(0, 4) + "****" + value.substring(value.length() - 2);
    }

    private String validateAndSerializeConfig(Object input, PaymentProvider provider,
                                               String existingJson) {
        Map<String, Object> values = deserializeConfigData(serializeConfigData(input));
        if (values == null) throw new BusinessException(ErrorCode.BAD_REQUEST, "支付渠道配置必须是 JSON 对象");

        Map<String, Object> existing = deserializeConfigData(existingJson);
        Set<String> sensitive = provider.configSchema().stream()
                .filter(this::isSensitive)
                .map(PaymentProvider.ConfigField::name)
                .collect(Collectors.toSet());
        sensitive.addAll(FALLBACK_SENSITIVE_KEYS);
        if (existing != null) {
            for (String key : sensitive) {
                Object newValue = values.get(key);
                if (newValue instanceof String text && text.contains("****") && existing.containsKey(key)) {
                    values.put(key, existing.get(key));
                }
            }
        }

        Map<String, PaymentProvider.ConfigField> schema = provider.configSchema().stream()
                .collect(Collectors.toMap(PaymentProvider.ConfigField::name, field -> field));
        if (values.keySet().stream().anyMatch(key -> !schema.containsKey(key))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "支付渠道配置包含提供商未声明的字段");
        }
        if (values.values().stream().anyMatch(value -> !(value instanceof String
                || value instanceof Number || value instanceof Boolean))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "支付渠道配置字段必须是标量值");
        }
        for (PaymentProvider.ConfigField field : schema.values()) {
            Object value = values.get(field.name());
            if (value == null && field.defaultValue() != null) {
                value = field.defaultValue();
                values.put(field.name(), value);
            }
            if (field.required() && field.defaultValue() == null
                    && (value == null || value instanceof String text && text.isBlank())) {
                throw new BusinessException(ErrorCode.PAYMENT_CONFIG_INCOMPLETE,
                        "支付渠道缺少必填配置项: " + field.label());
            }
            if (value != null) validateField(field, value);
        }
        String serialized = serializeConfigData(values);
        if (serialized.length() > 16_384) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "支付渠道配置过长");
        }
        return serialized;
    }

    private boolean isSensitive(PaymentProvider.ConfigField field) {
        String type = field.type() == null ? "" : field.type().toLowerCase(Locale.ROOT);
        return field.secret() || "password".equals(type) || "secret".equals(type);
    }

    private void validateField(PaymentProvider.ConfigField field, Object value) {
        String type = field.type().toLowerCase(Locale.ROOT);
        if (("text".equals(type) || "password".equals(type) || "secret".equals(type))
                && !(value instanceof String)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "配置项「" + field.label() + "」必须是文本");
        }
        if ("url".equals(type)) {
            if (!(value instanceof String text)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "配置项「" + field.label() + "」必须是 URL");
            }
            try {
                URI uri = URI.create(text.trim());
                if (!("http".equalsIgnoreCase(uri.getScheme())
                        || "https".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null || uri.getHost().isBlank()
                        || uri.getUserInfo() != null || uri.getQuery() != null
                        || uri.getFragment() != null) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException exception) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "配置项「" + field.label() + "」不是有效 http/https URL");
            }
        }
        if ("number".equals(type)) {
            try {
                new BigDecimal(String.valueOf(value));
            } catch (NumberFormatException exception) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "配置项「" + field.label() + "」必须是数字");
            }
        }
        if ("switch".equals(type) && !(value instanceof Boolean)
                && !(value instanceof String text && ("true".equalsIgnoreCase(text)
                || "false".equalsIgnoreCase(text)))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "配置项「" + field.label() + "」必须是开关值");
        }
        if ("select".equals(type) && field.options().stream()
                .noneMatch(option -> option.value().equals(String.valueOf(value)))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "配置项「" + field.label() + "」的选项无效");
        }
    }

    private PaymentProvider providerForRequest(Object requestedProvider, String currentProviderId) {
        if (paymentProviders.isEmpty()) {
            throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE, "当前没有已注册的支付提供商");
        }
        // With one registered provider the provider is an implementation
        // detail of this phase, so the admin form/API cannot select a second
        // value accidentally.
        if (paymentProviders.size() == 1) return paymentProviders.get(0);
        String providerId = requestedProvider == null
                ? currentProviderId : String.valueOf(requestedProvider);
        return provider(providerId);
    }

    private PaymentProvider provider(String providerId) {
        return paymentProviders.stream()
                .filter(candidate -> candidate.id().equalsIgnoreCase(providerId == null ? "" : providerId.trim()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "未注册的支付提供商"));
    }

    private String normalizePaymentType(Object value, PaymentProvider provider, boolean required) {
        if (value == null || String.valueOf(value).trim().isBlank()) {
            if (required) throw new BusinessException(ErrorCode.BAD_REQUEST, "支付类型不能为空");
            return "alipay";
        }
        String paymentType = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_MAINLAND_PAYMENT_TYPES.contains(paymentType)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "当前仅支持支付宝或微信支付");
        }
        if (provider.paymentTypes().stream()
                .map(type -> type.toLowerCase(Locale.ROOT))
                .noneMatch(paymentType::equals)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "支付类型不受该提供商支持");
        }
        return paymentType;
    }

    private String nextGeneratedChannelCode(String paymentType) {
        String base = paymentType.toLowerCase(Locale.ROOT);
        String candidate = base;
        int suffix = 2;
        while (paymentChannelRepository.findByChannelCodeAndIsDeleted(candidate, 0).isPresent()) {
            candidate = base + "-" + suffix++;
        }
        validateChannelCode(candidate);
        return candidate;
    }

    private void ensureChannelCodeAvailable(String channelCode, UUID currentId) {
        paymentChannelRepository.findByChannelCodeAndIsDeleted(channelCode, 0)
                .filter(existing -> currentId == null || !existing.getId().equals(currentId))
                .ifPresent(existing -> {
                    throw new BusinessException(ErrorCode.CHANNEL_CODE_CONFLICT,
                            "渠道代码已存在，请使用其他通道实例代码");
                });
    }

    private void validateChannelCode(String channelCode) {
        if (!channelCode.matches("[A-Za-z0-9][A-Za-z0-9._-]{1,127}")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "渠道代码需为 2~128 位字母、数字、点、下划线或短横线");
        }
    }

    private String defaultChannelName(String paymentType) {
        return "wxpay".equalsIgnoreCase(paymentType) ? "微信支付" : "支付宝";
    }

    private String displayText(Object value, String fallback, int maxLength, String label) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.isBlank()) return fallback;
        validateLength(text, maxLength, label);
        return text;
    }

    private String optionalText(Object value, int maxLength, String label) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.isBlank()) return null;
        validateLength(text, maxLength, label);
        return text;
    }

    private void validateLength(String value, int maxLength, String label) {
        if (value.codePointCount(0, value.length()) > maxLength) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    label + "不能超过 " + maxLength + " 个字符");
        }
    }

    private String requiredText(Object value, String message) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.isBlank()) throw new BusinessException(ErrorCode.BAD_REQUEST, message);
        return text;
    }

    private boolean booleanValue(Object value, boolean fallback) {
        return value == null ? fallback : value instanceof Boolean b
                ? b : Boolean.parseBoolean(String.valueOf(value));
    }

    private int integerValue(Object value, int fallback) {
        if (value == null) return fallback;
        try {
            int result = Integer.parseInt(String.valueOf(value));
            if (result < 0) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "排序值必须是非负整数");
        }
    }

    private String serializeConfigData(Object value) {
        if (value == null) return "{}";
        if (value instanceof String text) return text;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "支付渠道配置无法序列化");
        }
    }

    private Map<String, Object> deserializeConfigData(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "支付渠道配置必须是有效 JSON");
        }
    }
}
