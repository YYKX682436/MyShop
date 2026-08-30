package com.myshop.controller;

import com.myshop.common.ApiResponse;
import com.myshop.common.PageResult;
import com.myshop.model.request.CreateAdministratorRequest;
import com.myshop.model.request.ResetAdministratorPasswordRequest;
import com.myshop.model.request.ToggleAdministratorStatusRequest;
import com.myshop.model.request.UpdateAdministratorRequest;
import com.myshop.model.response.AdminOperationLogResponse;
import com.myshop.model.response.AdministratorResponse;
import com.myshop.service.AdministratorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/admin/administrators")
@RequiredArgsConstructor
public class AdministratorController {

    private final AdministratorService administratorService;

    @GetMapping
    public ApiResponse<PageResult<AdministratorResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(value = "is_deleted", required = false) Integer isDeleted,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "20") int pageSize) {
        return ApiResponse.success(administratorService.listAdministrators(keyword, isDeleted, page, pageSize));
    }

    @PostMapping
    public ApiResponse<AdministratorResponse> create(@Valid @RequestBody CreateAdministratorRequest request) {
        return ApiResponse.success(administratorService.createAdministrator(request));
    }

    @GetMapping("/{id}")
    public ApiResponse<AdministratorResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(administratorService.getAdministrator(id));
    }

    @PutMapping("/{id}")
    public ApiResponse<AdministratorResponse> update(@PathVariable UUID id,
                                                     @Valid @RequestBody UpdateAdministratorRequest request) {
        return ApiResponse.success(administratorService.updateAdministrator(id, request));
    }

    @PostMapping("/{id}/toggle")
    public ApiResponse<Void> toggle(@PathVariable UUID id,
                                    @Valid @RequestBody ToggleAdministratorStatusRequest request) {
        administratorService.toggleAdministrator(id, request);
        return ApiResponse.success();
    }

    @PostMapping("/{id}/reset-password")
    public ApiResponse<Void> resetPassword(@PathVariable UUID id,
                                           @Valid @RequestBody ResetAdministratorPasswordRequest request) {
        administratorService.resetAdministratorPassword(id, request);
        return ApiResponse.success();
    }

    @GetMapping("/{id}/operation-logs")
    public ApiResponse<PageResult<AdminOperationLogResponse>> operationLogs(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "20") int pageSize) {
        return ApiResponse.success(administratorService.listOperationLogs(id, page, pageSize));
    }
}
