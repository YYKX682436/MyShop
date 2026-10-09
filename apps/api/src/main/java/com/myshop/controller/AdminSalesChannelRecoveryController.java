package com.myshop.controller;

import com.myshop.annotation.LogOperation;
import com.myshop.common.ApiResponse;
import com.myshop.saleschannel.model.SalesChannelRecoveryDtos.RecoveryPermitSummary;
import com.myshop.saleschannel.model.SalesChannelRecoveryDtos.RecoveryPlan;
import com.myshop.saleschannel.model.SalesChannelRecoveryDtos.RecoveryReasonRequest;
import com.myshop.service.SalesChannelFailedEnsureRecoveryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Operator-only control plane for the one frozen failed ENSURE recovery. */
@RestController
@RequiredArgsConstructor
public class AdminSalesChannelRecoveryController {

    private final SalesChannelFailedEnsureRecoveryService recoveryService;

    @PostMapping("/admin/sales-channel-listings/{listingId}/operations/{operationId}/recovery/plan")
    public ApiResponse<RecoveryPlan> plan(@PathVariable UUID listingId, @PathVariable UUID operationId) {
        return ApiResponse.success(recoveryService.plan(listingId, operationId));
    }

    @LogOperation(action = "sales_channel.failed_ensure_recovery_prepare",
            targetType = "SALES_CHANNEL_RECOVERY", targetId = "#operationId", detail = "#request.reason()")
    @PostMapping("/admin/sales-channel-listings/{listingId}/operations/{operationId}/recovery/prepare")
    public ApiResponse<RecoveryPermitSummary> prepare(@PathVariable UUID listingId,
                                                       @PathVariable UUID operationId,
                                                       @Valid @RequestBody RecoveryReasonRequest request) {
        return ApiResponse.success(recoveryService.prepare(
                listingId, operationId, currentOperator(), request.reason()));
    }

    @LogOperation(action = "sales_channel.failed_ensure_recovery_arm",
            targetType = "SALES_CHANNEL_RECOVERY", targetId = "#permitId", detail = "'arm exact failed ENSURE permit'")
    @PostMapping("/admin/sales-channel-recovery/{permitId}/arm")
    public ApiResponse<RecoveryPermitSummary> arm(@PathVariable UUID permitId) {
        return ApiResponse.success(recoveryService.arm(permitId, currentOperator()));
    }

    @LogOperation(action = "sales_channel.failed_ensure_recovery_go",
            targetType = "SALES_CHANNEL_RECOVERY", targetId = "#permitId", detail = "'GO exact failed ENSURE recovery'")
    @PostMapping("/admin/sales-channel-recovery/{permitId}/go")
    public ApiResponse<RecoveryPermitSummary> go(@PathVariable UUID permitId) {
        return ApiResponse.success(recoveryService.go(permitId, currentOperator()));
    }

    @LogOperation(action = "sales_channel.failed_ensure_recovery_cancel",
            targetType = "SALES_CHANNEL_RECOVERY", targetId = "#permitId", detail = "#request.reason()")
    @PostMapping("/admin/sales-channel-recovery/{permitId}/cancel")
    public ApiResponse<RecoveryPermitSummary> cancel(@PathVariable UUID permitId,
                                                      @Valid @RequestBody RecoveryReasonRequest request) {
        return ApiResponse.success(recoveryService.cancel(permitId, currentOperator(), request.reason()));
    }

    @GetMapping("/admin/sales-channel-recovery/{permitId}")
    public ApiResponse<RecoveryPermitSummary> observe(@PathVariable UUID permitId) {
        return ApiResponse.success(recoveryService.observe(permitId));
    }

    private static String currentOperator() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getName())
                || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new IllegalStateException("RECOVERY_OPERATOR_AUTHENTICATION_REQUIRED");
        }
        return authentication.getName();
    }
}
