package com.myshop.repository;

import com.myshop.entity.AfterSaleMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AfterSaleMessageRepository extends JpaRepository<AfterSaleMessage, UUID> {

    List<AfterSaleMessage> findByRequestIdInOrderByCreatedAtAscIdAsc(List<UUID> requestIds);
}
