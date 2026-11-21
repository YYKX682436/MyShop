package com.myshop.controller;

import com.myshop.common.ApiResponse;
import com.myshop.context.RequestContext;
import com.myshop.service.SubsiteManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/admin/subsites")
@RequiredArgsConstructor
public class AdminSubsiteController {
    private final SubsiteManagementService service;
    @GetMapping public ApiResponse<?> list() { return ApiResponse.success(service.list()); }
    @GetMapping("/{id}/customers") public ApiResponse<?> customers(@PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.customersBySite(id, page, size));
    }
    @GetMapping("/settings") public ApiResponse<?> settings() { return ApiResponse.success(service.getSettings()); }
    @PutMapping("/settings") public ApiResponse<?> settings(@RequestBody SubsiteManagementService.Settings request) {
        return ApiResponse.success(service.updateSettings(request));
    }
    @PostMapping public ApiResponse<?> create(@RequestBody SubsiteManagementService.OpenRequest request) {
        return ApiResponse.success(service.open(RequestContext.getUserId(), request, true));
    }
    @PutMapping("/{id}/status") public ApiResponse<?> status(@PathVariable UUID id, @RequestBody SubsiteManagementService.StatusRequest request) {
        return ApiResponse.success(service.status(id, request.enabled()));
    }
    @PostMapping("/{id}/transfer") public ApiResponse<?> transfer(@PathVariable UUID id, @RequestBody SubsiteManagementService.TransferRequest request) {
        service.transfer(id,request); return ApiResponse.success();
    }
}
