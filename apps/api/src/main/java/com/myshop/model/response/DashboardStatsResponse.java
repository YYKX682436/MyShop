package com.myshop.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Explicit wire contract for the admin business dashboard. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DashboardStatsResponse(
        BigDecimal todaySales,
        BigDecimal monthSales,
        long todayOrders,
        long monthOrders,
        Double conversionRate,
        long todayPv,
        long todayUv,
        Double todaySalesChangePct,
        Double todayOrdersChangePct,
        long todayNewUsers,
        Double todayNewUsersChangePct,
        long monthNewUsers,
        long totalUsers,
        long todayBuyers,
        long pendingOrders,
        long paidWaitingDelivery,
        long deliveredToday,
        long expiredToday,
        Double todayPaymentSuccessRate,
        long todayPaymentSuccessSampleCount,
        long activeProducts,
        long availableCardKeys,
        long zeroStockProducts,
        List<LowStockProduct> lowStockProducts,
        List<TopProduct> topProducts,
        List<RecentUser> recentUsers
) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LowStockProduct(UUID productId, String title, long availableStock, int threshold) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TopProduct(UUID productId, String title, long quantity, BigDecimal salesAmount) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RecentUser(UUID id, String username, String email, LocalDateTime createdAt) { }
}
