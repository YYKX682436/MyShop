package com.myshop.repository;

import com.myshop.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;
import java.util.List;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    // 首页商品列表 — 无搜索词
    @Query("SELECT p FROM Product p WHERE p.isDeleted = 0 AND p.enabled = true " +
            "AND (:categoryId IS NULL OR p.categoryId = :categoryId)")
    Page<Product> findPublicProducts(@Param("categoryId") UUID categoryId,
                                     Pageable pageable);

    // 首页商品列表 — 带搜索词（keyword 保证非 null）
    @Query("SELECT p FROM Product p WHERE p.isDeleted = 0 AND p.enabled = true " +
            "AND (:categoryId IS NULL OR p.categoryId = :categoryId) " +
            "AND LOWER(p.title) LIKE :keywordPattern")
    Page<Product> findPublicProductsByKeyword(@Param("categoryId") UUID categoryId,
                                              @Param("keywordPattern") String keywordPattern,
                                              Pageable pageable);

    // 管理后台商品列表 — 无搜索词
    @Query("SELECT p FROM Product p WHERE p.isDeleted = 0 " +
            "AND (:categoryId IS NULL OR p.categoryId = :categoryId) " +
            "AND (:isEnabled IS NULL OR p.enabled = :isEnabled)")
    Page<Product> findAdminProducts(@Param("categoryId") UUID categoryId,
                                    @Param("isEnabled") Boolean isEnabled,
                                    Pageable pageable);

    // 管理后台商品列表 — 带搜索词（keyword 保证非 null）
    @Query("SELECT p FROM Product p WHERE p.isDeleted = 0 " +
            "AND (:categoryId IS NULL OR p.categoryId = :categoryId) " +
            "AND LOWER(p.title) LIKE :keywordPattern " +
            "AND (:isEnabled IS NULL OR p.enabled = :isEnabled)")
    Page<Product> findAdminProductsByKeyword(@Param("categoryId") UUID categoryId,
                                             @Param("keywordPattern") String keywordPattern,
                                             @Param("isEnabled") Boolean isEnabled,
                                             Pageable pageable);

    long countByCategoryIdAndIsDeleted(UUID categoryId, int isDeleted);

    // 一次性迁移：将已有规格的商品自动设置 spec_enabled=true
    @Modifying
    @Query("UPDATE Product p SET p.specEnabled = true WHERE p.specEnabled = false " +
            "AND p.isDeleted = 0 AND EXISTS (" +
            "SELECT 1 FROM ProductSpec s WHERE s.productId = p.id AND s.isDeleted = 0)")
    int migrateSpecEnabled();

    /** One grouped read supplies every active product's available stock. */
    @Query(value = """
            SELECT p.id,
                   p.title,
                   p.low_stock_threshold,
                   COUNT(ck.id) FILTER (WHERE ck.status = 'AVAILABLE') AS available_stock
            FROM products p
            LEFT JOIN card_keys ck ON ck.product_id = p.id
            WHERE p.is_deleted = 0
              AND p.is_enabled = true
            GROUP BY p.id, p.title, p.low_stock_threshold
            ORDER BY COUNT(ck.id) FILTER (WHERE ck.status = 'AVAILABLE') ASC,
                     p.title ASC
            """, nativeQuery = true)
    List<Object[]> findActiveProductStockSummaries();
}
