package com.myshop.service;

import java.util.UUID;

public interface EmailService {

    void sendDeliveryEmail(UUID orderId, String lookupToken);
}
