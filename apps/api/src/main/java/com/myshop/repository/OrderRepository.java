package com.myshop.repository;

import com.myshop.constant.OrderStatus;
import com.myshop.entity.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    Optional<Order> findByLookupTokenHash(String lookupTokenHash);

    List<Order> findByStatusAndDeliveredAtIsNullOrderByCreatedAtAsc(OrderStatus status, Pageable pageable);

    /** Only paid orders with unfinished automatic card delivery are retryable. */
    @Query(value = "SELECT DISTINCT o.* FROM orders o JOIN order_items oi ON oi.order_id = o.id "
            + "WHERE o.status = 'PAID' AND oi.fulfillment_status NOT IN ('DELIVERED', 'CANCELLED') "
            + "AND oi.delivery_type = 'AUTO' AND oi.stock_mode = 'CARD_KEY' "
            + "ORDER BY o.created_at ASC", nativeQuery = true)
    List<Order> findPaidOrdersWithPendingAutoItems(Pageable pageable);

    Page<Order> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<Order> findByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, OrderStatus status, Pageable pageable);

    List<Order> findByEmailInOrderByCreatedAtDesc(List<String> emails);

    List<Order> findByIdIn(List<UUID> ids);

    @Query("SELECT o FROM Order o WHERE o.status = com.myshop.constant.OrderStatus.PENDING AND o.expiresAt < :now")
    List<Order> findExpiredOrders(@Param("now") LocalDateTime now);

    long countByUserIdAndStatus(UUID userId, OrderStatus status);

    /** One aggregate query supplies successful-order metrics for a user page. */
    @Query("""
            SELECT o.userId AS userId,
                   COUNT(o) AS orderCount,
                   COALESCE(SUM(o.actualAmount), 0) AS totalSpent
            FROM Order o
            WHERE o.userId IN :userIds
              AND o.status IN (com.myshop.constant.OrderStatus.PAID,
                               com.myshop.constant.OrderStatus.DELIVERED)
            GROUP BY o.userId
            """)
    List<UserOrderMetricsProjection> findSuccessfulMetricsByUserIdIn(
            @Param("userIds") List<UUID> userIds);

    long countByClientIpAndStatus(String clientIp, OrderStatus status);

    long countByEmailAndStatus(String email, OrderStatus status);

    /** 悲观写锁：SELECT ... FOR UPDATE，用于防止并发发货等竞态条件 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") UUID id);

    @Query("SELECT o FROM Order o WHERE o.riskFlagged = true ORDER BY o.createdAt DESC")
    Page<Order> findRiskFlaggedOrders(Pageable pageable);

    // Dashboard aggregate queries
    @Query("SELECT COALESCE(SUM(o.actualAmount), 0) FROM Order o WHERE (o.status = com.myshop.constant.OrderStatus.PAID OR o.status = com.myshop.constant.OrderStatus.DELIVERED) AND o.paidAt >= :since")
    BigDecimal sumSalesSince(@Param("since") LocalDateTime since);

    @Query("SELECT COALESCE(SUM(o.actualAmount), 0) FROM Order o WHERE " +
            "(o.status = com.myshop.constant.OrderStatus.PAID OR o.status = com.myshop.constant.OrderStatus.DELIVERED) " +
            "AND o.paidAt >= :start AND o.paidAt < :end")
    BigDecimal sumSalesBetween(@Param("start") LocalDateTime start,
                               @Param("end") LocalDateTime end);

    @Query("SELECT COUNT(o) FROM Order o WHERE (o.status = com.myshop.constant.OrderStatus.PAID OR o.status = com.myshop.constant.OrderStatus.DELIVERED) AND o.paidAt >= :since")
    long countPaidOrdersSince(@Param("since") LocalDateTime since);

    @Query("SELECT COUNT(o) FROM Order o WHERE " +
            "(o.status = com.myshop.constant.OrderStatus.PAID OR o.status = com.myshop.constant.OrderStatus.DELIVERED) " +
            "AND o.paidAt >= :start AND o.paidAt < :end")
    long countPaidOrdersBetween(@Param("start") LocalDateTime start,
                                @Param("end") LocalDateTime end);

    long countByStatus(OrderStatus status);

    @Query("SELECT COUNT(o) FROM Order o WHERE o.status = com.myshop.constant.OrderStatus.DELIVERED " +
            "AND o.deliveredAt >= :start AND o.deliveredAt < :end")
    long countDeliveredBetween(@Param("start") LocalDateTime start,
                               @Param("end") LocalDateTime end);

    @Query("SELECT o.status, COUNT(o) FROM Order o WHERE o.createdAt >= :start AND o.createdAt < :end " +
            "AND (o.status = com.myshop.constant.OrderStatus.PAID " +
            "OR o.status = com.myshop.constant.OrderStatus.DELIVERED " +
            "OR o.status = com.myshop.constant.OrderStatus.EXPIRED) " +
            "GROUP BY o.status")
    List<Object[]> countPaymentOutcomesBetween(@Param("start") LocalDateTime start,
                                               @Param("end") LocalDateTime end);

    @Query("SELECT COUNT(DISTINCT LOWER(TRIM(o.email))) FROM Order o " +
            "WHERE (o.status = com.myshop.constant.OrderStatus.PAID OR " +
            "o.status = com.myshop.constant.OrderStatus.DELIVERED) " +
            "AND o.paidAt >= :start AND o.paidAt < :end " +
            "AND o.email IS NOT NULL AND TRIM(o.email) <> ''")
    long countDistinctBuyersBetween(@Param("start") LocalDateTime start,
                                    @Param("end") LocalDateTime end);

    @Query(value = """
            SELECT TO_CHAR(DATE_TRUNC('day', o.paid_at), 'YYYY-MM-DD') AS bucket,
                   COALESCE(SUM(COALESCE(o.actual_amount, 0)), 0) AS sales_amount,
                   COUNT(o.id) AS order_count
            FROM orders o
            WHERE o.status IN ('PAID', 'DELIVERED')
              AND o.paid_at >= :start
              AND o.paid_at < :end
            GROUP BY DATE_TRUNC('day', o.paid_at)
            ORDER BY DATE_TRUNC('day', o.paid_at)
            """, nativeQuery = true)
    List<Object[]> findDailySalesTrend(@Param("start") LocalDateTime start,
                                       @Param("end") LocalDateTime end);

    @Query(value = """
            SELECT TO_CHAR(DATE_TRUNC('month', o.paid_at), 'YYYY-MM') AS bucket,
                   COALESCE(SUM(COALESCE(o.actual_amount, 0)), 0) AS sales_amount,
                   COUNT(o.id) AS order_count
            FROM orders o
            WHERE o.status IN ('PAID', 'DELIVERED')
              AND o.paid_at >= :start
              AND o.paid_at < :end
            GROUP BY DATE_TRUNC('month', o.paid_at)
            ORDER BY DATE_TRUNC('month', o.paid_at)
            """, nativeQuery = true)
    List<Object[]> findMonthlySalesTrend(@Param("start") LocalDateTime start,
                                         @Param("end") LocalDateTime end);

    @Query("SELECT COUNT(o) FROM Order o WHERE (o.status = com.myshop.constant.OrderStatus.PAID OR o.status = com.myshop.constant.OrderStatus.DELIVERED)")
    long countTotalPaidOrders();

    long count();

    // 管理后台订单列表 — 无搜索词
    @Query("SELECT o FROM Order o WHERE " +
            "(:status IS NULL OR o.status = :status) " +
            "AND (:paymentMethod IS NULL OR o.paymentMethod = :paymentMethod) " +
            "AND (:isRiskFlagged IS NULL OR o.riskFlagged = :isRiskFlagged) " +
            "ORDER BY o.createdAt DESC")
    Page<Order> findAdminOrders(@Param("status") OrderStatus status,
                                @Param("paymentMethod") String paymentMethod,
                                @Param("isRiskFlagged") Boolean isRiskFlagged,
                                Pageable pageable);

    // 管理后台订单列表 — 带搜索词（按订单ID、邮箱或商品名称搜索，keyword 保证非 null）
    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN OrderItem oi ON oi.orderId = o.id WHERE " +
            "(:status IS NULL OR o.status = :status) " +
            "AND (:paymentMethod IS NULL OR o.paymentMethod = :paymentMethod) " +
            "AND (:isRiskFlagged IS NULL OR o.riskFlagged = :isRiskFlagged) " +
            "AND (str(o.id) LIKE :keywordPattern OR o.email LIKE :keywordPattern OR oi.productTitle LIKE :keywordPattern) " +
            "ORDER BY o.createdAt DESC")
    Page<Order> findAdminOrdersByKeyword(@Param("status") OrderStatus status,
                                         @Param("paymentMethod") String paymentMethod,
                                         @Param("isRiskFlagged") Boolean isRiskFlagged,
                                         @Param("keywordPattern") String keywordPattern,
                                         Pageable pageable);
}
