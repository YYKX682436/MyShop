package com.myshop.controller;

import com.myshop.annotation.LogOperation;
import com.myshop.common.ApiResponse;
import com.myshop.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/admin/categories")
@RequiredArgsConstructor
public class AdminCategoryController {

    private final CategoryService categoryService;

    @GetMapping
    public ApiResponse<?> listCategories() {
        return ApiResponse.success(categoryService.listCategories());
    }

    @LogOperation(action = "category.create", targetType = "CATEGORY", detail = "'创建分类'")
    @PostMapping
    public ApiResponse<Void> createCategory(@RequestBody Map<String, Object> request) {
        categoryService.createCategory(request);
        return ApiResponse.success();
    }

    @LogOperation(action = "category.update", targetType = "CATEGORY", targetId = "#id", detail = "'修改分类'")
    @PutMapping("/{id}")
    public ApiResponse<Void> updateCategory(@PathVariable UUID id, @RequestBody Map<String, Object> request) {
        categoryService.updateCategory(id, request);
        return ApiResponse.success();
    }

    @LogOperation(action = "category.delete", targetType = "CATEGORY", targetId = "#id", detail = "'删除分类'")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteCategory(@PathVariable UUID id) {
        categoryService.deleteCategory(id);
        return ApiResponse.success();
    }
}
