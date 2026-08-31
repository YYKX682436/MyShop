package com.myshop.repository;

import com.myshop.entity.ProductSpec;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProductSpecRepository extends JpaRepository<ProductSpec, UUID> {

    List<ProductSpec> findByProductIdAndIsDeletedOrderBySortOrderAsc(UUID productId, int isDeleted);

    List<ProductSpec> findByProductIdInAndIsDeletedAndVisibleOrderByProductIdAscSortOrderAsc(
            List<UUID> productIds, int isDeleted, boolean visible);

    List<ProductSpec> findByProductIdInAndIsDeletedOrderByProductIdAscSortOrderAsc(
            List<UUID> productIds, int isDeleted);

    boolean existsByProductIdAndIsDeletedAndVisible(UUID productId, int isDeleted, boolean visible);

    boolean existsByProductIdAndNameAndIsDeleted(UUID productId, String name, int isDeleted);
}
