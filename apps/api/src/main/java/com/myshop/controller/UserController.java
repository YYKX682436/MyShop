package com.myshop.controller;

import com.myshop.common.ApiResponse;
import com.myshop.common.PageResult;
import com.myshop.context.RequestContext;
import com.myshop.model.request.ChangePasswordRequest;
import com.myshop.model.response.UserProfileResponse;
import com.myshop.service.AuthCookieService;
import com.myshop.service.UserAvatarService;
import com.myshop.service.UserService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final UserAvatarService userAvatarService;
    private final AuthCookieService authCookieService;

    @GetMapping("/profile")
    public ApiResponse<UserProfileResponse> getProfile() {
        return ApiResponse.success(userService.getProfile(RequestContext.getUserId()));
    }

    @PutMapping("/password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                            HttpServletResponse response) {
        userService.changePassword(RequestContext.getUserId(), request);
        authCookieService.clearAuthCookies(response);
        return ApiResponse.success();
    }

    @PostMapping("/avatar")
    public ApiResponse<UserProfileResponse> uploadAvatar(@RequestParam("file") MultipartFile file) {
        return ApiResponse.success(userAvatarService.uploadAvatar(RequestContext.getUserId(), file));
    }

    @DeleteMapping("/avatar")
    public ApiResponse<UserProfileResponse> deleteAvatar() {
        return ApiResponse.success(userAvatarService.deleteAvatar(RequestContext.getUserId()));
    }

    @GetMapping("/orders")
    public ApiResponse<PageResult<?>> getOrders(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "20") int pageSize,
            @RequestParam(required = false) String status) {
        return ApiResponse.success(userService.getOrders(RequestContext.getUserId(), status, page, pageSize));
    }

}
