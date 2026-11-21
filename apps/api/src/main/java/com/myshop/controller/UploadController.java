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
        return ApiResponse.success(Map.of("url", result.url(), "original_size", result.originalSize(),
                "stored_size", result.storedSize(), "reason", result.reason()));
    }
}
