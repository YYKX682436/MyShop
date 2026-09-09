package com.myshop.service.impl;

import com.myshop.exception.BusinessException;
import com.myshop.repository.OrderRepository;
import com.myshop.service.AutoFulfillmentService;
import com.myshop.service.FulfillmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AutoFulfillmentServiceImpl implements AutoFulfillmentService {

    private static final int RETRY_BATCH_SIZE = 50;

    private final FulfillmentService fulfillmentService;
    private final OrderRepository orderRepository;

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
    }

    @Override
    @Scheduled(
            fixedDelayString = "${fulfillment.retry-interval-ms:60000}",
            initialDelayString = "${fulfillment.retry-initial-delay-ms:60000}")
    public void retryPendingPaidOrders() {
        orderRepository.findPaidOrdersWithPendingAutoItems(PageRequest.of(0, RETRY_BATCH_SIZE))
                .forEach(order -> trigger(order.getId()));
    }
}
