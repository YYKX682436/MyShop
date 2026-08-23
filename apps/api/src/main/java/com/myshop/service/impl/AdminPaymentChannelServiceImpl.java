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

import java.util.*;

@Service
@RequiredArgsConstructor
public class AdminPaymentChannelServiceImpl implements AdminPaymentChannelService {

    private final PaymentChannelRepository paymentChannelRepository;
    private final ObjectMapper objectMapper;
    private final List<PaymentProvider> paymentProviders;

    @Override
    public List<?> listChannels() {
        return paymentChannelRepository.findByIsDeletedOrderBySortOrderAsc(0).stream()
                .map(this::toMap).toList();
    }

    @Override
    @Transactional
    public void createChannel(Map<String, Object> req) {
        PaymentChannel channel = new PaymentChannel();
        channel.setChannelCode((String) req.get("channel_code"));
        channel.setChannelName((String) req.get("channel_name"));
        if (req.containsKey("provider_type")) {
            channel.setProviderType((String) req.get("provider_type"));
        }
        channel.setPaymentType(normalizePaymentType(req.get("payment_type"), channel.getProviderType()));
        if (req.containsKey("config_data")) {
            channel.setConfigData(validateAndSerializeConfig(req.get("config_data"), channel.getProviderType()));
        }
        if (req.containsKey("is_enabled")) channel.setEnabled((boolean) req.get("is_enabled"));
        if (req.containsKey("sort_order")) channel.setSortOrder(((Number) req.get("sort_order")).intValue());
        paymentChannelRepository.save(channel);
    }

