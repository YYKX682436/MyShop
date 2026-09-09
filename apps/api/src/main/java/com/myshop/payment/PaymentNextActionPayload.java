package com.myshop.payment;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the public JSON shape for a payment action.
 *
 * <p>The order endpoints intentionally return untyped maps because their
 * payload contains several independently shaped sections.  Converting the
 * action to a primitive map at that boundary keeps the discriminator in the
 * wire contract even when the HTTP stack chooses a different Jackson mapper
 * than the mapper used for the provider/storage contract.</p>
 */
public final class PaymentNextActionPayload {

    private PaymentNextActionPayload() {
    }

    public static Map<String, Object> from(PaymentNextAction action) {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        if (action instanceof PaymentNextAction.QrCode qrCode) {
            payload.put("type", "qr_code");
            payload.put("value", qrCode.value());
        } else if (action instanceof PaymentNextAction.Redirect redirect) {
            payload.put("type", "redirect");
            payload.put("url", redirect.url());
        } else if (action instanceof PaymentNextAction.OpenInClient openInClient) {
            payload.put("type", "open_in_client");
            payload.put("url", openInClient.url());
            payload.put("client", openInClient.client());
        } else if (action instanceof PaymentNextAction.DeepLink deepLink) {
            payload.put("type", "deep_link");
            payload.put("url", deepLink.url());
            payload.put("client", deepLink.client());
        } else if (action instanceof PaymentNextAction.FormPost formPost) {
            payload.put("type", "form_post");
            payload.put("url", formPost.url());
            payload.put("fields", new LinkedHashMap<>(formPost.fields()));
        } else {
            throw new IllegalArgumentException("unsupported payment action: " + action.getClass().getName());
        }
        return payload;
    }
}
