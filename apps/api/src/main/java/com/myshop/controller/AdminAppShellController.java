package com.myshop.controller;

import com.myshop.annotation.LogOperation;
import com.myshop.common.ApiResponse;
import com.myshop.constant.ErrorCode;
import com.myshop.context.RequestContext;
import com.myshop.context.SiteRequestContext;
import com.myshop.exception.BusinessException;
import com.myshop.model.request.AppShellConfigUpdateRequest;
import com.myshop.model.response.AppShellConfigResponse;
import com.myshop.service.AppShellService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/app-shell/config")
@RequiredArgsConstructor
public class AdminAppShellController {
    private final AppShellService appShellService;

    @GetMapping
    public ApiResponse<AppShellConfigResponse> getConfig() {
        requireMainSuperAdmin();
        return ApiResponse.success(appShellService.getConfig());
    }

    @PutMapping
    @LogOperation(action = "config.app_shell_update", targetType = "SITE_CONFIG", detail = "'更新安卓 App 域名授权'")
    public ApiResponse<AppShellConfigResponse> updateConfig(@RequestBody AppShellConfigUpdateRequest request) {
        requireMainSuperAdmin();
        return ApiResponse.success(appShellService.updateConfig(request));
    }

    private static void requireMainSuperAdmin() {
        if (SiteRequestContext.getSite() != null || !"SUPER_ADMIN".equals(RequestContext.getRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅主站超级管理员可以修改安卓 App 授权");
        }
    }
}
