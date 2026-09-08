package com.myshop.payment;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Provider-neutral SPI used by payment creation and webhook confirmation. */
public interface PaymentProvider {

    String id();

    String displayName();

    List<String> paymentTypes();

    List<ConfigField> configSchema();

    PaymentResult create(PaymentRequest request);

    /** Extract only the merchant trade number from untrusted request data. */
    default String extractMerchantTradeNo(WebhookRequest request) {
        throw new UnsupportedOperationException("Payment provider does not support webhooks");
    }

    /** Verify the callback with the attempt snapshot and return a standard result. */
    default CallbackResult verifyAndParseCallback(WebhookRequest request,
                                                  Map<String, String> config) {
        throw new UnsupportedOperationException("Payment provider does not support webhooks");
    }

    /** Providers opt into server-to-server confirmation explicitly. */
    default boolean supportsServerSideQuery() {
        return false;
    }

    default PaymentQueryResult query(PaymentQueryRequest request) {
        throw new UnsupportedOperationException("Payment provider does not support server-side query");
    }

    /** A provider may allow a verified callback when it explicitly reports query unavailability. */
    default boolean allowSignedCallbackWhenQueryUnavailable() {
        return false;
    }

    /** Protocol-specific acknowledgement bodies stay behind the provider boundary. */
    default WebhookAcknowledgement successAcknowledgement() {
        return WebhookAcknowledgement.success();
    }

    default WebhookAcknowledgement failureAcknowledgement() {
        return WebhookAcknowledgement.failure();
    }

    record ConfigOption(String value, String label) { }

    record ConfigField(String name, String label, String type, boolean secret, boolean required,
                       String placeholder, Object defaultValue, List<ConfigOption> options) {
        public ConfigField(String name, String label, String type, boolean secret, boolean required) {
            this(name, label, type, secret, required, null, null, List.of());
        }
    }

    record PaymentRequest(String merchantTradeNo, String paymentType, String productName,
                          BigDecimal amount, String clientIp, String device,
                          Map<String, String> config, String returnOrderNo) {
        public PaymentRequest {
            config = immutableStringMap(config);
        }
    }

    /** Complete HTTP context passed to a provider without exposing it to core business logic. */
    record WebhookRequest(String method, Map<String, String> headers,
                          Map<String, List<String>> queryParams,
                          Map<String, List<String>> formParams, String rawBody) {
        public WebhookRequest {
            method = method == null ? "" : method.toUpperCase(Locale.ROOT);
            headers = immutableStringMap(headers);
            queryParams = immutableMultiMap(queryParams);
            formParams = immutableMultiMap(formParams);
            rawBody = rawBody == null ? "" : rawBody;
        }

        public String header(String name) {
            if (name == null) return null;
            String expected = name.toLowerCase(Locale.ROOT);
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().toLowerCase(Locale.ROOT).equals(expected))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
        }

        public String parameter(String name) {
            if (name == null) return null;
            String value = first(formParams.get(name));
            return value != null ? value : first(queryParams.get(name));
        }

        /** Flatten query/form values for providers whose signing protocol is key/value based. */
        public Map<String, String> parameters() {
            Map<String, String> values = new LinkedHashMap<>();
            queryParams.forEach((key, entries) -> {
                String value = first(entries);
                if (value != null) values.put(key, value);
            });
            formParams.forEach((key, entries) -> {
                String value = first(entries);
                if (value != null) values.put(key, value);
            });
            return Collections.unmodifiableMap(values);
        }

        private static String first(List<String> values) {
            return values == null || values.isEmpty() ? null : values.get(0);
        }
    }

    record CallbackResult(String eventId, String merchantTradeNo, String providerTradeNo,
                          PaymentStatus status, BigDecimal amount) { }

    record PaymentQueryRequest(String merchantTradeNo, Map<String, String> config) {
        public PaymentQueryRequest {
            config = immutableStringMap(config);
        }
    }

    record PaymentQueryResult(PaymentStatus status, BigDecimal amount, String providerTradeNo) { }

    record PaymentResult(String providerTradeNo, PaymentNextAction nextAction) { }

    record WebhookAcknowledgement(String body, int statusCode, String contentType) {
        public static WebhookAcknowledgement success() {
            return new WebhookAcknowledgement("SUCCESS", 200, "text/plain;charset=UTF-8");
        }

        public static WebhookAcknowledgement failure() {
            return new WebhookAcknowledgement("FAIL", 200, "text/plain;charset=UTF-8");
        }
    }

    private static Map<String, String> immutableStringMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) return Map.of();
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private static Map<String, List<String>> immutableMultiMap(Map<String, List<String>> values) {
        if (values == null || values.isEmpty()) return Map.of();
        Map<String, List<String>> copy = new LinkedHashMap<>();
        values.forEach((key, entries) -> copy.put(key,
                entries == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(entries))));
        return Collections.unmodifiableMap(copy);
    }
}
