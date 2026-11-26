package com.myshop.service;

import com.myshop.context.RequestContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.time.LocalDateTime;

/** Complete, bounded aggregate queries for the single admin attention endpoint. */
@Service
@RequiredArgsConstructor
public class AdminAttentionService {
    private final JdbcTemplate jdbc;
    private final SupportTimelineService supportTimeline;

    @Transactional(readOnly = true)
    public Map<String, Object> summary() {
        // Domain timestamps are written by the API in its configured local time.
        // Bind the cutoff from the same clock instead of comparing against the
        // PostgreSQL container's (UTC) session timezone.
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> orders = jdbc.queryForMap("""
                WITH after_sale AS (
                    SELECT DISTINCT request.order_id
                    FROM after_sale_requests request JOIN orders o ON o.id=request.order_id
                    WHERE o.order_type='NORMAL' AND request.status='PENDING' AND request.waiting_for='ADMIN'
                ), manual_fulfillment AS (
                    SELECT DISTINCT o.id AS order_id
                    FROM orders o JOIN order_items oi ON oi.order_id=o.id
                    WHERE o.order_type='NORMAL' AND o.status='PAID' AND oi.delivery_type='MANUAL'
                      AND oi.fulfillment_status='WAITING_MANUAL'
                ), supplier_exception AS (
                    SELECT DISTINCT o.id AS order_id
                    FROM orders o
                    JOIN supplier_purchases purchase ON purchase.order_id=o.id
                    LEFT JOIN supplier_aftercare_operations operation ON operation.purchase_id=purchase.id
                    WHERE o.order_type='NORMAL' AND o.status IN ('PAID','DELIVERED','AFTER_SALE') AND (
                        purchase.status='BLOCKED'
                        OR (purchase.status IN ('UNKNOWN','SUBMITTING')
                            AND coalesce(purchase.submit_started_at,purchase.created_at)<=?)
                        OR (operation.kind='COMPLAINT' AND operation.status='UNKNOWN')
                        OR (operation.kind IN ('REFUND','CARD_VOID')
                            AND operation.status IN ('INTENT','SUBMITTING','UNKNOWN','ACCEPTED')
                            AND coalesce(operation.submitted_at,operation.created_at)<=?)
                    )
                ), auto_delivery_exception AS (
                    SELECT DISTINCT o.id AS order_id
                    FROM orders o
                    JOIN order_items oi ON oi.order_id=o.id
                    WHERE o.order_type='NORMAL' AND o.status='PAID' AND oi.delivery_type='AUTO' AND oi.stock_mode='CARD_KEY'
                      AND oi.fulfillment_status='PENDING'
                      AND o.paid_at <= ?
                ), payment_exception AS (
                    SELECT o.id AS order_id
                    FROM payment_attempts attempt JOIN orders o ON o.id=attempt.order_id
                    WHERE o.order_type='NORMAL' AND o.status IN ('PENDING','EXPIRED')
                      AND attempt.status='PENDING'
                      AND (attempt.creation_status='UNKNOWN'
                           OR (attempt.creation_status='SUBMITTING'
                               AND coalesce(attempt.creation_started_at,attempt.created_at)<=?))
                      AND NOT EXISTS (
                          SELECT 1 FROM operation_logs review
                          WHERE review.target_type='ORDER' AND review.target_id=o.id::text
                            AND review.action='order.payment_review'
                            AND review.detail LIKE '支付记录 ' || attempt.id::text || '：%'
                            AND review.created_at>=attempt.created_at
                      )
                    UNION
                    SELECT o.id AS order_id
                    FROM webhook_events event
                    JOIN payment_attempts attempt ON attempt.id=event.payment_attempt_id
                    JOIN orders o ON o.id=event.order_id
                    WHERE o.order_type='NORMAL' AND left(event.process_result,10)='LATE_PAID_'
                      AND NOT EXISTS (
                          SELECT 1 FROM operation_logs review
                          WHERE review.target_type='ORDER' AND review.target_id=o.id::text
                            AND review.action='order.payment_review'
                            AND review.detail LIKE '支付记录 ' || attempt.id::text || '：%'
                            AND review.created_at>=event.created_at
                      )
                ), all_attention AS (
                    SELECT order_id FROM after_sale
                    UNION SELECT order_id FROM manual_fulfillment
                    UNION SELECT order_id FROM supplier_exception
                    UNION SELECT order_id FROM auto_delivery_exception
                    UNION SELECT order_id FROM payment_exception
                )
                SELECT
                    (SELECT count(*) FROM all_attention) AS total,
                    (SELECT count(*) FROM after_sale) AS after_sale,
                    (SELECT count(*) FROM manual_fulfillment) AS manual_fulfillment,
                    (SELECT count(*) FROM supplier_exception) AS supplier_exception,
                    (SELECT count(*) FROM auto_delivery_exception) AS auto_delivery_exception,
                    (SELECT count(*) FROM payment_exception) AS payment_exception
                """, now.minusMinutes(10), now.minusMinutes(10), now.minusMinutes(10), now.minusMinutes(10));

        Map<String, Long> support = supportTimeline.adminAttentionCounts(RequestContext.getUserId());
        long supplierMappings = jdbc.queryForObject("""
                SELECT count(*) FROM supplier_product_mappings m
                JOIN supplier_accounts a ON a.id=m.account_id AND a.enabled=true
                JOIN products p ON p.id=m.product_id AND p.is_enabled=true AND p.is_deleted=0 AND p.stock_mode='SUPPLIER'
                WHERE ((m.spec_id IS NULL AND p.spec_enabled=false)
                    OR (m.spec_id IS NOT NULL AND p.spec_enabled=true AND EXISTS (
                        SELECT 1 FROM product_specs s WHERE s.id=m.spec_id AND s.product_id=p.id AND s.is_deleted=0)))
                  AND (
                    m.sync_error IN ('CONFIG','DATA')
                    OR (m.sync_error IN ('CONNECTION','HTTP','BUSINESS','INTERNAL')
                        AND m.last_synced_at <= ?)
                    OR (m.sync_error IS NULL AND m.last_synced_at <= ?
                        AND m.last_attempt_at <= ?)
                  )
                """, Long.class, now.minusMinutes(25), now.minusMinutes(25), now.minusMinutes(25));
        long lowStockProducts = jdbc.queryForObject("""
                SELECT CASE WHEN EXISTS (
                    SELECT 1 FROM site_configs WHERE config_key='low_stock_attention_enabled'
                      AND lower(config_value)='true'
                ) THEN (
                    SELECT count(*) FROM products p
                    LEFT JOIN (
                        SELECT card.product_id, count(*) FILTER (WHERE card.status='AVAILABLE') AS available_stock
                        FROM card_keys card JOIN products local_product ON local_product.id=card.product_id
                          AND local_product.is_enabled=true AND local_product.is_deleted=0 AND local_product.stock_mode='CARD_KEY'
                        GROUP BY card.product_id
                    ) card_stock ON card_stock.product_id=p.id
                    LEFT JOIN (
                        SELECT stock.product_id, sum(stock.available_quantity) AS available_stock
                        FROM manual_stocks stock JOIN products local_product ON local_product.id=stock.product_id
                          AND local_product.is_enabled=true AND local_product.is_deleted=0 AND local_product.stock_mode='MANUAL'
                        GROUP BY stock.product_id
                    ) manual_stock ON manual_stock.product_id=p.id
                    WHERE p.is_enabled=true AND p.is_deleted=0 AND p.stock_mode IN ('CARD_KEY','MANUAL')
                      AND (CASE WHEN p.stock_mode='MANUAL' THEN coalesce(manual_stock.available_stock,0)
                                ELSE coalesce(card_stock.available_stock,0) END) <= p.low_stock_threshold
                ) ELSE 0 END
                """, Long.class);

        long orderTotal = number(orders.get("total"));
        long supportTotal = support.getOrDefault("total", 0L);
        long total = orderTotal + supportTotal + supplierMappings + lowStockProducts;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orders_total", orderTotal);
        result.put("after_sale_needs_attention", number(orders.get("after_sale")));
        result.put("manual_fulfillment_orders", number(orders.get("manual_fulfillment")));
        result.put("supplier_exception_orders", number(orders.get("supplier_exception")));
        result.put("auto_delivery_exception_orders", number(orders.get("auto_delivery_exception")));
        result.put("payment_exception_orders", number(orders.get("payment_exception")));
        result.put("support_total", supportTotal);
        result.put("support_unread", support.getOrDefault("unread", 0L));
        result.put("support_pending", support.getOrDefault("pending", 0L));
        result.put("supplier_needs_attention", supplierMappings);
        result.put("low_stock_products", lowStockProducts);
        result.put("total", total);
        return result;
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
