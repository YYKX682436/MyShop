package com.myshop.controller;

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
    @PostMapping public ApiResponse<UserSecretPriceResponse> create(@PathVariable UUID userId, @RequestBody UserSecretPriceRequest request) {
        return ApiResponse.success(service.create(userId, request));
    }
    @PutMapping("/{id}") public ApiResponse<UserSecretPriceResponse> update(@PathVariable UUID userId, @PathVariable UUID id, @RequestBody UserSecretPriceRequest request) {
        return ApiResponse.success(service.update(userId, id, request));
    }
    @DeleteMapping("/{id}") public ApiResponse<Void> delete(@PathVariable UUID userId, @PathVariable UUID id) {
        service.delete(userId, id);
        return ApiResponse.success();
    }
}
