package com.myshop.repository;
import com.myshop.entity.SupplierDeliveryCard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface SupplierDeliveryCardRepository extends JpaRepository<SupplierDeliveryCard,UUID> {
    List<SupplierDeliveryCard> findByPurchaseIdOrderByCreatedAtAsc(UUID purchaseId);
    Optional<SupplierDeliveryCard> findByAccountIdAndRemoteCardId(UUID accountId,String remoteCardId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from SupplierDeliveryCard c where c.id=:id")
    Optional<SupplierDeliveryCard> findForUpdate(@Param("id") UUID id);
}
