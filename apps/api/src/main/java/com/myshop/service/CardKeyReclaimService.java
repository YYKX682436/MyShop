package com.myshop.service;

import com.myshop.entity.CardKeyReclaim;

import java.util.List;
import java.util.UUID;

/** Owns the immutable history written before a reclaimed key is released. */
public interface CardKeyReclaimService {

    void recordAll(List<CardKeyReclaim> reclaims);

    long countByOrderId(UUID orderId);
}
