package com.myshop.repository;

import com.myshop.entity.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {

    List<OrderItem> findByOrderId(UUID orderId);

    List<OrderItem> findByOrderIdIn(List<UUID> orderIds);

    @Query("SELECT COALESCE(SUM(oi.quantity), 0) FROM OrderItem oi JOIN Order o ON oi.orderId = o.id " +
            "WHERE oi.productId = :productId AND o.orderType = com.myshop.constant.OrderType.NORMAL " +
            "AND (o.status = com.myshop.constant.OrderStatus.PAID OR o.status = com.myshop.constant.OrderStatus.DELIVERED)")
    int sumQuantityByProductId(@Param("productId") UUID productId);

    /** One PostgreSQL aggregate supplies the recent best sellers. */
    @Query(value = """
            SELECT oi.product_id,
                   MAX(oi.product_title) AS title,
                   SUM(oi.quantity) AS quantity,
                   COALESCE(SUM(oi.subtotal), 0) AS sales_amount
            FROM order_items oi
            JOIN orders o ON o.id = oi.order_id
            WHERE o.status IN ('PAID', 'DELIVERED')
              AND o.order_type = 'NORMAL'
              AND o.paid_at >= :since
              AND o.paid_at < :until
            GROUP BY oi.product_id
            ORDER BY SUM(oi.quantity) DESC,
                     COALESCE(SUM(oi.subtotal), 0) DESC
            LIMIT 5
            """, nativeQuery = true)
    List<Object[]> findTopProductsBetween(@Param("since") java.time.LocalDateTime since,
                                          @Param("until") java.time.LocalDateTime until);
}
