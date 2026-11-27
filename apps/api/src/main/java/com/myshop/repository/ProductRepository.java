package com.myshop.repository;

import com.myshop.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.UUID;
import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    // 首页商品列表 — 无搜索词
    @Query("SELECT p FROM Product p WHERE p.isDeleted = 0 AND p.enabled = true " +
            "AND (:categoryId IS NULL OR p.categoryId = :categoryId " +
            "OR p.categoryId IN (SELECT child.id FROM ProductCategory child " +
            "WHERE child.parentId = :categoryId AND child.isDeleted = 0))")
    Page<Product> findPublicProducts(@Param("categoryId") UUID categoryId,
                                     Pageable pageable);

    // 首页商品列表 — 带搜索词（keyword 保证非 null）
    @Query("SELECT p FROM Product p WHERE p.isDeleted = 0 AND p.enabled = true " +
            "AND (:categoryId IS NULL OR p.categoryId = :categoryId " +
            "OR p.categoryId IN (SELECT child.id FROM ProductCategory child " +
            "WHERE child.parentId = :categoryId AND child.isDeleted = 0)) " +
            "AND (LOWER(p.title) LIKE :keywordPattern OR (:productNo IS NOT NULL AND p.productNo = :productNo))")
    Page<Product> findPublicProductsByKeyword(@Param("categoryId") UUID categoryId,
                                              @Param("keywordPattern") String keywordPattern,
                                              @Param("productNo") Long productNo,
                                              Pageable pageable);

    // 管理后台商品列表 — 无搜索词
    @Query("SELECT p FROM Product p WHERE p.isDeleted = 0 " +
            "AND (:categoryId IS NULL OR p.categoryId = :categoryId " +
            "OR p.categoryId IN (SELECT child.id FROM ProductCategory child " +
            "WHERE child.parentId = :categoryId AND child.isDeleted = 0)) " +
            "AND (:isEnabled IS NULL OR p.enabled = :isEnabled)")
    Page<Product> findAdminProducts(@Param("categoryId") UUID categoryId,
                                    @Param("isEnabled") Boolean isEnabled,
                                    Pageable pageable);

    // 管理后台商品列表 — 带搜索词（keyword 保证非 null）
    @Query("SELECT p FROM Product p WHERE p.isDeleted = 0 " +
            "AND (:categoryId IS NULL OR p.categoryId = :categoryId " +
            "OR p.categoryId IN (SELECT child.id FROM ProductCategory child " +
            "WHERE child.parentId = :categoryId AND child.isDeleted = 0)) " +
            "AND (LOWER(p.title) LIKE :keywordPattern OR (:productNo IS NOT NULL AND p.productNo = :productNo)) " +
            "AND (:isEnabled IS NULL OR p.enabled = :isEnabled)")
    Page<Product> findAdminProductsByKeyword(@Param("categoryId") UUID categoryId,
                                             @Param("keywordPattern") String keywordPattern,
                                             @Param("productNo") Long productNo,
                                             @Param("isEnabled") Boolean isEnabled,
                                             Pageable pageable);

    Optional<Product> findByProductNo(Long productNo);

    /** PostgreSQL sequence-backed business number allocation. */
    @Query(value = "SELECT nextval('product_no_sequence')", nativeQuery = true)
    long nextProductNoValue();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") UUID id);

    @Modifying
    @Query(value = "UPDATE products SET card_content_column_count = :columnCount WHERE id = :id",
            nativeQuery = true)
    int setCardContentColumnCount(@Param("id") UUID id, @Param("columnCount") int columnCount);

    long countByCategoryIdAndIsDeleted(UUID categoryId, int isDeleted);

    /** Active products whose inventory is checked through ProductInventoryBatchService. */
    @Query("SELECT p FROM Product p WHERE p.isDeleted = 0 AND p.enabled = true ORDER BY p.title ASC, p.id ASC")
    List<Product> findActiveProductsForDashboard();
}
