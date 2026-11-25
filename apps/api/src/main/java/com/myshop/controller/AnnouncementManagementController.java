package com.myshop.controller;

import com.myshop.common.ApiResponse;
import com.myshop.constant.ErrorCode;
import com.myshop.exception.BusinessException;
import com.myshop.service.AnnouncementService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/{scope}/announcements")
@RequiredArgsConstructor
public class AnnouncementManagementController {
    private final AnnouncementService announcements;
    public record Save(UUID requestKey, String title, String body, String audienceType,
                       List<UUID> audienceLevelIds, boolean publish) {}

    private UUID site(String scope) {
        if (!"admin".equals(scope) && !"subsite".equals(scope))
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权管理公告");
        return announcements.managementSite("admin".equals(scope));
    }

    @GetMapping
    public ApiResponse<?> list(@PathVariable String scope) {
        UUID siteId = site(scope);
        return ApiResponse.success(announcements.managementData(siteId));
    }

    @PostMapping
    public ApiResponse<?> create(@PathVariable String scope, @RequestBody Save request) {
        return ApiResponse.success(Map.of("id", announcements.save(site(scope), null, edit(request))));
    }

    @PutMapping("/{id}")
    public ApiResponse<?> edit(@PathVariable String scope, @PathVariable UUID id, @RequestBody Save request) {
        return ApiResponse.success(Map.of("id", announcements.save(site(scope), id, edit(request))));
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<Void> publish(@PathVariable String scope, @PathVariable UUID id) {
        announcements.publish(site(scope), id); return ApiResponse.success();
    }

    @PostMapping("/{id}/withdraw")
    public ApiResponse<Void> withdraw(@PathVariable String scope, @PathVariable UUID id) {
        announcements.withdraw(site(scope), id); return ApiResponse.success();
    }

    private AnnouncementService.Edit edit(Save request) {
        if (request == null) throw new BusinessException(ErrorCode.BAD_REQUEST, "通知内容不完整");
        return new AnnouncementService.Edit(request.requestKey(), request.title(), request.body(),
                request.audienceType(), request.audienceLevelIds(), request.publish());
    }
}
