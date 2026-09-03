package com.myshop.controller;

import com.myshop.annotation.LogOperation;
import com.myshop.common.ApiResponse;
import com.myshop.model.request.AdminPricingPreviewRequest;
import com.myshop.model.request.AdminProductUpsertRequest;
import com.myshop.model.request.AdminProductStatusRequest;
import com.myshop.model.request.AdminProductSortWeightRequest;
import com.myshop.model.request.AdminProductCategoryUpdateRequest;
import com.myshop.model.request.AdminProductPricesUpdateRequest;
import com.myshop.model.request.AdminProductTitleUpdateRequest;
import com.myshop.service.AdminProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/admin/products")
@RequiredArgsConstructor
public class AdminProductController {

    private final AdminProductService productService;

    @GetMapping
    public ApiResponse<?> listProducts(
            @RequestParam(value = "category_id", required = false) UUID categoryId,
            @RequestParam(required = false) String keyword,
            @RequestParam(value = "is_enabled", required = false) Boolean isEnabled,
            @RequestParam(value = "sort_by", required = false) String sortBy,
            @RequestParam(value = "sort_direction", required = false) String sortDirection,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "20") int pageSize) {
        return ApiResponse.success(productService.listProducts(categoryId, keyword, isEnabled,
                sortBy, sortDirection, page, pageSize));
    }

    @LogOperation(action = "product.create", targetType = "PRODUCT", detail = "'创建商品'")
    @PostMapping
    public ApiResponse<?> createProduct(@RequestBody AdminProductUpsertRequest request) {
        return ApiResponse.success(productService.createProduct(request));
    }

    @GetMapping("/{id}")
    public ApiResponse<?> getProduct(@PathVariable UUID id) {
        return ApiResponse.success(productService.getProduct(id));
    }

    @LogOperation(action = "product.update", targetType = "PRODUCT", targetId = "#id", detail = "'修改商品'")
    @PutMapping("/{id}")
    public ApiResponse<?> updateProduct(@PathVariable UUID id, @RequestBody AdminProductUpsertRequest request) {
        return ApiResponse.success(productService.updateProduct(id, request));
    }

    @LogOperation(action = "product.status", targetType = "PRODUCT", targetId = "#id", detail = "'修改商品状态'")
    @PostMapping("/{id}/status")
    public ApiResponse<?> setStatus(@PathVariable UUID id, @RequestBody AdminProductStatusRequest request) {
        return ApiResponse.success(productService.setStatus(id, request));
    }

    @LogOperation(action = "product.update", targetType = "PRODUCT", targetId = "#id", detail = "'快捷修改商品名称'")
    @PutMapping("/{id}/title")
    public ApiResponse<?> updateTitle(@PathVariable UUID id,
                                      @RequestBody AdminProductTitleUpdateRequest request) {
        return ApiResponse.success(productService.updateTitle(id, request));
    }

    @LogOperation(action = "product.update", targetType = "PRODUCT", targetId = "#id", detail = "'快捷修改商品分类'")
    @PutMapping("/{id}/category")
    public ApiResponse<?> updateCategory(@PathVariable UUID id,
                                         @RequestBody AdminProductCategoryUpdateRequest request) {
        return ApiResponse.success(productService.updateCategory(id, request));
    }

    @LogOperation(action = "product.update", targetType = "PRODUCT", targetId = "#id", detail = "'快捷修改商品价格'")
    @PutMapping("/{id}/prices")
    public ApiResponse<?> updatePrices(@PathVariable UUID id,
                                       @RequestBody AdminProductPricesUpdateRequest request) {
        return ApiResponse.success(productService.updatePrices(id, request));
    }

    @LogOperation(action = "product.sort_weight", targetType = "PRODUCT", targetId = "#id", detail = "'修改商品排序权重'")
    @PatchMapping("/{id}/sort-weight")
    public ApiResponse<Void> updateSortWeight(@PathVariable UUID id,
                                               @RequestBody AdminProductSortWeightRequest request) {
        productService.updateSortWeight(id, request == null ? null : request.sortWeight());
        return ApiResponse.success();
    }

    @LogOperation(action = "product.delete", targetType = "PRODUCT", targetId = "#id", detail = "'删除商品'")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteProduct(@PathVariable UUID id) {
        productService.deleteProduct(id);
        return ApiResponse.success();
    }

    @GetMapping("/{id}/specs")
    public ApiResponse<?> listSpecs(@PathVariable UUID id) {
        return ApiResponse.success(productService.listSpecs(id));
    }

    @PostMapping("/pricing-preview")
    public ApiResponse<?> previewPricing(@RequestBody AdminPricingPreviewRequest request) {
        return ApiResponse.success(productService.previewPricing(request));
    }
}
