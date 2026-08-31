package com.myshop.controller;

import com.myshop.common.ApiResponse;
import com.myshop.context.RequestContext;
import com.myshop.service.PublicProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletResponse;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class ProductController {

    private final PublicProductService productService;

    @GetMapping("/products")
    public ApiResponse<?> listProducts(
            @RequestParam(value = "category_id", required = false) UUID categoryId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "20") int pageSize,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store");
        return ApiResponse.success(productService.listProducts(categoryId, keyword, page, pageSize,
                RequestContext.getUserId()));
    }

    @GetMapping("/products/{id}")
    public ApiResponse<?> getProduct(@PathVariable UUID id, HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store");
        return ApiResponse.success(productService.getProduct(id, RequestContext.getUserId()));
    }
}
