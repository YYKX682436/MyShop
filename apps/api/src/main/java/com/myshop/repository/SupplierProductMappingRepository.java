package com.myshop.repository;

import com.myshop.entity.SupplierProductMapping;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.UUID;

public interface SupplierProductMappingRepository extends JpaRepository<SupplierProductMapping, UUID> {
    List<SupplierProductMapping> findByAccountIdAndRemoteGoodsIdOrderByRemoteSku(UUID accountId, String remoteGoodsId);
    List<SupplierProductMapping> findByAccountIdOrderByRemoteGoodsIdAscRemoteSkuAsc(UUID accountId);
    boolean existsByProductId(UUID productId);
    @Query("select m from SupplierProductMapping m where m.productId=:productId and " +
           "((:specId is null and m.specId is null) or m.specId=:specId)")
    java.util.Optional<SupplierProductMapping> findLocal(@Param("productId") UUID productId, @Param("specId") UUID specId);
    @Query("select m.accountId from SupplierProductMapping m where m.productId=:productId and " +
           "((:specId is null and m.specId is null) or m.specId=:specId)")
    java.util.Optional<UUID> findLocalAccount(@Param("productId") UUID productId, @Param("specId") UUID specId);
    List<SupplierProductMapping> findByProductIdIn(List<UUID> productIds);
    List<SupplierProductMapping> findByProductId(UUID productId);
    @Query("select distinct m.accountId from SupplierProductMapping m where m.productId=:productId")
    List<UUID> findAccountsByProductId(@Param("productId") UUID productId);
    @Query("select m.accountId, m.productId from SupplierProductMapping m where m.id=:id")
    List<Object[]> findCoordinates(@Param("id") UUID id);
    @Query(value="""
        SELECT m.account_id, m.remote_goods_id
        FROM supplier_product_mappings m
        JOIN supplier_accounts a ON a.id=m.account_id
        JOIN products p ON p.id=m.product_id
        WHERE a.enabled=true AND p.is_deleted=0 AND p.stock_mode='SUPPLIER'
          AND ((m.spec_id IS NULL AND p.spec_enabled=false)
            OR (m.spec_id IS NOT NULL AND p.spec_enabled=true AND EXISTS (
                SELECT 1 FROM product_specs s
                WHERE s.id=m.spec_id AND s.product_id=p.id AND s.is_deleted=0)))
        GROUP BY m.account_id,m.remote_goods_id
        HAVING MIN(m.last_attempt_at)<CURRENT_TIMESTAMP-INTERVAL '1 minute'
          AND (BOOL_OR(m.sync_error IN ('CONNECTION','HTTP','BUSINESS','INTERNAL'))
            OR (BOOL_AND(m.sync_error IS NULL)
                AND MIN(m.last_synced_at)<CURRENT_TIMESTAMP-INTERVAL '25 minutes')
            OR (BOOL_OR(m.sync_error IS NOT NULL)
                AND MIN(m.last_synced_at)<CURRENT_TIMESTAMP-INTERVAL '25 minutes'
                AND MIN(m.last_attempt_at)<CURRENT_TIMESTAMP-INTERVAL '25 minutes'))
        ORDER BY MIN(m.last_attempt_at),m.account_id,m.remote_goods_id
        """,nativeQuery=true)
    List<Object[]> findDue(Pageable pageable);
}
