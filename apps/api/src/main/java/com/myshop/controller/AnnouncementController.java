package com.myshop.controller;

import com.myshop.common.ApiResponse;
import com.myshop.context.SiteRequestContext;
import com.myshop.service.AnnouncementService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/announcements")
@RequiredArgsConstructor
public class AnnouncementController {
    private final AnnouncementService announcements;
    public record Read(int version) {}

    @GetMapping("/summary")
    public ApiResponse<?> summary(@RequestHeader(value = "X-Session-Token", required = false) String token) {
        return ApiResponse.success(announcements.summary(SiteRequestContext.getSiteId(), token));
    }

    @GetMapping
    public ApiResponse<?> list(@RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "30") int size,
                               @RequestHeader(value = "X-Session-Token", required = false) String token) {
        return ApiResponse.success(announcements.publicList(SiteRequestContext.getSiteId(), page, size, token));
    }

    @GetMapping("/{id}")
    public ApiResponse<?> detail(@PathVariable UUID id) {
        return ApiResponse.success(announcements.publicDetail(SiteRequestContext.getSiteId(), id));
    }

    @PostMapping("/{id}/read")
    public ApiResponse<Void> read(@PathVariable UUID id, @RequestBody Read read,
                                  @RequestHeader(value = "X-Session-Token", required = false) String token) {
        announcements.markRead(SiteRequestContext.getSiteId(), id, read.version(), token);
        return ApiResponse.success();
    }
}
