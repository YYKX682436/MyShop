package com.myshop.controller;

import com.myshop.common.ApiResponse;
import com.myshop.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping("/categories")
    public ApiResponse<?> listCategories() {
        return ApiResponse.success(categoryService.listCategories());
    }
}
