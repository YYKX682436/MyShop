package com.myshop.controller;

import com.myshop.common.ApiResponse;
import com.myshop.common.PageResult;
import com.myshop.model.request.AdminAdjustBalanceRequest;
import com.myshop.model.request.AdminResetPasswordRequest;
import com.myshop.model.request.AdminToggleUserStatusRequest;
import com.myshop.model.request.AdminUpdateUserProfileRequest;
import com.myshop.model.request.UserLevelAssignmentRequest;
import com.myshop.model.response.AdminOperationLogResponse;
import com.myshop.model.response.AdminUserDetailResponse;
import com.myshop.model.response.AdminUserListResponse;
import com.myshop.model.response.AdminUserOrderResponse;
import com.myshop.model.response.BalanceTransactionResponse;
import com.myshop.model.response.UserLevelResponse;
import com.myshop.service.AdminUserService;
import com.myshop.service.UserLevelService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;
    private final UserLevelService userLevelService;

    @GetMapping
    public ApiResponse<PageResult<AdminUserListResponse>> listUsers(
            @RequestParam(required = false) String keyword,
            @RequestParam(value = "is_deleted", required = false) Integer isDeleted,
            @RequestParam(value = "user_level_id", required = false) UUID userLevelId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "20") int pageSize) {
        return ApiResponse.success(adminUserService.listUsers(keyword, isDeleted, userLevelId, page, pageSize));
    }

    @GetMapping("/{id}")
    public ApiResponse<AdminUserDetailResponse> getUserDetail(@PathVariable UUID id) {
        return ApiResponse.success(adminUserService.getUserDetail(id));
    }

    @GetMapping("/{id}/orders")
    public ApiResponse<PageResult<AdminUserOrderResponse>> listOrders(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "20") int pageSize) {
        return ApiResponse.success(adminUserService.listUserOrders(id, page, pageSize));
    }

    @GetMapping("/{id}/balance-transactions")
    public ApiResponse<PageResult<BalanceTransactionResponse>> listBalanceTransactions(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "20") int pageSize) {
        return ApiResponse.success(adminUserService.listBalanceTransactions(id, page, pageSize));
    }

    @GetMapping("/{id}/operation-logs")
    public ApiResponse<PageResult<AdminOperationLogResponse>> listOperationLogs(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "20") int pageSize) {
        return ApiResponse.success(adminUserService.listUserOperationLogs(id, page, pageSize));
    }

    @PutMapping("/{id}/profile")
    public ApiResponse<AdminUserDetailResponse> updateProfile(
            @PathVariable UUID id,
            @Valid @RequestBody AdminUpdateUserProfileRequest request) {
        return ApiResponse.success(adminUserService.updateProfile(id, request));
    }

    @PostMapping("/{id}/balance")
    public ApiResponse<BalanceTransactionResponse> adjustBalance(
            @PathVariable UUID id,
            @Valid @RequestBody AdminAdjustBalanceRequest request) {
        return ApiResponse.success(adminUserService.adjustBalance(id, request));
    }

    @PostMapping("/{id}/toggle")
    public ApiResponse<Void> toggleUser(
            @PathVariable UUID id,
            @Valid @RequestBody AdminToggleUserStatusRequest request) {
        adminUserService.toggleUser(id, request);
        return ApiResponse.success();
    }

    @PostMapping("/{id}/reset-password")
    public ApiResponse<Void> resetPassword(
            @PathVariable UUID id,
            @Valid @RequestBody AdminResetPasswordRequest request) {
        adminUserService.resetPassword(id, request);
        return ApiResponse.success();
    }

    @PostMapping("/{id}/level")
    public ApiResponse<UserLevelResponse> changeLevel(
            @PathVariable UUID id,
            @RequestBody UserLevelAssignmentRequest request) {
        return ApiResponse.success(userLevelService.assignUser(id, request));
    }
}
