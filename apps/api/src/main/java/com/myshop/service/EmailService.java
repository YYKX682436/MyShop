package com.myshop.service;

import java.util.UUID;
import java.util.List;

public interface EmailService {

    /** Send only the newly completed automatic order items when supplied. */
    void sendDeliveryEmail(UUID orderId, String lookupToken, List<UUID> orderItemIds);

}
