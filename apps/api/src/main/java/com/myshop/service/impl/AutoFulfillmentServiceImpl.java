package com.myshop.service.impl;

import com.myshop.exception.BusinessException;
import com.myshop.entity.Order;
import com.myshop.repository.OrderRepository;
import com.myshop.service.AutoFulfillmentService;
import com.myshop.service.FulfillmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.myshop.supplier.SupplierPurchaseWorker;

import java.util.UUID;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AutoFulfillmentServiceImpl implements AutoFulfillmentService {

    private static final int RETRY_BATCH_SIZE = 50;

    private final FulfillmentService fulfillmentService;
    private final OrderRepository orderRepository;
    private SupplierPurchaseWorker supplierWorker;

    @Autowired
    public void setSupplierWorker(SupplierPurchaseWorker supplierWorker) { this.supplierWorker=supplierWorker; }

    @Override
    public void trigger(UUID orderId) {
        try {
            fulfillmentService.fulfillOrder(orderId);
        } catch (BusinessException exception) {
            // A shortage is expected to be recoverable. The payment fact is in
            // a different committed transaction and remains PAID.
            log.warn("Automatic fulfillment deferred for order {}: {}", orderId, exception.getMessage());
        } catch (RuntimeException exception) {
            // Do not include provider credentials or card content in a retry
            // log. The scheduler will retry safely.
            log.warn("Automatic fulfillment failed for order {}", orderId);
        }
        if (supplierWorker != null) {
            try { supplierWorker.enqueueOrder(orderId); }
            catch (RuntimeException ignored) {
                // Payment is already committed; the independent recovery scan will retry safely.
                log.warn("Supplier fulfillment enqueue deferred for order {}",orderId);
            }
        }
    }

    @Override
    @Scheduled(
            fixedDelayString = "${fulfillment.retry-interval-ms:60000}",
            initialDelayString = "${fulfillment.retry-initial-delay-ms:60000}")
    public void retryPendingPaidOrders() {
        LocalDateTime scanStartedAt = LocalDateTime.now();
        LocalDateTime afterCreatedAt = null;
        UUID afterId = null;
        while (true) {
            List<Order> batch = orderRepository.findPaidOrdersWithPendingAutoItemsAfter(
                    afterCreatedAt, afterId, scanStartedAt, PageRequest.of(0, RETRY_BATCH_SIZE));
            if (batch.isEmpty()) return;
            batch.forEach(order -> trigger(order.getId()));
            // A failed old order must not monopolize the first batch. A keyset
            // also avoids skipping rows when successful deliveries disappear.
            Order last = batch.getLast();
            afterCreatedAt = last.getCreatedAt();
            afterId = last.getId();
            if (batch.size() < RETRY_BATCH_SIZE) return;
        }
    }
}
