package com.myshop.controller;

import com.myshop.common.ApiResponse;
import com.myshop.service.MediaLibraryService;
import com.myshop.service.MediaMaintenanceService;
import com.myshop.service.AvatarMediaOptimizationService;
import com.myshop.service.MediaAssetImageOptimizationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/media")
public class AdminMediaController {
    private final MediaLibraryService media;
    private final MediaMaintenanceService maintenance;
    private final AvatarMediaOptimizationService avatarOptimization;
    private final MediaAssetImageOptimizationService imageOptimization;

    public AdminMediaController(MediaLibraryService media, MediaMaintenanceService maintenance,
                                AvatarMediaOptimizationService avatarOptimization,
                                MediaAssetImageOptimizationService imageOptimization) {
        this.media = media;
        this.maintenance = maintenance;
        this.avatarOptimization = avatarOptimization;
        this.imageOptimization = imageOptimization;
    }

    @GetMapping
    public ApiResponse<MediaLibraryService.MediaPage> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "24") int pageSize,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "desc") String order,
            @RequestParam(defaultValue = "time") String sort,
            @RequestParam(defaultValue = "") String type,
            @RequestParam(defaultValue = "") String usage,
            @RequestParam(defaultValue = "false") boolean unused,
            @RequestParam(name = "exclude_avatars", defaultValue = "false") boolean excludeAvatars,
            @RequestParam(name = "asset_id", required = false) java.util.UUID assetId) {
        return ApiResponse.success(media.list(page, pageSize, query, order, sort, type, usage, unused, excludeAvatars, assetId));
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

    @PostMapping("/{assetId}/optimize-avatar")
    public ApiResponse<AvatarMediaOptimizationService.OptimizationReport> optimizeAvatar(@PathVariable java.util.UUID assetId) {
        return ApiResponse.success(avatarOptimization.optimize(assetId));
    }

    @PostMapping("/{assetId}/optimize-image")
    public ApiResponse<MediaAssetImageOptimizationService.OptimizationReport> optimizeImage(@PathVariable java.util.UUID assetId) {
        return ApiResponse.success(imageOptimization.optimize(assetId));
    }

}
