package com.myshop.service;

import com.myshop.model.request.CategoryUpsertRequest;
import com.myshop.model.response.CategoryResponse;

import java.util.List;
import java.util.UUID;

public interface CategoryService {

    List<CategoryResponse> listCategories();

    void createCategory(CategoryUpsertRequest request);

    void updateCategory(UUID id, CategoryUpsertRequest request);

    void deleteCategory(UUID id);
}
