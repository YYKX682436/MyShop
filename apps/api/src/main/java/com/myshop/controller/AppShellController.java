package com.myshop.controller;

import com.myshop.common.ApiResponse;
import com.myshop.model.response.AppShellResolveResponse;
import com.myshop.service.AppShellService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/app-shell")
@RequiredArgsConstructor
public class AppShellController {
    private final AppShellService appShellService;

    @GetMapping("/resolve")
    public ApiResponse<AppShellResolveResponse> resolve(@RequestParam(required = false) String origin) {
        return ApiResponse.success(appShellService.resolve(origin));
    }
}
