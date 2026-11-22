package com.myshop.controller;

import com.myshop.common.ApiResponse;
import com.myshop.context.RequestContext;
import com.myshop.context.SiteRequestContext;
import com.myshop.service.SubsiteManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/subsite")
@RequiredArgsConstructor
public class SubsiteController {
    private final SubsiteManagementService service;
    @GetMapping("/current") public ApiResponse<?> current() {
        var site = SiteRequestContext.getSite();
        return ApiResponse.success(site == null ? null : service.view(site));
    }
    @GetMapping("/opening") public ApiResponse<?> opening() { return ApiResponse.success(service.opening(RequestContext.getUserId())); }
    @GetMapping("/mine") public ApiResponse<?> mine() { return ApiResponse.success(service.mine(RequestContext.getUserId())); }
    @PostMapping("/open") public ApiResponse<?> open(@RequestBody SubsiteManagementService.OpenRequest request) {
        return ApiResponse.success(service.open(RequestContext.getUserId(), request, false));
    }
    @GetMapping("/overview") public ApiResponse<?> overview() { return ApiResponse.success(service.overview(RequestContext.getUserId())); }
    @GetMapping("/orders") public ApiResponse<?> orders(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) { return ApiResponse.success(service.orders(RequestContext.getUserId(),page,size)); }
    @GetMapping("/customers") public ApiResponse<?> customers(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.customers(RequestContext.getUserId(), page, size));
    }
    @PutMapping("/customers/{id}/level") public ApiResponse<?> level(@PathVariable java.util.UUID id,
            @RequestBody SubsiteManagementService.LevelRequest request) {
        service.changeCustomerLevel(RequestContext.getUserId(), id, request); return ApiResponse.success();
    }
    @PutMapping("/mine") public ApiResponse<?> update(@RequestBody SubsiteManagementService.SiteUpdate request) {
        return ApiResponse.success(service.updateMine(RequestContext.getUserId(), request));
    }
    @PostMapping("/mine/favicon") public ApiResponse<?> uploadFavicon(@RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        return ApiResponse.success(service.uploadFavicon(RequestContext.getUserId(), file));
    }
}
