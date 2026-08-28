package com.myshop.service;

import com.myshop.constant.ErrorCode;
import com.myshop.constant.StockMode;
import com.myshop.entity.Product;
import com.myshop.exception.BusinessException;
import com.myshop.model.CustomerInputField;
import com.myshop.model.CustomerInputValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Shared product configuration and customer-input validation rules. */
public final class ProductRules {

    public static final int MAX_CUSTOMER_INPUT_FIELDS = 20;
    public static final int MAX_CUSTOMER_INPUT_LABEL_LENGTH = 50;
    public static final int MAX_CUSTOMER_INPUT_VALUE_LENGTH = 255;

    private ProductRules() {
    }

    /** Normalize an admin product definition and generate ids for new fields. */
    public static List<CustomerInputField> normalizeCustomerInputFields(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) throw bad("客户填写字段必须是数组");
        if (list.size() > MAX_CUSTOMER_INPUT_FIELDS) {
            throw bad("客户填写字段最多支持" + MAX_CUSTOMER_INPUT_FIELDS + "项");
        }

        List<CustomerInputField> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> labels = new HashSet<>();
        for (Object rawField : list) {
            if (!(rawField instanceof Map<?, ?> map)) throw bad("客户填写字段格式无效");
            String id = text(map.get("id"));
            if (id == null) id = UUID.randomUUID().toString();
            validateIdentifier(id);
            String label = requiredLabel(map.get("label"));
            if (!ids.add(id)) throw bad("客户填写字段 ID 不能重复");
            if (!labels.add(label.toLowerCase(Locale.ROOT))) throw bad("客户填写字段名称不能重复");
            result.add(new CustomerInputField(id, label));
        }
        return List.copyOf(result);
    }

    /** Accept the structured request and the temporary one-field legacy request. */
    public static List<CustomerInputValue> normalizeCustomerInputs(Product product,
                                                                     Map<String, Object> request) {
        boolean structured = request.containsKey("customer_inputs");
        boolean legacy = request.containsKey("customer_input_value");
        return normalizeCustomerInputs(product,
                structured ? request.get("customer_inputs") : null,
                structured,
                legacy ? request.get("customer_input_value") : null,
                legacy);
    }

    public static List<CustomerInputValue> normalizeCustomerInputs(Product product,
                                                                     Object rawInputs,
                                                                     boolean structuredProvided,
                                                                     Object legacyValue,
                                                                     boolean legacyProvided) {
        List<CustomerInputField> fields = currentFields(product);
        Object effectiveInputs = rawInputs;
        if (!structuredProvided && legacyProvided) {
            String legacy = legacyValue == null ? "" : String.valueOf(legacyValue);
            if (fields.size() != 1) {
                if (legacy.isBlank() && fields.isEmpty()) return List.of();
                throw bad("旧版客户填写信息仅支持当前商品恰好一个字段");
            }
            effectiveInputs = List.of(Map.of("id", fields.get(0).id(), "value", legacy));
        }

        if (fields.isEmpty()) {
            if (!structuredProvided || effectiveInputs == null) return List.of();
            if (!(effectiveInputs instanceof List<?> list) || !list.isEmpty()) {
                throw bad("该商品无需填写客户信息");
            }
            return List.of();
        }
        if (!structuredProvided && !legacyProvided) throw bad("请完整填写商品所需信息");

        List<?> list = asList(effectiveInputs, "客户填写信息必须是数组");
        if (list.size() != fields.size()) throw bad("请完整填写商品所需信息");

        Map<String, String> valuesById = new HashMap<>();
        for (Object rawValue : list) {
            String id;
            Object value;
            if (rawValue instanceof CustomerInputValue inputValue) {
                id = inputValue.id();
                value = inputValue.value();
            } else if (rawValue instanceof Map<?, ?> map) {
                id = text(map.get("id"));
                value = map.get("value");
            } else {
                throw bad("客户填写信息格式无效");
            }
            if (id == null) throw bad("客户填写字段 ID 不能为空");
            if (valuesById.put(id, validateValue(value)) != null) throw bad("客户填写字段不能重复");
        }
        if (valuesById.size() != fields.size()) throw bad("客户填写字段不匹配，请刷新后重试");

        List<CustomerInputValue> result = new ArrayList<>();
        for (CustomerInputField field : fields) {
            String value = valuesById.get(field.id());
            if (value == null) throw bad("请完整填写" + field.label());
            result.add(new CustomerInputValue(field.id(), field.label(), value));
        }
        return List.copyOf(result);
    }

    /** Render known values for an outdated cart; checkout still uses strict validation. */
    public static List<CustomerInputValue> knownCustomerInputs(Product product, Object rawInputs) {
        List<CustomerInputField> fields = currentFields(product);
        if (!(rawInputs instanceof List<?> list) || list.isEmpty()) return List.of();
        Map<String, String> valuesById = new HashMap<>();
        for (Object rawValue : list) {
            String id;
            Object value;
            if (rawValue instanceof CustomerInputValue inputValue) {
                id = inputValue.id();
                value = inputValue.value();
            } else if (rawValue instanceof Map<?, ?> map) {
                id = text(map.get("id"));
                value = map.get("value");
            } else continue;
            if (id == null || valuesById.containsKey(id)) continue;
            try {
                valuesById.put(id, validateValue(value));
            } catch (BusinessException ignored) {
                // Invalid historical values are intentionally rendered as incomplete.
            }
        }
        List<CustomerInputValue> result = new ArrayList<>();
        for (CustomerInputField field : fields) {
            String value = valuesById.get(field.id());
            if (value != null) result.add(new CustomerInputValue(field.id(), field.label(), value));
        }
        return List.copyOf(result);
    }

    public static boolean hasCompleteCustomerInputs(Product product, Object rawInputs) {
        try {
            normalizeCustomerInputs(product, rawInputs, true, null, false);
            return true;
        } catch (BusinessException exception) {
            return false;
        }
    }

    public static List<CustomerInputField> currentFields(Product product) {
        return product == null || product.getCustomerInputFields() == null
                ? List.of() : List.copyOf(product.getCustomerInputFields());
    }

    /** Equality is based on id/value pairs, not raw JSON or array order. */
    public static boolean sameCustomerInputs(Object left, Object right) {
        return inputMap(left).equals(inputMap(right));
    }

    private static Map<String, String> inputMap(Object rawInputs) {
        if (!(rawInputs instanceof List<?> list)) return Map.of();
        Map<String, String> result = new HashMap<>();
        for (Object rawValue : list) {
            if (rawValue instanceof CustomerInputValue inputValue) {
                result.put(inputValue.id(), inputValue.value());
            } else if (rawValue instanceof Map<?, ?> map) {
                String id = text(map.get("id"));
                String value = text(map.get("value"));
                if (id != null && value != null) result.put(id, value);
            }
        }
        return result;
    }

    public static StockMode parseStockMode(Object raw, StockMode fallback) {
        if (raw == null || String.valueOf(raw).isBlank()) return fallback;
        try {
            return StockMode.valueOf(String.valueOf(raw).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "库存模式无效");
        }
    }

    public static String normalizeDeliveryType(Object raw, String fallback) {
        String value = raw == null || String.valueOf(raw).isBlank()
                ? (fallback == null || fallback.isBlank() ? "AUTO" : fallback)
                : String.valueOf(raw).trim().toUpperCase(Locale.ROOT);
        if (!"AUTO".equals(value) && !"MANUAL".equals(value)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "发货方式无效");
        }
        return value;
    }

    public static StockMode compatibleStockMode(String deliveryType, StockMode requested) {
        if ("AUTO".equals(deliveryType) && requested != StockMode.CARD_KEY) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "自动发货商品只能使用卡券库存");
        }
        if ("MANUAL".equals(deliveryType) && requested == StockMode.CARD_KEY) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "人工商品只能使用手动库存或无限库存");
        }
        return requested;
    }

    private static String requiredLabel(Object raw) {
        String label = text(raw);
        if (label == null) throw bad("客户填写字段名称不能为空");
        if (label.length() > MAX_CUSTOMER_INPUT_LABEL_LENGTH) {
            throw bad("客户填写字段名称最多" + MAX_CUSTOMER_INPUT_LABEL_LENGTH + "个字符");
        }
        if (containsControl(label)) throw bad("客户填写字段名称不能包含换行或控制字符");
        return label;
    }

    private static String validateValue(Object raw) {
        if (raw == null) throw bad("请填写完整的客户信息");
        String value = String.valueOf(raw).strip();
        if (value.isEmpty()) throw bad("客户填写信息不能为空");
        if (value.length() > MAX_CUSTOMER_INPUT_VALUE_LENGTH) {
            throw bad("客户填写信息最多" + MAX_CUSTOMER_INPUT_VALUE_LENGTH + "个字符");
        }
        if (containsControl(value)) throw bad("客户填写信息不能包含换行或控制字符");
        return value;
    }

    private static void validateIdentifier(String id) {
        if (id.isBlank() || id.length() > 100 || containsControl(id)) throw bad("客户填写字段 ID 无效");
    }

    private static boolean containsControl(String value) {
        return value.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                || switch (Character.getType(codePoint)) {
                    case Character.FORMAT, Character.PRIVATE_USE,
                            Character.SURROGATE, Character.UNASSIGNED -> true;
                    default -> false;
                });
    }

    private static List<?> asList(Object raw, String message) {
        if (!(raw instanceof List<?> list)) throw bad(message);
        return list;
    }

    private static String text(Object raw) {
        if (raw == null) return null;
        String value = String.valueOf(raw).strip();
        return value.isEmpty() ? null : value;
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}
