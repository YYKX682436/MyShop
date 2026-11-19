package com.myshop.controller;

import com.myshop.annotation.LogOperation;
import com.myshop.common.ApiResponse;
import com.myshop.model.request.UserSecretPriceRequest;
import com.myshop.model.response.UserSecretPriceResponse;
import com.myshop.service.impl.UserSecretPriceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/admin/users/{userId}/secret-prices")
@RequiredArgsConstructor
public class AdminUserSecretPriceController {
    private final UserSecretPriceService service;

    @GetMapping public ApiResponse<List<UserSecretPriceResponse>> list(@PathVariable UUID userId) {
        return ApiResponse.success(service.list(userId));
    }
    @LogOperation(action = "secret_price.create", targetType = "USER_SECRET_PRICE",
            targetId = "#result.getData().id()",
            detail = "'新增用户密价：用户=' + #userId + ' 商品=' + #result.getData().productId() + ' 规格=' + #result.getData().specId()")
    @PostMapping public ApiResponse<UserSecretPriceResponse> create(@PathVariable UUID userId, @RequestBody UserSecretPriceRequest request) {
        return ApiResponse.success(service.create(userId, request));
    }
    @LogOperation(action = "secret_price.update", targetType = "USER_SECRET_PRICE", targetId = "#id",
            detail = "'修改用户密价：用户=' + #userId + ' 密价记录=' + #id + ' 商品=' + #result.getData().productId() + ' 规格=' + #result.getData().specId()")
    @PutMapping("/{id}") public ApiResponse<UserSecretPriceResponse> update(@PathVariable UUID userId, @PathVariable UUID id, @RequestBody UserSecretPriceRequest request) {
        return ApiResponse.success(service.update(userId, id, request));
    }
    @LogOperation(action = "secret_price.delete", targetType = "USER_SECRET_PRICE", targetId = "#id",
            detail = "'删除用户密价：用户=' + #userId + ' 密价记录=' + #id")
    @DeleteMapping("/{id}") public ApiResponse<Void> delete(@PathVariable UUID userId, @PathVariable UUID id) {
        service.delete(userId, id);
        return ApiResponse.success();
    }
}
