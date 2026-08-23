package com.myshop.controller;

import com.myshop.annotation.LogOperation;
import com.myshop.common.ApiResponse;
import com.myshop.service.AdminUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    @GetMapping
    public ApiResponse<?> listUsers(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "20") int pageSize) {
        return ApiResponse.success(adminUserService.listUsers(keyword, page, pageSize));
    }

    @LogOperation(action = "user.toggle", targetType = "USER", targetId = "#id", detail = "'切换用户状态'")
    @PostMapping("/{id}/toggle")
    public ApiResponse<Void> toggleUser(@PathVariable UUID id, @RequestBody Map<String, Object> request) {
        int isDeleted = ((Number) request.get("is_deleted")).intValue();
        adminUserService.toggleUser(id, isDeleted);
        return ApiResponse.success();
    }

    @LogOperation(action = "user.reset_password", targetType = "USER", targetId = "#id", detail = "'管理员重置密码'")
    @PostMapping("/{id}/reset-password")
    public ApiResponse<Void> resetPassword(@PathVariable UUID id, @RequestBody Map<String, String> request) {
        adminUserService.resetPassword(id, request.get("new_password"));
        return ApiResponse.success();
    }
}
