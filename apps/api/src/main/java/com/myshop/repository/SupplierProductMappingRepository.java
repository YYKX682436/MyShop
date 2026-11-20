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
    @Query("select m.accountId, m.remoteGoodsId from SupplierProductMapping m, SupplierAccount a " +
           "where m.accountId = a.id and a.enabled = true group by m.accountId, m.remoteGoodsId " +
           "order by min(m.lastAttemptAt) asc")
    List<Object[]> findDue(Pageable pageable);
}
