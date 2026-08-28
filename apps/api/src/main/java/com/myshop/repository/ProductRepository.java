package com.myshop.repository;

import com.myshop.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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

    /** One grouped read supplies every active product's mode-specific available stock. */
    @Query(value = """
            SELECT p.id,
                   p.title,
                   p.low_stock_threshold,
                   CASE
                       WHEN p.stock_mode = 'UNLIMITED' THEN NULL
                       WHEN p.stock_mode = 'MANUAL' THEN COALESCE(ms.available_stock, 0)
                       ELSE COALESCE(cs.available_stock, 0)
                   END AS available_stock
            FROM products p
            LEFT JOIN (
                SELECT product_id,
                       COUNT(*) FILTER (WHERE status = 'AVAILABLE') AS available_stock
                FROM card_keys
                GROUP BY product_id
            ) cs ON cs.product_id = p.id
            LEFT JOIN (
                SELECT product_id, SUM(available_quantity) AS available_stock
                FROM manual_stocks
                GROUP BY product_id
            ) ms ON ms.product_id = p.id
            WHERE p.is_deleted = 0
              AND p.is_enabled = true
            ORDER BY CASE
                         WHEN p.stock_mode = 'UNLIMITED' THEN 1
                         WHEN p.stock_mode = 'MANUAL' THEN COALESCE(ms.available_stock, 0)
                         ELSE COALESCE(cs.available_stock, 0)
                     END ASC,
                     p.title ASC
            """, nativeQuery = true)
    List<Object[]> findActiveProductStockSummaries();
}
