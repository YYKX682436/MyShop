package com.myshop.controller;

import com.myshop.common.ApiResponse;
import com.myshop.service.AfterSaleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Small authenticated summary used by the admin shell notification badge. */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminAttentionController {

    private final AfterSaleService afterSaleService;

    @GetMapping("/attention-summary")
    public ApiResponse<?> summary() {
        return ApiResponse.success(Map.of(
                "after_sale_needs_attention", afterSaleService.countNeedsAdminAttention()));
    }
}
