package com.myshop.service;

import com.myshop.common.PageResult;
import com.myshop.model.request.AdminPricingPreviewRequest;
import com.myshop.model.request.AdminProductUpsertRequest;
import com.myshop.model.request.AdminProductStatusRequest;
import com.myshop.model.request.AdminProductCategoryUpdateRequest;
import com.myshop.model.request.AdminProductPricesUpdateRequest;
import com.myshop.model.request.AdminProductTitleUpdateRequest;
import com.myshop.model.response.AdminPricingPreviewResponse;
import com.myshop.model.response.AdminProductResponse;
import com.myshop.model.response.AdminProductSpecResponse;

import java.util.UUID;

public interface AdminProductService {
    PageResult<AdminProductResponse> listProducts(UUID categoryId, String keyword, Boolean isEnabled,
                                                  String sortBy, String sortDirection, int page, int pageSize);

    AdminProductResponse getProduct(UUID id);

    AdminProductResponse createProduct(AdminProductUpsertRequest request);

    AdminProductResponse updateProduct(UUID id, AdminProductUpsertRequest request);

    AdminProductResponse setStatus(UUID id, AdminProductStatusRequest request);

    AdminProductResponse updateTitle(UUID id, AdminProductTitleUpdateRequest request);

    AdminProductResponse updateCategory(UUID id, AdminProductCategoryUpdateRequest request);

    AdminProductResponse updatePrices(UUID id, AdminProductPricesUpdateRequest request);

    void updateSortWeight(UUID id, Integer sortWeight);

    void deleteProduct(UUID id);

    java.util.List<AdminProductSpecResponse> listSpecs(UUID productId);

    AdminPricingPreviewResponse previewPricing(AdminPricingPreviewRequest request);
}
