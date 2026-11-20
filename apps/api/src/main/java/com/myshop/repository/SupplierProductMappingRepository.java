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
    @Query("select m.accountId, m.remoteGoodsId from SupplierProductMapping m, SupplierAccount a " +
           "where m.accountId = a.id and a.enabled = true group by m.accountId, m.remoteGoodsId " +
           "order by min(m.lastAttemptAt) asc")
    List<Object[]> findDue(Pageable pageable);
}
