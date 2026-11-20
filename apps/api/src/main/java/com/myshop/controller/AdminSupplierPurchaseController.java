package com.myshop.controller;

import com.myshop.common.ApiResponse;
import com.myshop.supplier.SupplierAdminOrders;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/admin/orders")
@RequiredArgsConstructor
public class AdminSupplierPurchaseController {
    private final SupplierAdminOrders orders;
    @PostMapping("/{orderId}/supplier-purchases/{purchaseId}/refresh")
    public ApiResponse<Map<String,Object>> refresh(@PathVariable UUID orderId,@PathVariable UUID purchaseId) {
        return ApiResponse.success(orders.refresh(orderId,purchaseId));
    }
}
