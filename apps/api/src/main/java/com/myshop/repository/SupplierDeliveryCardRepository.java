package com.myshop.repository;
import com.myshop.entity.SupplierDeliveryCard;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface SupplierDeliveryCardRepository extends JpaRepository<SupplierDeliveryCard,UUID> {
    List<SupplierDeliveryCard> findByPurchaseIdOrderByCreatedAtAsc(UUID purchaseId);
    Optional<SupplierDeliveryCard> findByAccountIdAndRemoteCardId(UUID accountId,String remoteCardId);
}
