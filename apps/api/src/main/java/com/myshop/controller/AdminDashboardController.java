package com.myshop.controller;

import com.myshop.common.ApiResponse;
import com.myshop.service.DashboardService;
import com.myshop.service.system.SystemStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/dashboard")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final DashboardService dashboardService;
    private final SystemStatusService systemStatusService;

    @GetMapping
    public ApiResponse<?> getStats() {
        return ApiResponse.success(dashboardService.getStats());
    }

    @GetMapping("/sales-trend")
    public ApiResponse<?> getSalesTrend(
            @RequestParam(defaultValue = "7d") String period,
            @RequestParam(value = "start_date", required = false) String startDate,
            @RequestParam(value = "end_date", required = false) String endDate) {
        return ApiResponse.success(dashboardService.getSalesTrend(period, startDate, endDate));
    }

    @GetMapping("/system-status")
    public ApiResponse<?> getSystemStatus() {
        return ApiResponse.success(systemStatusService.getStatus());
    }
}
