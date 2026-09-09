package com.myshop.payment;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;

import java.io.IOException;

/**
 * Keeps the payment action discriminator when an action is nested in a
 * response map whose value type is Object.
 */
public final class PaymentNextActionJsonSerializer extends JsonSerializer<PaymentNextAction> {

    @Override
    public void serialize(PaymentNextAction action, JsonGenerator generator,
                          SerializerProvider provider) throws IOException {
        generator.writeStartObject();
        if (action instanceof PaymentNextAction.QrCode qrCode) {
            generator.writeStringField("type", "qr_code");
            generator.writeStringField("value", qrCode.value());
        } else if (action instanceof PaymentNextAction.Redirect redirect) {
            generator.writeStringField("type", "redirect");
            generator.writeStringField("url", redirect.url());
        } else if (action instanceof PaymentNextAction.OpenInClient openInClient) {
            generator.writeStringField("type", "open_in_client");
            generator.writeStringField("url", openInClient.url());
            generator.writeStringField("client", openInClient.client());
        } else if (action instanceof PaymentNextAction.DeepLink deepLink) {
            generator.writeStringField("type", "deep_link");
            generator.writeStringField("url", deepLink.url());
            generator.writeStringField("client", deepLink.client());
        } else if (action instanceof PaymentNextAction.FormPost formPost) {
            generator.writeStringField("type", "form_post");
            generator.writeStringField("url", formPost.url());
            generator.writeObjectField("fields", formPost.fields());
        } else {
            throw new IOException("unsupported payment action: " + action.getClass().getName());
        }
        generator.writeEndObject();
    }

    @Override
    public void serializeWithType(PaymentNextAction action, JsonGenerator generator,
                                  SerializerProvider provider, TypeSerializer typeSerializer)
            throws IOException {
        // The explicit type is written by serialize above. Calling the type
        // serializer as well would duplicate the discriminator property.
        serialize(action, generator, provider);
    }
}
