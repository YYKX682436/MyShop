package com.myshop.repository;

import com.myshop.entity.CardKeyReclaim;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CardKeyReclaimRepository extends JpaRepository<CardKeyReclaim, UUID> {

    long countByOrderId(UUID orderId);
}
