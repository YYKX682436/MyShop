package com.myshop.controller;

import com.myshop.common.ApiResponse;
import com.myshop.model.request.UserLevelAssignmentRequest;
import com.myshop.model.request.UserLevelMigrationRequest;
import com.myshop.model.request.UserLevelStatusRequest;
import com.myshop.model.request.UserLevelUpsertRequest;
import com.myshop.model.response.UserLevelResponse;
import com.myshop.service.UserLevelService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/admin/user-levels")
@RequiredArgsConstructor
public class AdminUserLevelController {

    private final UserLevelService userLevelService;

    @GetMapping
    public ApiResponse<List<UserLevelResponse>> list() {
        return ApiResponse.success(userLevelService.list());
    }

    @PostMapping
    public ApiResponse<UserLevelResponse> create(@RequestBody UserLevelUpsertRequest request) {
        return ApiResponse.success(userLevelService.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<UserLevelResponse> update(@PathVariable UUID id,
                                                  @RequestBody UserLevelUpsertRequest request) {
        return ApiResponse.success(userLevelService.update(id, request));
    }

    @PostMapping("/{id}/status")
    public ApiResponse<UserLevelResponse> setStatus(@PathVariable UUID id,
                                                     @RequestBody UserLevelStatusRequest request) {
        return ApiResponse.success(userLevelService.setStatus(id, request));
    }

    @PostMapping("/{id}/default")
    public ApiResponse<UserLevelResponse> setDefault(@PathVariable UUID id) {
        return ApiResponse.success(userLevelService.setDefault(id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        userLevelService.delete(id);
        return ApiResponse.success();
    }

    @PostMapping("/{id}/migrate")
    public ApiResponse<Integer> migrate(@PathVariable UUID id,
                                        @RequestBody UserLevelMigrationRequest request) {
        return ApiResponse.success(userLevelService.migrate(id, request));
    }

    /** Kept under the user resource for an atomic single-user level change. */
    @PostMapping("/users/{userId}/assign")
    public ApiResponse<UserLevelResponse> assignUser(@PathVariable UUID userId,
                                                      @RequestBody UserLevelAssignmentRequest request) {
        return ApiResponse.success(userLevelService.assignUser(userId, request));
    }
}