    @Override
    @Transactional
    public void updateChannel(UUID id, Map<String, Object> req) {
        PaymentChannel channel = paymentChannelRepository.findById(id)
                .filter(c -> c.getIsDeleted() == 0)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "支付渠道不存在"));
        if (req.containsKey("channel_name")) channel.setChannelName((String) req.get("channel_name"));
        if (req.containsKey("provider_type")) {
            channel.setProviderType(String.valueOf(req.get("provider_type")));
        }
        if (req.containsKey("config_data")) {
            channel.setConfigData(validateAndSerializeConfig(
                    mergeConfigData(req.get("config_data"), channel.getConfigData()), channel.getProviderType()));
        }
        if (req.containsKey("payment_type") || req.containsKey("provider_type")) {
            Object paymentType = req.containsKey("payment_type")
                    ? req.get("payment_type") : channel.getPaymentType();
            channel.setPaymentType(normalizePaymentType(paymentType, channel.getProviderType()));
        }
        if (req.containsKey("is_enabled")) channel.setEnabled((boolean) req.get("is_enabled"));
        if (req.containsKey("sort_order")) channel.setSortOrder(((Number) req.get("sort_order")).intValue());
        paymentChannelRepository.save(channel);
    }

    @Override
    @Transactional
    public void deleteChannel(UUID id) {
        PaymentChannel channel = paymentChannelRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "支付渠道不存在"));
        channel.setIsDeleted(1);
        paymentChannelRepository.save(channel);
    }

    /** 需要在 API 响应中脱敏的敏感字段名 */
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "api_token", "key", "secret", "password", "private_key", "token"
    );

    private Map<String, Object> toMap(PaymentChannel c) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", c.getId());
        map.put("channel_code", c.getChannelCode());
        map.put("channel_name", c.getChannelName());
        map.put("provider_type", c.getProviderType());
        map.put("payment_type", c.getPaymentType());
        map.put("config_data", maskSensitiveConfigData(deserializeConfigData(c.getConfigData())));
        map.put("is_enabled", c.isEnabled());
        map.put("sort_order", c.getSortOrder());
        map.put("created_at", c.getCreatedAt());
        return map;
    }

    /**
     * 对 configData 中的敏感字段进行脱敏处理，前端仅显示部分字符。
     */
    private Map<String, Object> maskSensitiveConfigData(Map<String, Object> configData) {
        if (configData == null) return null;
        Map<String, Object> masked = new LinkedHashMap<>(configData);
        for (String sensitiveKey : SENSITIVE_KEYS) {
            if (masked.containsKey(sensitiveKey)) {
                Object val = masked.get(sensitiveKey);
                if (val instanceof String s && !s.isEmpty()) {
                    // 保留前 4 位和后 2 位，中间用 **** 替代
                    if (s.length() <= 8) {
                        masked.put(sensitiveKey, s.substring(0, Math.min(2, s.length())) + "****");
                    } else {
                        masked.put(sensitiveKey, s.substring(0, 4) + "****" + s.substring(s.length() - 2));
                    }
                }
            }
        }
        return masked;
    }

    /**
     * 合并新配置与旧配置：对敏感字段，如果新值包含掩码标记 (****)，
     * 则保留数据库中的原始值，防止管理员编辑渠道时将脱敏值覆写回数据库。
     */
    @SuppressWarnings("unchecked")
    private String mergeConfigData(Object newConfigData, String existingConfigDataJson) {
        if (newConfigData == null) return null;

        Map<String, Object> newConfig;
        if (newConfigData instanceof Map<?, ?> m) {
            newConfig = new LinkedHashMap<>((Map<String, Object>) m);
        } else if (newConfigData instanceof String s) {
            Map<String, Object> parsed = deserializeConfigData(s);
            if (parsed == null) return s;
            newConfig = new LinkedHashMap<>(parsed);
        } else {
            return serializeConfigData(newConfigData);
        }

        Map<String, Object> oldConfig = deserializeConfigData(existingConfigDataJson);
        if (oldConfig != null) {
            for (String sensitiveKey : SENSITIVE_KEYS) {
                Object newVal = newConfig.get(sensitiveKey);
                if (newVal instanceof String s && s.contains("****")) {
                    Object oldVal = oldConfig.get(sensitiveKey);
                    if (oldVal != null) {
                        newConfig.put(sensitiveKey, oldVal);
                    }
                }
            }
        }

        return serializeConfigData(newConfig);
    }

    private String serializeConfigData(Object configData) {
        if (configData == null) return null;
        if (configData instanceof String s) return s;
        try {
            return objectMapper.writeValueAsString(configData);
        } catch (JsonProcessingException e) {
            return configData.toString();
        }
    }

    private String validateAndSerializeConfig(Object configData, String providerId) {
        String serialized = serializeConfigData(configData);
        Map<String, Object> values = deserializeConfigData(serialized);
        PaymentProvider provider = paymentProviders.stream()
                .filter(candidate -> candidate.id().equals(providerId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "未注册的支付提供商"));
        if (values == null) throw new BusinessException(ErrorCode.BAD_REQUEST, "支付渠道配置必须是 JSON 对象");
        Set<String> allowed = provider.configSchema().stream()
                .map(PaymentProvider.ConfigField::name).collect(java.util.stream.Collectors.toSet());
        if (values.keySet().stream().anyMatch(key -> !allowed.contains(key))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "支付渠道配置包含提供商未声明的字段");
        }
        if (serialized.length() > 16_384 || values.values().stream()
                .anyMatch(value -> !(value instanceof String || value instanceof Number || value instanceof Boolean))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "支付渠道配置字段格式或长度无效");
        }
        return serialized;
    }

    private String normalizePaymentType(Object value, String providerId) {
        String paymentType = value == null ? "alipay" : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        boolean supported = paymentProviders.stream()
                .filter(provider -> provider.id().equals(providerId))
                .findFirst()
                .map(provider -> provider.paymentTypes().contains(paymentType))
                .orElse(false);
        if (!supported) throw new BusinessException(ErrorCode.BAD_REQUEST, "支付类型不受该提供商支持");
        return paymentType;
    }

    private Map<String, Object> deserializeConfigData(String configData) {
        if (configData == null || configData.isBlank()) return null;
        try {
            return objectMapper.readValue(configData, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
