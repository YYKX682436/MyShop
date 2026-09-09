package com.myshop.payment;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The only payment carrier contract exposed to the storefront. The explicit
 * type is intentionally independent from provider field names and device
 * heuristics.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = PaymentNextAction.QrCode.class, name = "qr_code"),
        @JsonSubTypes.Type(value = PaymentNextAction.Redirect.class, name = "redirect"),
        @JsonSubTypes.Type(value = PaymentNextAction.OpenInClient.class, name = "open_in_client"),
        @JsonSubTypes.Type(value = PaymentNextAction.DeepLink.class, name = "deep_link"),
        @JsonSubTypes.Type(value = PaymentNextAction.FormPost.class, name = "form_post")
})
@JsonSerialize(using = PaymentNextActionJsonSerializer.class)
public sealed interface PaymentNextAction
        permits PaymentNextAction.QrCode, PaymentNextAction.Redirect,
        PaymentNextAction.OpenInClient, PaymentNextAction.DeepLink,
        PaymentNextAction.FormPost {

    /** Not serialized as a second field; Jackson writes it as the discriminator. */
    @JsonIgnore
    String type();

    static String requiredValue(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    static String requiredClient(String value) {
        String client = requiredValue(value, "client").toLowerCase(java.util.Locale.ROOT);
        if (!"wechat".equals(client) && !"alipay".equals(client)) {
            throw new IllegalArgumentException("client must be wechat or alipay");
        }
        return client;
    }

    @JsonTypeName("qr_code")
    record QrCode(String value) implements PaymentNextAction {
        public QrCode {
            value = requiredValue(value, "value");
        }

        @Override
        @JsonIgnore
        public String type() {
            return "qr_code";
        }
    }

    @JsonTypeName("redirect")
    record Redirect(String url) implements PaymentNextAction {
        public Redirect {
            url = requiredValue(url, "url");
        }

        @Override
        @JsonIgnore
        public String type() {
            return "redirect";
        }
    }

    @JsonTypeName("open_in_client")
    record OpenInClient(String url, String client) implements PaymentNextAction {
        public OpenInClient {
            url = requiredValue(url, "url");
            client = requiredClient(client);
        }

        @Override
        @JsonIgnore
        public String type() {
            return "open_in_client";
        }
    }

    @JsonTypeName("deep_link")
    record DeepLink(String url, String client) implements PaymentNextAction {
        public DeepLink {
            url = requiredValue(url, "url");
            client = requiredClient(client);
        }

        @Override
        @JsonIgnore
        public String type() {
            return "deep_link";
        }
    }

    @JsonTypeName("form_post")
    record FormPost(String url, Map<String, String> fields) implements PaymentNextAction {
        public FormPost {
            url = requiredValue(url, "url");
            if (fields == null) throw new IllegalArgumentException("fields must not be null");
            Map<String, String> copy = new LinkedHashMap<>();
            fields.forEach((key, value) -> {
                if (key == null || key.isBlank() || value == null) {
                    throw new IllegalArgumentException("form fields must contain non-null names and values");
                }
                copy.put(key, value);
            });
            fields = Map.copyOf(copy);
        }

        @Override
        @JsonIgnore
        public String type() {
            return "form_post";
        }
    }
}
