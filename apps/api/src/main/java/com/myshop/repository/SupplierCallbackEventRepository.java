package com.myshop.repository;
import com.myshop.entity.SupplierCallbackEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.*;
public interface SupplierCallbackEventRepository extends JpaRepository<SupplierCallbackEvent,UUID> {
    boolean existsByAccountIdAndEventHash(UUID accountId,String eventHash);
    List<SupplierCallbackEvent> findByPurchaseIdAndProcessedFalse(UUID purchaseId);
    @Modifying
    @Query(value="""
            INSERT INTO supplier_callback_events(id,created_at,updated_at,account_id,purchase_id,event_hash,upstream_status,encrypted_cards,processed)
            VALUES (:id,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,:account,:purchase,:hash,:status,:cards,false)
            ON CONFLICT (account_id,event_hash) DO NOTHING
            """,nativeQuery=true)
    int insertOnce(@Param("id") UUID id,@Param("account") UUID account,@Param("purchase") UUID purchase,
                   @Param("hash") String hash,@Param("status") int status,@Param("cards") String cards);
}
