package com.myshop.service;

import com.myshop.payment.PaymentProvider;

public interface WebhookService {

    PaymentProvider.WebhookAcknowledgement handleWebhook(
            String providerId, PaymentProvider.WebhookRequest request);
}
