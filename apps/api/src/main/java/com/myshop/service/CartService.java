package com.myshop.service;

import java.util.Map;
import java.util.UUID;

public interface CartService {

    Map<String, Object> getCart(UUID userId, String sessionToken);

    String addItem(UUID userId, String sessionToken, Map<String, Object> request);

    void updateItem(UUID userId, String sessionToken, UUID itemId, int quantity);

    /** Updates quantity and, when present, the structured customer inputs. */
    void updateItem(UUID userId, String sessionToken, UUID itemId, int quantity, Map<String, Object> request);

    void deleteItem(UUID userId, String sessionToken, UUID itemId);
}
