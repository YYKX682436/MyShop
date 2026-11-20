package com.myshop.repository;

import com.myshop.entity.SupplierAftercareOperation;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;
import java.time.LocalDateTime;

public interface SupplierAftercareOperationRepository extends JpaRepository<SupplierAftercareOperation,UUID> {
    List<SupplierAftercareOperation> findByPurchaseIdOrderByCreatedAtAsc(UUID purchaseId);
    Optional<SupplierAftercareOperation> findByPurchaseIdAndKind(UUID purchaseId,String kind);
    Optional<SupplierAftercareOperation> findByCardIdAndKind(UUID cardId,String kind);
    Optional<SupplierAftercareOperation> findByAccountIdAndIdempotencyKey(UUID accountId,UUID key);
    Optional<SupplierAftercareOperation> findByAccountIdAndSupplierReqIdAndKind(UUID accountId,Long reqId,String kind);
    @Query("select o.purchaseId from SupplierAftercareOperation o where o.id=:id")
    Optional<UUID> findPurchaseId(@Param("id") UUID id);
    @Query("select o.id from SupplierAftercareOperation o where o.accountId=:account and o.supplierReqId=:req and o.kind='COMPLAINT'")
    Optional<UUID> findComplaintId(@Param("account") UUID account,@Param("req") Long reqId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from SupplierAftercareOperation o where o.id=:id")
    Optional<SupplierAftercareOperation> findForUpdate(@Param("id") UUID id);
    @Query("select o.id from SupplierAftercareOperation o where o.kind in ('REFUND','CARD_VOID') and o.status in ('SUBMITTING','UNKNOWN','ACCEPTED') order by coalesce(o.lastCheckedAt,o.createdAt),o.createdAt")
    List<UUID> recovery(Pageable page);
    @Query("select o.id from SupplierAftercareOperation o where o.kind='COMPLAINT' and o.status='SUBMITTING' and o.submittedAt<:before order by o.submittedAt")
    List<UUID> staleComplaints(@Param("before") LocalDateTime before,Pageable page);
    @Query("select count(o)>0 from SupplierAftercareOperation o where o.purchaseId=:id and o.kind='REFUND' and o.status in ('INTENT','SUBMITTING','UNKNOWN','ACCEPTED')")
    boolean refundUnresolved(@Param("id") UUID purchaseId);
}
