package com.myshop.service;

import com.myshop.entity.Order;
import com.myshop.entity.User;

import java.util.Map;

/** Owns the atomic balance debit performed while a normal order is created. */
public interface BalanceOrderPaymentService {

    void pay(Order order, User user);

    Map<String, Object> buildPaymentResult(Order order);
}
