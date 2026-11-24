package com.myshop.controller;

import com.myshop.common.ApiResponse;
import com.myshop.service.ImageUploadService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/upload")
public class UploadController {

    private final ImageUploadService imageUploadService;

    public UploadController(ImageUploadService imageUploadService) {
        this.imageUploadService = imageUploadService;
    }

    @PostMapping("/image")
    public ApiResponse<?> uploadImage(@RequestParam("file") MultipartFile file) {
        var result = imageUploadService.uploadAdminImage(file);
        var data = new java.util.LinkedHashMap<String, Object>();
        data.put("url", result.url()); data.put("original_size", result.originalSize());
        data.put("stored_size", result.storedSize()); data.put("reason", result.reason());
        data.put("asset_id", result.assetId()); data.put("reused", result.reused());
        data.put("thumbnail_url", result.thumbnailUrl()); data.put("clear_url", result.clearUrl());
        return ApiResponse.success(data);
    }
}
