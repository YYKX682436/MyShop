package com.myshop.repository;

import com.myshop.entity.AfterSaleMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AfterSaleMessageRepository extends JpaRepository<AfterSaleMessage, UUID> {

    List<AfterSaleMessage> findByRequestIdInOrderByCreatedAtAscIdAsc(List<UUID> requestIds);

    @org.springframework.data.jpa.repository.Query("""
            select m from AfterSaleMessage m, AfterSaleRequest r
            where m.requestId=r.id and r.orderId=:orderId and m.senderType=:senderType
              and m.senderUserId=:senderId and m.clientKey=:clientKey
            """)
    Optional<AfterSaleMessage> findRetry(UUID orderId, com.myshop.constant.AfterSaleMessageSenderType senderType,
                                         UUID senderId, UUID clientKey);
}
