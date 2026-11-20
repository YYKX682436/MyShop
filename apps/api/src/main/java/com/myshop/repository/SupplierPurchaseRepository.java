package com.myshop.repository;

import com.myshop.entity.SupplierPurchase;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.*;

public interface SupplierPurchaseRepository extends JpaRepository<SupplierPurchase,UUID> {
    Optional<SupplierPurchase> findByOrderItemId(UUID id);
    Optional<SupplierPurchase> findByAccountIdAndOuterNumber(UUID accountId,String outerNumber);
    @Query("select p.id from SupplierPurchase p where p.accountId=:account and p.outerNumber=:outer")
    Optional<UUID> findIdByAccountAndOuter(@Param("account") UUID account,@Param("outer") String outer);
    List<SupplierPurchase> findByOrderId(UUID id);
    boolean existsByOrderId(UUID id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from SupplierPurchase p where p.id=:id")
    Optional<SupplierPurchase> findForUpdate(@Param("id") UUID id);
    @Query("select p.orderId from SupplierPurchase p where p.id=:id")
    Optional<UUID> findOrderId(@Param("id") UUID id);
    @Query("select p.accountId from SupplierPurchase p where p.id=:id")
    Optional<UUID> findAccountId(@Param("id") UUID id);
    @Query("select p.orderItemId from SupplierPurchase p where p.id=:id")
    Optional<UUID> findOrderItemId(@Param("id") UUID id);
    @Modifying
    @Query(value="""
        UPDATE supplier_purchases p SET status='SUBMITTING',submit_started_at=:now,updated_at=:now,error_code=NULL
        WHERE p.id=:id AND p.status='WAITING_PAYMENT'
          AND EXISTS (SELECT 1 FROM orders o WHERE o.id=p.order_id AND o.status='PAID')
          AND EXISTS (SELECT 1 FROM supplier_accounts a WHERE a.id=p.account_id AND a.enabled=true)
        """,nativeQuery=true)
    int claim(@Param("id") UUID id,@Param("now") LocalDateTime now);
    @Query("select p.id from SupplierPurchase p, Order o where o.id=p.orderId " +
            "and ((:allowCreate=true and p.status='WAITING_PAYMENT' and o.status=com.myshop.constant.OrderStatus.PAID) or " +
            "(p.status in ('SUBMITTING','UNKNOWN','UNPAID','PROCESSING','REFUNDING') and o.status in " +
            "(com.myshop.constant.OrderStatus.PAID,com.myshop.constant.OrderStatus.DELIVERED,com.myshop.constant.OrderStatus.AFTER_SALE))) " +
            "order by coalesce(p.lastCheckedAt,p.createdAt), p.createdAt")
    List<UUID> findRecoveryCandidates(@Param("allowCreate") boolean allowCreate,Pageable page);
}
