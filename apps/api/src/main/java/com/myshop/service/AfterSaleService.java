package com.myshop.service;

import com.myshop.model.response.AdminOrderRefundResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Owns the customer after-sale lifecycle and its order-state transitions. */
public interface AfterSaleService {

    Map<String, Object> apply(UUID orderId, UUID userId, String reason,
                               List<MultipartFile> attachments);

    Map<String, Object> buildOrderView(UUID orderId);

    Map<UUID, Map<String, Object>> buildOrderSummaries(Collection<UUID> orderIds);

    long countNeedsAdminAttention();

    void reject(UUID orderId, String rejectionReason);

    Map<String, Object> replyFromCustomer(UUID orderId, UUID userId, String content,
                                           List<MultipartFile> attachments);

    Map<String, Object> replyFromAdmin(UUID orderId, String content,
                                        List<MultipartFile> attachments);

    AdminOrderRefundResponse approve(UUID orderId, boolean confirmed,
                                     boolean reclaimCardKeys);
}
