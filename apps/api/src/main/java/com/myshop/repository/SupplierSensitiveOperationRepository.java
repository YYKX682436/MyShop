package com.myshop.repository;

import com.myshop.entity.SupplierSensitiveOperation;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.*;

public interface SupplierSensitiveOperationRepository extends JpaRepository<SupplierSensitiveOperation,UUID> {
    Optional<SupplierSensitiveOperation> findByAccountIdAndIdempotencyKey(UUID accountId,UUID key);
    @Query("select o.accountId from SupplierSensitiveOperation o where o.id=:id")
    Optional<UUID> findAccountId(@Param("id") UUID id);
    List<SupplierSensitiveOperation> findByAccountIdAndKindOrderByCreatedAtDesc(UUID accountId,String kind,Pageable page);
    @Query("select count(o)>0 from SupplierSensitiveOperation o where o.accountId=:account and o.kind=:kind and "
            + "(o.status in ('INTENT','SUBMITTING') or (o.status='UNKNOWN' and (o.reviewOutcome is null or o.reviewOutcome<>'MANUAL_HANDLING')))")
    boolean hasUnresolved(@Param("account") UUID accountId,@Param("kind") String kind);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from SupplierSensitiveOperation o where o.id=:id")
    Optional<SupplierSensitiveOperation> findForUpdate(@Param("id") UUID id);
    @Query("select o.id from SupplierSensitiveOperation o where o.status='SUBMITTING' and o.submittedAt<:before order by o.submittedAt")
    List<UUID> stale(@Param("before") LocalDateTime before,Pageable page);
}
