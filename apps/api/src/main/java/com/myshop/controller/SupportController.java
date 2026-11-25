package com.myshop.controller;

import com.myshop.common.ApiResponse;
import com.myshop.service.SupportService;
import com.myshop.service.SupportTimelineService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping({"/support", "/admin/support"})
@RequiredArgsConstructor
public class SupportController {
    private final SupportService support;
    private final SupportTimelineService timeline;
    public record Read(List<String> cursors, Long cursor) {}
    private boolean admin(HttpServletRequest request) { return request.getServletPath().startsWith("/admin/support"); }
    private List<MultipartFile> files(MultipartFile[] files) { return files == null ? List.of() : Arrays.asList(files); }

    @GetMapping("/summary")
    public ApiResponse<?> summary(@RequestHeader(value="X-Session-Token", required=false) String token) {
        return ApiResponse.success(timeline.customerSummary(token));
    }

    @GetMapping("/general")
    public ApiResponse<?> general(@RequestHeader(value="X-Session-Token", required=false) String token) {
        return ApiResponse.success(support.generalContext(token));
    }

    @GetMapping("/conversations")
    public ApiResponse<?> list(HttpServletRequest request,
                               @RequestHeader(value="X-Session-Token", required=false) String token,
                               @RequestParam(required=false) String site,
                               @RequestParam(required=false) String kind,
                               @RequestParam(defaultValue="false") boolean unread,
                               @RequestParam(defaultValue="false") boolean pending,
                               @RequestParam(required=false) String order,
                               @RequestParam(required=false) UUID level,
                               @RequestParam(defaultValue="0") int page,
                               @RequestParam(defaultValue="30") int size) {
        return ApiResponse.success(timeline.list(admin(request), token, site, kind, unread, pending, order, level, page, size));
    }

    @GetMapping("/conversations/{id}")
    public ApiResponse<?> detail(HttpServletRequest request, @PathVariable UUID id,
                                 @RequestHeader(value="X-Session-Token", required=false) String token) {
        return ApiResponse.success(timeline.detail(id, admin(request), token));
    }

    @GetMapping("/conversations/{id}/activities")
    public ApiResponse<?> activities(HttpServletRequest request, @PathVariable UUID id,
                                     @RequestHeader(value="X-Session-Token", required=false) String token,
                                     @RequestParam(required=false) Long after, @RequestParam(required=false) Long before,
                                     @RequestParam(defaultValue="40") int size) {
        return ApiResponse.success(timeline.activities(id, admin(request), token, after, before, size));
    }

    @PostMapping("/conversations/{id}/read")
    public ApiResponse<Void> read(HttpServletRequest request, @PathVariable UUID id, @RequestBody Read read,
                                  @RequestHeader(value="X-Session-Token", required=false) String token) {
        List<String> cursors = read.cursors() != null ? read.cursors()
                : read.cursor() == null ? List.of() : List.of(Long.toString(read.cursor()));
        timeline.markRead(id, admin(request), token, cursors); return ApiResponse.success();
    }

    @PostMapping("/conversations/send")
    public ApiResponse<?> send(HttpServletRequest request,
                                @RequestHeader(value="X-Session-Token", required=false) String token,
                                @RequestParam(value="conversation_id", required=false) UUID conversationId,
                                @RequestParam(value="order_id", required=false) UUID orderId,
                                @RequestParam(value="source_announcement_id", required=false) UUID sourceAnnouncementId,
                                @RequestParam(value="client_key") UUID clientKey,
                                @RequestParam(value="content", required=false) String content,
                                @RequestParam(value="attachments", required=false) MultipartFile[] files) {
        return ApiResponse.success(support.send(conversationId, orderId, sourceAnnouncementId,
                clientKey, content, files(files), admin(request), token));
    }

    @PostMapping("/conversations/{id}/after-sale-reply")
    public ApiResponse<Void> reply(HttpServletRequest request, @PathVariable UUID id,
                                    @RequestHeader(value="X-Session-Token", required=false) String token,
                                    @RequestParam(value="order_id", required=false) UUID orderId,
                                    @RequestParam(value="request_id") UUID requestId,
                                    @RequestParam(value="round") Integer round,
                                    @RequestParam(value="client_key") UUID clientKey,
                                    @RequestParam(value="content", required=false) String content,
                                    @RequestParam(value="attachments", required=false) MultipartFile[] files) {
        support.replyAfterSale(id, clientKey, content, orderId, requestId, round,
                files(files), admin(request), token); return ApiResponse.success();
    }

    @GetMapping("/orders/{id}")
    public ApiResponse<?> order(HttpServletRequest request, @PathVariable UUID id,
                                @RequestParam(value="request_id", required=false) UUID requestId,
                                @RequestParam(value="round", required=false) Integer round,
                                @RequestHeader(value="X-Session-Token", required=false) String token) {
        return ApiResponse.success(admin(request) ? support.adminOrderContext(id) : support.orderContext(id, requestId, round, token));
    }

    @GetMapping("/attachments/{id}")
    public ResponseEntity<org.springframework.core.io.Resource> attachment(HttpServletRequest request, @PathVariable UUID id,
                                @RequestHeader(value="X-Session-Token", required=false) String token) {
        SupportService.Download download = support.openAttachment(id, admin(request), token);
        boolean svg = "image/svg+xml".equals(download.contentType());
        MediaType type = svg ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(download.contentType());
        String name = download.filename().replaceAll("[\\r\\n\"]", "_");
        return ResponseEntity.ok().contentType(type).contentLength(download.size()).cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, (svg ? ContentDisposition.attachment() : ContentDisposition.inline())
                        .filename(name, StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff").body(download.resource());
    }
}
