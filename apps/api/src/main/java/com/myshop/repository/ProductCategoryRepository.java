package com.myshop.repository;

import com.myshop.entity.ProductCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProductCategoryRepository extends JpaRepository<ProductCategory, UUID> {

    List<ProductCategory> findByIsDeletedOrderBySortOrderAscIdAsc(int isDeleted);

    boolean existsByNameAndParentIdIsNullAndIsDeleted(String name, int isDeleted);

    boolean existsByNameAndParentIdAndIsDeleted(String name, UUID parentId, int isDeleted);

    boolean existsByNameAndParentIdIsNullAndIsDeletedAndIdNot(String name, int isDeleted, UUID id);

    boolean existsByNameAndParentIdAndIsDeletedAndIdNot(String name, UUID parentId, int isDeleted, UUID id);

    long countByParentIdAndIsDeleted(UUID parentId, int isDeleted);
}
