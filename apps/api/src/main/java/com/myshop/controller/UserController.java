package com.myshop.controller;

import com.myshop.common.ApiResponse;
import com.myshop.common.PageResult;
import com.myshop.context.RequestContext;
import com.myshop.model.request.ChangePasswordRequest;
import com.myshop.model.response.UserProfileResponse;
import com.myshop.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/profile")
    public ApiResponse<UserProfileResponse> getProfile() {
        return ApiResponse.success(userService.getProfile(RequestContext.getUserId()));
    }

    @PutMapping("/password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(RequestContext.getUserId(), request);
        return ApiResponse.success();
    }

    @GetMapping("/orders")
    public ApiResponse<PageResult<?>> getOrders(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "20") int pageSize,
            @RequestParam(required = false) String status) {
        return ApiResponse.success(userService.getOrders(RequestContext.getUserId(), status, page, pageSize));
    }

}
