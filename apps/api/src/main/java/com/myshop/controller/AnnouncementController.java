package com.myshop.controller;

import com.myshop.common.ApiResponse;
import com.myshop.context.SiteRequestContext;
import com.myshop.service.AnnouncementService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/announcements")
@RequiredArgsConstructor
public class AnnouncementController {
    private final AnnouncementService announcements;
    public record Read(int version) {}
    public record Refresh(List<UUID> ids) {}

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<?>> summary(@RequestHeader(value = "X-Session-Token", required = false) String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .body(ApiResponse.success(announcements.summary(SiteRequestContext.getSiteId(), token)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<?>> list(@RequestParam(required = false) String before,
                               @RequestParam(defaultValue = "30") int size,
                               @RequestParam(defaultValue = "false") boolean anchor,
                               @RequestHeader(value = "X-Session-Token", required = false) String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .body(ApiResponse.success(announcements.publicList(SiteRequestContext.getSiteId(), before, size, token, anchor)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<?>> detail(@PathVariable UUID id,
                                 @RequestHeader(value = "X-Session-Token", required = false) String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .body(ApiResponse.success(announcements.publicDetail(SiteRequestContext.getSiteId(), id, token)));
    }

    @PostMapping("/{id}/read")
    public ApiResponse<Void> read(@PathVariable UUID id, @RequestBody Read read,
                                  @RequestHeader(value = "X-Session-Token", required = false) String token) {
        announcements.markRead(SiteRequestContext.getSiteId(), id, read.version(), token);
        return ApiResponse.success();
    }

    @PostMapping("/read-all")
    public ApiResponse<?> readAll(@RequestHeader(value = "X-Session-Token", required = false) String token) {
        return ApiResponse.success(announcements.markAllVisibleRead(SiteRequestContext.getSiteId(), token));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<?>> refresh(@RequestBody Refresh refresh,
                                  @RequestHeader(value = "X-Session-Token", required = false) String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .body(ApiResponse.success(announcements.refreshVisible(SiteRequestContext.getSiteId(),
                        refresh == null ? List.of() : refresh.ids(), token)));
    }
}
