package com.myshop.service.impl;

import java.sql.DriverManager;
import java.util.UUID;

/** Simulates a worker process dying immediately after its claim commits. */
public class SupplierPurchaseCrashHelper {
    public static void main(String[] args) throws Exception {
        var connection=DriverManager.getConnection(args[0],args[1],args[2]);
        connection.setAutoCommit(false);
        try (var account=connection.prepareStatement("""
                SELECT a.id FROM supplier_accounts a JOIN supplier_purchases p ON p.account_id=a.id
                WHERE p.id=? FOR UPDATE OF a
                """)) {
            account.setObject(1,UUID.fromString(args[3]));
            try (var rows=account.executeQuery()) { if (!rows.next()) Runtime.getRuntime().halt(2); }
        }
        try (var product=connection.prepareStatement("""
                SELECT product.id FROM products product JOIN order_items i ON i.product_id=product.id
                JOIN supplier_purchases p ON p.order_item_id=i.id WHERE p.id=? FOR UPDATE OF product
                """)) {
            product.setObject(1,UUID.fromString(args[3]));
            try (var rows=product.executeQuery()) { if (!rows.next()) Runtime.getRuntime().halt(2); }
        }
        try (var order=connection.prepareStatement("""
                SELECT o.id FROM orders o JOIN supplier_purchases p ON p.order_id=o.id
                WHERE p.id=? FOR UPDATE OF o
                """)) {
            order.setObject(1,UUID.fromString(args[3]));
            try (var rows=order.executeQuery()) { if (!rows.next()) Runtime.getRuntime().halt(2); }
        }
        try (var statement=connection.prepareStatement("""
                UPDATE supplier_purchases p SET status='SUBMITTING',submit_started_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP
                WHERE p.id=? AND p.status='WAITING_PAYMENT'
                AND EXISTS (SELECT 1 FROM orders o WHERE o.id=p.order_id AND o.status='PAID')
                AND EXISTS (SELECT 1 FROM supplier_accounts a WHERE a.id=p.account_id AND a.enabled=true)
                """)) {
            statement.setObject(1,UUID.fromString(args[3]));
            if (statement.executeUpdate()!=1) Runtime.getRuntime().halt(2);
        }
        connection.commit();
        Runtime.getRuntime().halt(0);
    }
}
