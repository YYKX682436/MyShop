package com.myshop.repository;

import com.myshop.constant.OrderStatus;
import com.myshop.constant.OrderPaymentMethod;
import com.myshop.constant.OrderType;
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

    boolean existsByPaymentMethodAndStatusAndOrderType(OrderPaymentMethod paymentMethod,
                                                        OrderStatus status,
                                                        OrderType orderType);

    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    Optional<Order> findByLookupTokenHash(String lookupTokenHash);

    Optional<Order> findByOrderNo(Long orderNo);

    @Query(value = "SELECT nextval('order_no_sequence')", nativeQuery = true)
    Long nextOrderNo();

    List<Order> findByStatusAndDeliveredAtIsNullOrderByCreatedAtAsc(OrderStatus status, Pageable pageable);

    /** Only paid orders with unfinished automatic card delivery are retryable. */
    @Query(value = "SELECT DISTINCT o.* FROM orders o JOIN order_items oi ON oi.order_id = o.id "
            + "WHERE o.status = 'PAID' AND oi.fulfillment_status NOT IN ('DELIVERED', 'CANCELLED') "
            + "AND oi.delivery_type = 'AUTO' AND oi.stock_mode = 'CARD_KEY' "
            + "ORDER BY o.created_at ASC", nativeQuery = true)
    List<Order> findPaidOrdersWithPendingAutoItems(Pageable pageable);

    Page<Order> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<Order> findByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, OrderStatus status, Pageable pageable);

    List<Order> findByIdIn(List<UUID> ids);

    @Query("SELECT o FROM Order o WHERE o.status = com.myshop.constant.OrderStatus.PENDING AND o.expiresAt < :now")
    List<Order> findExpiredOrders(@Param("now") LocalDateTime now);

    /** One aggregate query supplies successful-order metrics for a user page. */
    @Query("""
            SELECT o.userId AS userId,
                   COUNT(o) AS orderCount,
                   COALESCE(SUM(o.actualAmount), 0) AS totalSpent
            FROM Order o
            WHERE o.userId IN :userIds
              AND o.status IN (com.myshop.constant.OrderStatus.PAID,
                               com.myshop.constant.OrderStatus.DELIVERED)
              AND o.orderType = com.myshop.constant.OrderType.NORMAL
            GROUP BY o.userId
            """)
    List<UserOrderMetricsProjection> findSuccessfulMetricsByUserIdIn(
            @Param("userIds") List<UUID> userIds);

    /** 悲观写锁：SELECT ... FOR UPDATE，用于防止并发发货等竞态条件 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") UUID id);

    @Query("SELECT o FROM Order o WHERE o.riskFlagged = true ORDER BY o.createdAt DESC")
    Page<Order> findRiskFlaggedOrders(Pageable pageable);

    // Dashboard aggregate queries
    @Query("SELECT COALESCE(SUM(o.actualAmount), 0) FROM Order o WHERE (o.status = com.myshop.constant.OrderStatus.PAID OR o.status = com.myshop.constant.OrderStatus.DELIVERED) AND o.orderType = com.myshop.constant.OrderType.NORMAL AND o.paidAt >= :since")
    BigDecimal sumSalesSince(@Param("since") LocalDateTime since);

    @Query("SELECT COALESCE(SUM(o.actualAmount), 0) FROM Order o WHERE " +
            "(o.status = com.myshop.constant.OrderStatus.PAID OR o.status = com.myshop.constant.OrderStatus.DELIVERED) " +
            "AND o.orderType = com.myshop.constant.OrderType.NORMAL " +
            "AND o.paidAt >= :start AND o.paidAt < :end")
    BigDecimal sumSalesBetween(@Param("start") LocalDateTime start,
                               @Param("end") LocalDateTime end);

    @Query("SELECT COUNT(o) FROM Order o WHERE (o.status = com.myshop.constant.OrderStatus.PAID OR o.status = com.myshop.constant.OrderStatus.DELIVERED) AND o.orderType = com.myshop.constant.OrderType.NORMAL AND o.paidAt >= :since")
    long countPaidOrdersSince(@Param("since") LocalDateTime since);

    @Query("SELECT COUNT(o) FROM Order o WHERE " +
            "(o.status = com.myshop.constant.OrderStatus.PAID OR o.status = com.myshop.constant.OrderStatus.DELIVERED) " +
            "AND o.orderType = com.myshop.constant.OrderType.NORMAL " +
            "AND o.paidAt >= :start AND o.paidAt < :end")
    long countPaidOrdersBetween(@Param("start") LocalDateTime start,
                                @Param("end") LocalDateTime end);

    long countByStatus(OrderStatus status);

    @Query("SELECT COUNT(o) FROM Order o WHERE o.status = com.myshop.constant.OrderStatus.DELIVERED " +
            "AND o.orderType = com.myshop.constant.OrderType.NORMAL " +
            "AND o.deliveredAt >= :start AND o.deliveredAt < :end")
    long countDeliveredBetween(@Param("start") LocalDateTime start,
                               @Param("end") LocalDateTime end);

    @Query("SELECT o.status, COUNT(o) FROM Order o WHERE o.createdAt >= :start AND o.createdAt < :end " +
            "AND (o.status = com.myshop.constant.OrderStatus.PAID " +
            "OR o.status = com.myshop.constant.OrderStatus.DELIVERED " +
            "OR o.status = com.myshop.constant.OrderStatus.EXPIRED) " +
            "AND o.orderType = com.myshop.constant.OrderType.NORMAL " +
            "GROUP BY o.status")
    List<Object[]> countPaymentOutcomesBetween(@Param("start") LocalDateTime start,
                                               @Param("end") LocalDateTime end);

    /** Registered users, guest sessions, and anomalous guest orders each have a stable buyer identity. */
    @Query(value = """
            SELECT COUNT(DISTINCT CASE
                       WHEN o.user_id IS NOT NULL THEN 'u:' || CAST(o.user_id AS text)
                       WHEN NULLIF(o.session_token, '') IS NOT NULL THEN 's:' || o.session_token
                       ELSE 'o:' || CAST(o.id AS text)
                   END)
            FROM orders o
            WHERE o.status IN ('PAID', 'DELIVERED')
              AND o.paid_at >= :start
              AND o.paid_at < :end
              AND o.order_type = 'NORMAL'
            """, nativeQuery = true)
    long countDistinctBuyersBetween(@Param("start") LocalDateTime start,
                                    @Param("end") LocalDateTime end);

    @Query(value = """
            SELECT TO_CHAR(DATE_TRUNC('day', o.paid_at), 'YYYY-MM-DD') AS bucket,
                   COALESCE(SUM(COALESCE(o.actual_amount, 0)), 0) AS sales_amount,
                   COUNT(o.id) AS order_count
            FROM orders o
            WHERE o.status IN ('PAID', 'DELIVERED')
              AND o.order_type = 'NORMAL'
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
              AND o.order_type = 'NORMAL'
              AND o.paid_at >= :start
              AND o.paid_at < :end
            GROUP BY DATE_TRUNC('month', o.paid_at)
            ORDER BY DATE_TRUNC('month', o.paid_at)
            """, nativeQuery = true)
    List<Object[]> findMonthlySalesTrend(@Param("start") LocalDateTime start,
                                         @Param("end") LocalDateTime end);

    @Query("SELECT COUNT(o) FROM Order o WHERE (o.status = com.myshop.constant.OrderStatus.PAID OR o.status = com.myshop.constant.OrderStatus.DELIVERED) AND o.orderType = com.myshop.constant.OrderType.NORMAL")
    long countTotalPaidOrders();

    long count();

    /**
     * Search the order page without joining one-to-many relations. Every
     * related-field match is an EXISTS predicate, so count/pagination remain
     * one row per order even when an order has multiple items or cards.
     */
    @Query(value = """
            SELECT o.*
            FROM orders o
            WHERE o.order_type = 'NORMAL'
              AND CAST(:sortDirection AS text) IN ('asc', 'desc')
              AND (CAST(:status AS text) IS NULL OR o.status = CAST(:status AS text))
              AND (CAST(:createdFrom AS timestamp) IS NULL OR o.created_at >= CAST(:createdFrom AS timestamp))
              AND (CAST(:createdTo AS timestamp) IS NULL OR o.created_at < CAST(:createdTo AS timestamp))
              AND (
                    CAST(:keywordPattern AS text) IS NULL
                    OR CAST(o.order_no AS text) ILIKE CAST(:keywordPattern AS text) ESCAPE '\\'
                    OR CAST(o.id AS text) ILIKE CAST(:keywordPattern AS text) ESCAPE '\\'
                    OR COALESCE(o.payment_method, '') ILIKE CAST(:keywordPattern AS text) ESCAPE '\\'
                    OR COALESCE(o.payment_channel_code, '') ILIKE CAST(:keywordPattern AS text) ESCAPE '\\'
                    OR EXISTS (
                        SELECT 1
                        FROM payment_attempts pa
                        WHERE pa.order_id = o.id
                          AND (COALESCE(pa.channel_code_snapshot, '') ILIKE CAST(:keywordPattern AS text) ESCAPE '\\'
                               OR COALESCE(pa.payment_type, '') ILIKE CAST(:keywordPattern AS text) ESCAPE '\\'
                               OR COALESCE(pa.merchant_trade_no, '') ILIKE CAST(:keywordPattern AS text) ESCAPE '\\'
                               OR COALESCE(pa.provider_trade_no, '') ILIKE CAST(:keywordPattern AS text) ESCAPE '\\')
                    )
                    OR EXISTS (
                        SELECT 1
                        FROM order_items oi
                        WHERE oi.order_id = o.id
                          AND (
                                COALESCE(oi.product_title, '') ILIKE CAST(:keywordPattern AS text) ESCAPE '\\'
                                OR CAST(oi.product_no AS text) ILIKE CAST(:keywordPattern AS text) ESCAPE '\\'
                                OR COALESCE(oi.spec_name, '') ILIKE CAST(:keywordPattern AS text) ESCAPE '\\'
                                OR CAST(oi.customer_inputs AS text) ILIKE CAST(:keywordPattern AS text) ESCAPE '\\'
                          )
                    )
                    OR EXISTS (
                        SELECT 1
                        FROM users u
                        WHERE u.id = o.user_id
                          AND (
                                COALESCE(u.username, '') ILIKE CAST(:keywordPattern AS text) ESCAPE '\\'
                                OR CAST(u.user_no AS text) ILIKE CAST(:keywordPattern AS text) ESCAPE '\\'
                                OR CAST(u.id AS text) ILIKE CAST(:keywordPattern AS text) ESCAPE '\\'
                                OR COALESCE(u.email, '') ILIKE CAST(:keywordPattern AS text) ESCAPE '\\'
                                OR COALESCE(u.phone, '') ILIKE CAST(:keywordPattern AS text) ESCAPE '\\'
                          )
                    )
                    OR EXISTS (
                        SELECT 1
                        FROM card_keys ck
                        WHERE ck.order_id = o.id
                          AND (
                                CAST(ck.id AS text) ILIKE CAST(:keywordPattern AS text) ESCAPE '\\'
                                OR CAST(ck.content_parts AS text) ILIKE CAST(:keywordPattern AS text) ESCAPE '\\'
                          )
                    )
              )
            ORDER BY
                CASE WHEN CAST(:sortDirection AS text) = 'asc' THEN o.created_at END ASC,
                CASE WHEN CAST(:sortDirection AS text) = 'desc' THEN o.created_at END DESC,
                CASE WHEN CAST(:sortDirection AS text) = 'asc' THEN o.id END ASC,
                CASE WHEN CAST(:sortDirection AS text) = 'desc' THEN o.id END DESC
            """,
            countQuery = """
            SELECT COUNT(*)
            FROM orders o
            WHERE o.order_type = 'NORMAL'
              AND CAST(:sortDirection AS text) IN ('asc', 'desc')
              AND (CAST(:status AS text) IS NULL OR o.status = CAST(:status AS text))
              AND (CAST(:createdFrom AS timestamp) IS NULL OR o.created_at >= CAST(:createdFrom AS timestamp))
              AND (CAST(:createdTo AS timestamp) IS NULL OR o.created_at < CAST(:createdTo AS timestamp))
              AND (
                    CAST(:keywordPattern AS text) IS NULL
                    OR CAST(o.order_no AS text) ILIKE CAST(:keywordPattern AS text) ESCAPE '\\'
                    OR CAST(o.id AS text) ILIKE CAST(:keywordPattern AS text) ESCAPE '\\'
                    OR COALESCE(o.payment_method, '') ILIKE CAST(:keywordPattern AS text) ESCAPE '\\'
                    OR COALESCE(o.payment_channel_code, '') ILIKE CAST(:keywordPattern AS text) ESCAPE '\\'
                    OR EXISTS (
                        SELECT 1
                        FROM payment_attempts pa
                        WHERE pa.order_id = o.id
                          AND (COALESCE(pa.channel_code_snapshot, '') ILIKE CAST(:keywordPattern AS text) ESCAPE '\\'
                               OR COALESCE(pa.payment_type, '') ILIKE CAST(:keywordPattern AS text) ESCAPE '\\'
                               OR COALESCE(pa.merchant_trade_no, '') ILIKE CAST(:keywordPattern AS text) ESCAPE '\\'
                               OR COALESCE(pa.provider_trade_no, '') ILIKE CAST(:keywordPattern AS text) ESCAPE '\\')
                    )
                    OR EXISTS (
                        SELECT 1
                        FROM order_items oi
                        WHERE oi.order_id = o.id
                          AND (
                                COALESCE(oi.product_title, '') ILIKE CAST(:keywordPattern AS text) ESCAPE '\\'
                                OR CAST(oi.product_no AS text) ILIKE CAST(:keywordPattern AS text) ESCAPE '\\'
                                OR COALESCE(oi.spec_name, '') ILIKE CAST(:keywordPattern AS text) ESCAPE '\\'
                                OR CAST(oi.customer_inputs AS text) ILIKE CAST(:keywordPattern AS text) ESCAPE '\\'
                          )
                    )
                    OR EXISTS (
                        SELECT 1
                        FROM users u
                        WHERE u.id = o.user_id
                          AND (
                                COALESCE(u.username, '') ILIKE CAST(:keywordPattern AS text) ESCAPE '\\'
                                OR CAST(u.user_no AS text) ILIKE CAST(:keywordPattern AS text) ESCAPE '\\'
                                OR CAST(u.id AS text) ILIKE CAST(:keywordPattern AS text) ESCAPE '\\'
                                OR COALESCE(u.email, '') ILIKE CAST(:keywordPattern AS text) ESCAPE '\\'
                                OR COALESCE(u.phone, '') ILIKE CAST(:keywordPattern AS text) ESCAPE '\\'
                          )
                    )
                    OR EXISTS (
                        SELECT 1
                        FROM card_keys ck
                        WHERE ck.order_id = o.id
                          AND (
                                CAST(ck.id AS text) ILIKE CAST(:keywordPattern AS text) ESCAPE '\\'
                                OR CAST(ck.content_parts AS text) ILIKE CAST(:keywordPattern AS text) ESCAPE '\\'
                          )
                    )
              )
            """,
            nativeQuery = true)
    Page<Order> searchAdminOrders(@Param("status") String status,
                                   @Param("createdFrom") LocalDateTime createdFrom,
                                   @Param("createdTo") LocalDateTime createdTo,
                                   @Param("keywordPattern") String keywordPattern,
                                   @Param("sortDirection") String sortDirection,
                                   Pageable pageable);
}
