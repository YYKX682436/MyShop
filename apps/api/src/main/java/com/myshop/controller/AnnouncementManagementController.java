package com.myshop.controller;

import com.myshop.common.ApiResponse;
import com.myshop.constant.ErrorCode;
import com.myshop.exception.BusinessException;
import com.myshop.service.AnnouncementService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/{scope}/announcements")
@RequiredArgsConstructor
public class AnnouncementManagementController {
    private final AnnouncementService announcements;

    private UUID site(String scope) {
        if (!"admin".equals(scope) && !"subsite".equals(scope))
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权管理公告");
        return announcements.managementSite("admin".equals(scope));
    }

    @GetMapping
    public ApiResponse<?> list(@PathVariable String scope) {
        UUID siteId = site(scope);
        return ApiResponse.success(Map.of("announcements", announcements.managementList(siteId),
                "channels", announcements.managementChannels(siteId)));
    }

    @PostMapping
    public ApiResponse<?> create(@PathVariable String scope, @RequestBody AnnouncementService.Edit edit) {
        return ApiResponse.success(Map.of("id", announcements.create(site(scope), edit)));
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> edit(@PathVariable String scope, @PathVariable UUID id, @RequestBody AnnouncementService.Edit edit) {
        announcements.edit(site(scope), id, edit); return ApiResponse.success();
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<Void> publish(@PathVariable String scope, @PathVariable UUID id) {
        announcements.publish(site(scope), id); return ApiResponse.success();
    }

    @PostMapping("/{id}/withdraw")
    public ApiResponse<Void> withdraw(@PathVariable String scope, @PathVariable UUID id) {
        announcements.withdraw(site(scope), id); return ApiResponse.success();
    }

    @PutMapping("/channels/{channel}")
    public ApiResponse<Void> channel(@PathVariable String scope, @PathVariable String channel,
                                     @RequestBody AnnouncementService.Channel setting) {
        announcements.setChannel(site(scope), channel, setting); return ApiResponse.success();
    }
}
