package com.myshop.service.impl;

import com.myshop.constant.ErrorCode;
import com.myshop.entity.ProductCategory;
import com.myshop.exception.BusinessException;
import com.myshop.model.request.CategoryUpsertRequest;
import com.myshop.model.response.CategoryResponse;
import com.myshop.repository.ProductCategoryRepository;
import com.myshop.repository.ProductRepository;
import com.myshop.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    private static final int ACTIVE = 0;

    private final ProductCategoryRepository categoryRepository;
    private final ProductRepository productRepository;

    @Override
    @Transactional(readOnly = true)
    public List<CategoryResponse> listCategories() {
        List<ProductCategory> activeCategories = categoryRepository
                .findByIsDeletedOrderBySortOrderAscIdAsc(ACTIVE);
        Map<UUID, List<ProductCategory>> childrenByParent = activeCategories.stream()
                .filter(category -> category.getParentId() != null)
                .collect(Collectors.groupingBy(ProductCategory::getParentId, LinkedHashMap::new,
                        Collectors.toList()));

        return activeCategories.stream()
                .filter(category -> category.getParentId() == null)
                .sorted(categoryOrder())
                .map(root -> new CategoryResponse(root.getId(), root.getName(), root.getSortOrder(), null,
                        childrenByParent.getOrDefault(root.getId(), List.of()).stream()
                                .sorted(categoryOrder())
                                .map(child -> new CategoryResponse(child.getId(), child.getName(),
                                        child.getSortOrder(), child.getParentId(), List.of()))
                                .toList()))
                .toList();
    }

    @Override
    @Transactional
    public void createCategory(CategoryUpsertRequest request) {
        if (request == null) throw new BusinessException(ErrorCode.BAD_REQUEST, "分类参数不能为空");
        String name = normalizeName(request.name());
        validateParent(request.parentId(), null);
        ensureSiblingNameAvailable(name, request.parentId(), null);

        ProductCategory category = new ProductCategory();
        category.setName(name);
        category.setParentId(request.parentId());
        category.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        categoryRepository.save(category);
    }

    @Override
    @Transactional
    public void updateCategory(UUID id, CategoryUpsertRequest request) {
        if (request == null) throw new BusinessException(ErrorCode.BAD_REQUEST, "分类参数不能为空");
        ProductCategory category = findActive(id);
        String name = request.name() == null ? normalizeName(category.getName()) : normalizeName(request.name());
        UUID targetParentId = request.parentId();

        if (id.equals(targetParentId)) {
            throw new BusinessException(ErrorCode.CATEGORY_PARENT_INVALID, "分类不能将自己设为上级分类");
        }
        if (category.getParentId() == null && targetParentId != null
                && categoryRepository.countByParentIdAndIsDeleted(id, ACTIVE) > 0) {
            throw new BusinessException(ErrorCode.CATEGORY_HAS_CHILDREN, "拥有二级分类的一级分类不能移动到其他分类下");
        }
        validateParent(targetParentId, id);
        ensureSiblingNameAvailable(name, targetParentId, id);

        category.setName(name);
        category.setParentId(targetParentId);
        if (request.sortOrder() != null) category.setSortOrder(request.sortOrder());
        categoryRepository.save(category);
    }

    @Override
    @Transactional
    public void deleteCategory(UUID id) {
        ProductCategory category = findActive(id);
        if (categoryRepository.countByParentIdAndIsDeleted(id, ACTIVE) > 0) {
            throw new BusinessException(ErrorCode.CATEGORY_HAS_CHILDREN, "该一级分类下存在二级分类，无法删除");
        }
        if (productRepository.countByCategoryIdAndIsDeleted(id, ACTIVE) > 0) {
            throw new BusinessException(ErrorCode.CATEGORY_HAS_PRODUCTS, "该分类下存在商品，无法删除");
        }
        category.setIsDeleted(1);
        categoryRepository.save(category);
    }

    private ProductCategory findActive(UUID id) {
        return categoryRepository.findById(id)
                .filter(category -> category.getIsDeleted() == ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "分类不存在"));
    }

    private void validateParent(UUID parentId, UUID categoryId) {
        if (parentId == null) return;
        if (parentId.equals(categoryId)) {
            throw new BusinessException(ErrorCode.CATEGORY_PARENT_INVALID, "分类不能将自己设为上级分类");
        }
        ProductCategory parent = categoryRepository.findById(parentId)
                .filter(category -> category.getIsDeleted() == ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.CATEGORY_PARENT_INVALID,
                        "上级分类不存在或已删除"));
        if (parent.getParentId() != null) {
            throw new BusinessException(ErrorCode.CATEGORY_PARENT_INVALID, "最多只支持两级分类，二级分类不能作为上级分类");
        }
    }

    private void ensureSiblingNameAvailable(String name, UUID parentId, UUID categoryId) {
        boolean exists = parentId == null
                ? categoryId == null
                ? categoryRepository.existsByNameAndParentIdIsNullAndIsDeleted(name, ACTIVE)
                : categoryRepository.existsByNameAndParentIdIsNullAndIsDeletedAndIdNot(name, ACTIVE, categoryId)
                : categoryId == null
                ? categoryRepository.existsByNameAndParentIdAndIsDeleted(name, parentId, ACTIVE)
                : categoryRepository.existsByNameAndParentIdAndIsDeletedAndIdNot(name, parentId, ACTIVE, categoryId);
        if (exists) throw new BusinessException(ErrorCode.CATEGORY_NAME_EXISTS, "同级分类名称已存在");
    }

    private String normalizeName(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) throw new BusinessException(ErrorCode.BAD_REQUEST, "分类名称不能为空");
        if (normalized.length() > 255) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分类名称不能超过 255 个字符");
        }
        return normalized;
    }

    private Comparator<ProductCategory> categoryOrder() {
        return Comparator.comparingInt(ProductCategory::getSortOrder)
                .thenComparing(ProductCategory::getId, Comparator.nullsLast(Comparator.naturalOrder()));
    }
}
