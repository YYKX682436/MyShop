package com.myshop.service.impl;

import com.myshop.constant.ErrorCode;
import com.myshop.entity.Order;
import com.myshop.exception.BusinessException;
import com.myshop.repository.OrderRepository;
import com.myshop.service.FulfillmentService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AutoFulfillmentServiceImplTest {

    @Test
    void permanentlyShortFirstBatchDoesNotStarveLaterPaidOrders() {
        OrderRepository repository = mock(OrderRepository.class);
        FulfillmentService fulfillment = mock(FulfillmentService.class);
        LocalDateTime createdAt = LocalDateTime.now().minusHours(1);
        List<Order> firstBatch = IntStream.range(0, 50).mapToObj(index -> {
            Order order = new Order();
            order.setId(UUID.randomUUID());
            order.setCreatedAt(createdAt.plusSeconds(index));
            return order;
        }).toList();
        Order later = new Order();
        later.setId(UUID.randomUUID());
        later.setCreatedAt(createdAt.plusSeconds(50));
        when(repository.findPaidOrdersWithPendingAutoItemsAfter(isNull(), isNull(), any(), any()))
                .thenReturn(firstBatch);
        Order last = firstBatch.getLast();
        when(repository.findPaidOrdersWithPendingAutoItemsAfter(
                eq(last.getCreatedAt()), eq(last.getId()), any(), any())).thenReturn(List.of(later));
        firstBatch.forEach(order -> when(fulfillment.fulfillOrder(order.getId()))
                .thenThrow(new BusinessException(ErrorCode.ORDER_OUT_OF_STOCK, "库存不足")));

        new AutoFulfillmentServiceImpl(fulfillment, repository).retryPendingPaidOrders();

        firstBatch.forEach(order -> verify(fulfillment).fulfillOrder(order.getId()));
        verify(fulfillment).fulfillOrder(later.getId());
        verify(repository, times(2)).findPaidOrdersWithPendingAutoItemsAfter(any(), any(), any(), any());
    }
}
