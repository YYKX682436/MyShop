package com.myshop.controller;

import com.myshop.common.ApiResponse;
import com.myshop.service.MediaLibraryService;
import com.myshop.service.MediaMaintenanceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/media")
public class AdminMediaController {
    private final MediaLibraryService media;
    private final MediaMaintenanceService maintenance;

    public AdminMediaController(MediaLibraryService media, MediaMaintenanceService maintenance) {
        this.media = media;
        this.maintenance = maintenance;
    }

    @GetMapping
    public ApiResponse<MediaLibraryService.MediaPage> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "24") int pageSize,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "desc") String order,
            @RequestParam(name = "qr_only", defaultValue = "false") boolean qrOnly) {
        return ApiResponse.success(media.list(page, pageSize, query, order, qrOnly));
    }

    @GetMapping("/inspection")
    public ApiResponse<MediaMaintenanceService.Inspection> inspect(@RequestParam String filename) {
        return ApiResponse.success(maintenance.inspect(filename));
    }

    @DeleteMapping
    public ApiResponse<?> delete(@RequestParam String filename) {
        maintenance.delete(filename);
        return ApiResponse.success();
    }

    @PostMapping("/optimize")
    public ApiResponse<MediaMaintenanceService.Optimization> optimize(@RequestParam String filename) {
        return ApiResponse.success(maintenance.optimize(filename));
    }
}
