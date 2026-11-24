package com.myshop.controller;

import com.myshop.common.ApiResponse;
import com.myshop.service.TusVideoService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import me.desair.tus.server.exception.TusException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/admin/media")
public class AdminVideoUploadController {
    private final TusVideoService videos;

    public AdminVideoUploadController(TusVideoService videos) { this.videos = videos; }

    public record CreateVideo(String name, long size) { }

    @PostMapping("/video-sessions")
    public ApiResponse<TusVideoService.Created> create(@RequestBody CreateVideo request) {
        return ApiResponse.success(videos.create(request.name(), request.size()));
    }

    @GetMapping("/video-sessions/{id}")
    public ApiResponse<TusVideoService.Status> status(@PathVariable UUID id) {
        return ApiResponse.success(videos.status(id));
    }

    @GetMapping("/video-sessions")
    public ApiResponse<TusVideoService.Status> statusByUpload(@RequestParam("upload_uri") String uri) {
        return ApiResponse.success(videos.statusByUpload(uri));
    }

    @PostMapping("/video-sessions/{id}/retry")
    public ApiResponse<?> retry(@PathVariable UUID id) throws IOException, TusException {
        videos.retry(id);
        return ApiResponse.success();
    }

    @PostMapping("/video-sessions/{id}/cleanup")
    public ApiResponse<?> cleanup(@PathVariable UUID id) throws IOException, TusException {
        videos.retryReadyCleanup(id);
        return ApiResponse.success();
    }

    @DeleteMapping("/video-sessions/{id}")
    public ApiResponse<?> cancel(@PathVariable UUID id) throws IOException, TusException {
        videos.cancel(id);
        return ApiResponse.success();
    }

    @RequestMapping(value = {"/tus", "/tus/{id}"}, method = {
            RequestMethod.OPTIONS, RequestMethod.POST, RequestMethod.HEAD, RequestMethod.PATCH, RequestMethod.DELETE})
    public void tus(HttpServletRequest request, HttpServletResponse response) throws IOException, TusException {
        videos.processTus(request, response);
    }
}
