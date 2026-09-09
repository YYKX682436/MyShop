package com.myshop.service;

import com.myshop.entity.Order;

import java.util.Map;
import java.util.UUID;

/** Owns the atomic balance debit performed while a normal order is created. */
public interface BalanceOrderPaymentService {

    void pay(Order order, UUID userId);

    Map<String, Object> buildPaymentResult(Order order);
}
