package com.myshop.controller;

import com.myshop.annotation.LogOperation;
import com.myshop.common.ApiResponse;
import com.myshop.model.request.AdminManualFulfillRequest;
import com.myshop.model.request.AdminRefundRequest;
import com.myshop.model.request.AdminOrderSearchRequest;
import com.myshop.model.response.AdminOrderRefundResponse;
import com.myshop.service.AdminOrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/admin/orders")
@RequiredArgsConstructor
public class AdminOrderController {

    private final AdminOrderService adminOrderService;

    @PostMapping("/search")
    public ApiResponse<?> searchOrders(@RequestBody(required = false) AdminOrderSearchRequest request) {
        return ApiResponse.success(adminOrderService.searchOrders(request));
    }

    @GetMapping("/{id}")
    public ApiResponse<?> getOrderDetail(@PathVariable UUID id) {
        return ApiResponse.success(adminOrderService.getOrderDetail(id));
    }

    @PostMapping("/{id}/mark-paid")
    public ApiResponse<Void> markPaid(@PathVariable UUID id, @Valid @RequestBody MarkPaidRequest request) {
        adminOrderService.markPaid(id, request.reason(), request.confirmed());
        return ApiResponse.success();
    }

    @PostMapping("/{id}/refund")
    public ApiResponse<AdminOrderRefundResponse> refund(@PathVariable UUID id,
                                                        @Valid @RequestBody AdminRefundRequest request) {
        return ApiResponse.success(adminOrderService.refund(id, request.reason(),
                Boolean.TRUE.equals(request.confirmed()), request.reclaimCardKeys()));
    }

    @LogOperation(action = "order.manual_deliver", targetType = "ORDER", targetId = "#id", detail = "#request.auditDetail()")
    @PostMapping("/{id}/fulfill")
    public ApiResponse<?> completeManualItems(@PathVariable UUID id,
                                              @Valid @RequestBody AdminManualFulfillRequest request) {
        return ApiResponse.success(adminOrderService.completeManualItems(id, request));
    }

    public record MarkPaidRequest(String reason, boolean confirmed) {}
}
