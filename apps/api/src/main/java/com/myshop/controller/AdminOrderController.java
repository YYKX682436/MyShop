package com.myshop.controller;

import com.myshop.annotation.LogOperation;
import com.myshop.common.ApiResponse;
import com.myshop.model.request.AdminManualFulfillRequest;
import com.myshop.model.request.AdminOrderSearchRequest;
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

    @LogOperation(action = "order.mark_paid", targetType = "ORDER", targetId = "#id", detail = "'手动标记已付（管理员确认）'")
    @PostMapping("/{id}/mark-paid")
    public ApiResponse<Void> markPaid(@PathVariable UUID id, @RequestBody MarkPaidRequest request) {
        adminOrderService.markPaid(id, request.reason(), request.confirmed());
        return ApiResponse.success();
    }

    @LogOperation(action = "order.manual_deliver", targetType = "ORDER", targetId = "#id", detail = "#request.auditDetail()")
    @PostMapping("/{id}/fulfill")
    public ApiResponse<?> completeManualItems(@PathVariable UUID id,
                                              @Valid @RequestBody AdminManualFulfillRequest request) {
        return ApiResponse.success(adminOrderService.completeManualItems(id, request));
    }

    public record MarkPaidRequest(String reason, boolean confirmed) {}
}
