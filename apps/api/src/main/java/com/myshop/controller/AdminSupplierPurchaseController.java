package com.myshop.controller;

import com.myshop.common.ApiResponse;
import com.myshop.supplier.SupplierAdminOrders;
import com.myshop.supplier.SupplierAftercareService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/admin/orders")
@RequiredArgsConstructor
public class AdminSupplierPurchaseController {
    private final SupplierAdminOrders orders;
    private final SupplierAftercareService aftercare;
    public record ConfirmedOperation(UUID idempotencyKey,boolean confirmed) {}
    public record ComplaintOperation(UUID idempotencyKey,boolean confirmed,UUID afterSaleId,
                                     String topic,String content,java.util.List<UUID> attachmentIds) {}
    @PostMapping("/{orderId}/supplier-purchases/{purchaseId}/refresh")
    public ApiResponse<Map<String,Object>> refresh(@PathVariable UUID orderId,@PathVariable UUID purchaseId) {
        return ApiResponse.success(orders.refresh(orderId,purchaseId));
    }
    @PostMapping("/{orderId}/supplier-purchases/{purchaseId}/reviews")
    public ApiResponse<Map<String,Object>> review(@PathVariable UUID orderId,@PathVariable UUID purchaseId,
                                                   @RequestBody Map<String,String> request) {
        return ApiResponse.success(orders.recordReview(orderId,purchaseId,request.get("outcome")));
    }
    @PostMapping("/{orderId}/supplier-purchases/{purchaseId}/refunds")
    public ApiResponse<Map<String,Object>> refund(@PathVariable UUID orderId,@PathVariable UUID purchaseId,
                                                   @RequestBody ConfirmedOperation request) {
        return ApiResponse.success(aftercare.refund(orderId,purchaseId,request.idempotencyKey(),request.confirmed()));
    }
    @PostMapping("/{orderId}/supplier-purchases/{purchaseId}/complaints")
    public ApiResponse<Map<String,Object>> complaint(@PathVariable UUID orderId,@PathVariable UUID purchaseId,
                                                      @RequestBody ComplaintOperation request) {
        return ApiResponse.success(aftercare.complaint(orderId,purchaseId,request.afterSaleId(),request.idempotencyKey(),
                request.confirmed(),request.topic(),request.content(),request.attachmentIds()));
    }
    @PostMapping("/{orderId}/supplier-purchases/{purchaseId}/cards/{cardId}/query")
    public ApiResponse<Map<String,Object>> queryCard(@PathVariable UUID orderId,@PathVariable UUID purchaseId,@PathVariable UUID cardId) {
        return ApiResponse.success(aftercare.queryCard(orderId,purchaseId,cardId));
    }
    @PostMapping("/{orderId}/supplier-purchases/{purchaseId}/cards/{cardId}/void")
    public ApiResponse<Map<String,Object>> voidCard(@PathVariable UUID orderId,@PathVariable UUID purchaseId,@PathVariable UUID cardId,
                                                     @RequestBody ConfirmedOperation request) {
        return ApiResponse.success(aftercare.voidCard(orderId,purchaseId,cardId,request.idempotencyKey(),request.confirmed()));
    }
    @PostMapping("/{orderId}/supplier-purchases/{purchaseId}/operations/{operationId}/refresh")
    public ApiResponse<Map<String,Object>> refreshOperation(@PathVariable UUID orderId,@PathVariable UUID purchaseId,@PathVariable UUID operationId) {
        return ApiResponse.success(aftercare.refresh(orderId,purchaseId,operationId));
    }
    @PostMapping("/{orderId}/supplier-purchases/{purchaseId}/operations/{operationId}/reviews")
    public ApiResponse<Map<String,Object>> reviewOperation(@PathVariable UUID orderId,@PathVariable UUID purchaseId,@PathVariable UUID operationId,
                                                            @RequestBody Map<String,String> request) {
        return ApiResponse.success(aftercare.recordReview(orderId,purchaseId,operationId,request.get("outcome")));
    }
}
