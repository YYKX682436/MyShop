package com.myshop.service;

import com.myshop.model.response.AdminOrderRefundResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Owns the customer after-sale lifecycle and its order-state transitions. */
public interface AfterSaleService {

    Map<String, Object> apply(UUID orderId, UUID userId, String reason,
                               List<MultipartFile> attachments);

    Map<String, Object> buildOrderView(UUID orderId);

    void reject(UUID orderId, String adminReply);

    AdminOrderRefundResponse approve(UUID orderId, boolean confirmed,
                                     boolean reclaimCardKeys);
}
