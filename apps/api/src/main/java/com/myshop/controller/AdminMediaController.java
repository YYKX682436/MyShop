package com.myshop.controller;

import com.myshop.common.ApiResponse;
import com.myshop.service.MediaLibraryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/media")
public class AdminMediaController {
    private final MediaLibraryService media;

    public AdminMediaController(MediaLibraryService media) { this.media = media; }

    @GetMapping
    public ApiResponse<MediaLibraryService.MediaPage> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "24") int pageSize,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "desc") String order,
            @RequestParam(name = "qr_only", defaultValue = "false") boolean qrOnly) {
        return ApiResponse.success(media.list(page, pageSize, query, order, qrOnly));
    }
}
