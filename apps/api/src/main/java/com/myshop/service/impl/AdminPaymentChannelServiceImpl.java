package com.myshop.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.constant.ErrorCode;
import com.myshop.entity.PaymentChannel;
import com.myshop.exception.BusinessException;
import com.myshop.payment.PaymentProvider;
import com.myshop.repository.PaymentChannelRepository;
import com.myshop.service.AdminPaymentChannelService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminPaymentChannelServiceImpl implements AdminPaymentChannelService {

    private static final Set<String> FALLBACK_SENSITIVE_KEYS = Set.of(
            "api_token", "key", "secret", "password", "private_key", "token");

    private final PaymentChannelRepository paymentChannelRepository;
    private final ObjectMapper objectMapper;
    private final List<PaymentProvider> paymentProviders;

    @Override
    public List<?> listChannels() {
        return paymentChannelRepository.findByIsDeletedOrderBySortOrderAsc(0).stream()
                .map(this::toMap)
                .toList();
    }

    @Override
    @Transactional
    public void createChannel(Map<String, Object> req) {
        String channelCode = requiredText(req.get("channel_code"), "渠道代码不能为空");
        validateChannelCode(channelCode);
        String channelName = requiredText(req.get("channel_name"), "渠道名称不能为空");
        String providerType = requiredText(req.getOrDefault("provider_type", ""), "支付提供商不能为空");
        PaymentProvider provider = provider(providerType);
        ensureChannelCodeAvailable(channelCode, null);

        PaymentChannel channel = new PaymentChannel();
        channel.setChannelCode(channelCode);
        channel.setChannelName(channelName);
        channel.setProviderType(provider.id());
        channel.setPaymentType(normalizePaymentType(req.get("payment_type"), provider));
        channel.setConfigData(validateAndSerializeConfig(req.get("config_data"), provider, null));
        channel.setEnabled(booleanValue(req.get("is_enabled"), true));
        channel.setSortOrder(integerValue(req.get("sort_order"), 0));
        paymentChannelRepository.save(channel);
    }

    @Override
    @Transactional
    public void updateChannel(java.util.UUID id, Map<String, Object> req) {
        PaymentChannel channel = paymentChannelRepository.findById(id)
                .filter(value -> value.getIsDeleted() == 0)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "支付渠道不存在"));

        String channelCode = channel.getChannelCode();
        if (req.containsKey("channel_code")) {
            channelCode = requiredText(req.get("channel_code"), "渠道代码不能为空");
            validateChannelCode(channelCode);
            ensureChannelCodeAvailable(channelCode, channel.getId());
            channel.setChannelCode(channelCode);
        }
        if (req.containsKey("channel_name")) {
            channel.setChannelName(requiredText(req.get("channel_name"), "渠道名称不能为空"));
        }

        PaymentProvider provider = provider(req.containsKey("provider_type")
                ? String.valueOf(req.get("provider_type")) : channel.getProviderType());
        if (req.containsKey("provider_type")) channel.setProviderType(provider.id());
        if (req.containsKey("payment_type") || req.containsKey("provider_type")) {
            Object paymentType = req.containsKey("payment_type") ? req.get("payment_type") : channel.getPaymentType();
            channel.setPaymentType(normalizePaymentType(paymentType, provider));
        }
        if (req.containsKey("config_data") || req.containsKey("provider_type")) {
            Object configData = req.containsKey("config_data") ? req.get("config_data") : channel.getConfigData();
            channel.setConfigData(validateAndSerializeConfig(configData, provider, channel.getConfigData()));
        }
        if (req.containsKey("is_enabled")) channel.setEnabled(booleanValue(req.get("is_enabled"), channel.isEnabled()));
        if (req.containsKey("sort_order")) channel.setSortOrder(integerValue(req.get("sort_order"), channel.getSortOrder()));
        paymentChannelRepository.save(channel);
    }

    @Override
    @Transactional
    public void deleteChannel(java.util.UUID id) {
        PaymentChannel channel = paymentChannelRepository.findById(id)
                .filter(value -> value.getIsDeleted() == 0)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "支付渠道不存在"));
        channel.setIsDeleted(1);
        paymentChannelRepository.save(channel);
    }

    private Map<String, Object> toMap(PaymentChannel channel) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", channel.getId());
        result.put("channel_code", channel.getChannelCode());
        result.put("channel_name", channel.getChannelName());
        result.put("provider_type", channel.getProviderType());
        result.put("payment_type", channel.getPaymentType());
        result.put("config_data", maskSensitiveConfigData(
                deserializeConfigData(channel.getConfigData()), provider(channel.getProviderType())));
        result.put("is_enabled", channel.isEnabled());
        result.put("sort_order", channel.getSortOrder());
        result.put("created_at", channel.getCreatedAt());
        return result;
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
        for (String key : sensitive) {
            Object value = result.get(key);
            if (!(value instanceof String text) || text.isBlank()) continue;
            result.put(key, mask(text));
        }
        return result;
    }

    private String mask(String value) {
        if (value.length() <= 8) return value.substring(0, Math.min(2, value.length())) + "****";
        return value.substring(0, 4) + "****" + value.substring(value.length() - 2);
    }

    @SuppressWarnings("unchecked")
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
            if (value == null && field.required() && field.defaultValue() == null) {
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
                URI uri = URI.create(text);
                if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException exception) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "配置项「" + field.label() + "」不是有效 URL");
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
                && !(value instanceof String text && ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "配置项「" + field.label() + "」必须是开关值");
        }
        if ("select".equals(type) && field.options().stream()
                .noneMatch(option -> option.value().equals(String.valueOf(value)))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "配置项「" + field.label() + "」的选项无效");
        }
    }

    private PaymentProvider provider(String providerId) {
        return paymentProviders.stream()
                .filter(candidate -> candidate.id().equalsIgnoreCase(providerId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "未注册的支付提供商"));
    }

    private String normalizePaymentType(Object value, PaymentProvider provider) {
        String paymentType = value == null ? "alipay" : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (!provider.paymentTypes().stream().map(type -> type.toLowerCase(Locale.ROOT)).toList().contains(paymentType)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "支付类型不受该提供商支持");
        }
        return paymentType;
    }

    private void ensureChannelCodeAvailable(String channelCode, java.util.UUID currentId) {
        paymentChannelRepository.findByChannelCodeAndIsDeleted(channelCode, 0)
                .filter(existing -> currentId == null || !existing.getId().equals(currentId))
                .ifPresent(existing -> {
                    throw new BusinessException(ErrorCode.CHANNEL_CODE_CONFLICT,
                            "渠道代码已存在，请使用其他通道实例代码");
                });
    }

    private void validateChannelCode(String channelCode) {
        if (!channelCode.matches("[A-Za-z0-9][A-Za-z0-9._-]{1,127}")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "渠道代码需为 2~128 位字母、数字、点、下划线或短横线");
        }
    }

    private String requiredText(Object value, String message) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.isBlank()) throw new BusinessException(ErrorCode.BAD_REQUEST, message);
        return text;
    }

    private boolean booleanValue(Object value, boolean fallback) {
        return value == null ? fallback : value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
    }

    private int integerValue(Object value, int fallback) {
        if (value == null) return fallback;
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "排序值必须是整数");
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
