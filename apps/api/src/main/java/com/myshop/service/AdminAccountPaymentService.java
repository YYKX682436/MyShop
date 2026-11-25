package com.myshop.service;

import com.myshop.common.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminAccountPaymentService {
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> recharges(String keyword, String status, String channelCode,
                                                      BigDecimal minAmount, BigDecimal maxAmount,
                                                      int page, int pageSize) {
        int p = Math.max(1, page);
        int s = Math.max(1, Math.min(pageSize, 100));
        StringBuilder filter = new StringBuilder(" WHERE t.type = 'RECHARGE' ");
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            filter.append(" AND (u.username ILIKE ? OR u.email ILIKE ? OR CAST(u.user_no AS text) ILIKE ?) ");
            String like = "%" + keyword.trim() + "%";
            args.add(like); args.add(like); args.add(like);
        }
        if (status != null && !status.isBlank()) {
            String normalized = status.trim().toUpperCase(java.util.Locale.ROOT);
            if ("RECOVERED".equals(normalized)) {
                filter.append(" AND t.status = 'RECOVERED' ");
            } else if ("PAID".equals(normalized)) {
                filter.append(" AND pay.status = 'PAID' AND t.status <> 'RECOVERED' ");
            } else {
                filter.append(" AND COALESCE(pay.status, t.status) = ? ");
                args.add(normalized);
            }
        }
        if (channelCode != null && !channelCode.isBlank()) {
            filter.append(" AND t.channel_code_snapshot = ? ");
            args.add(channelCode.trim());
        }
        if (minAmount != null) { filter.append(" AND t.amount >= ? "); args.add(minAmount); }
        if (maxAmount != null) { filter.append(" AND t.amount <= ? "); args.add(maxAmount); }
        String from = """
                FROM account_payment_transactions t
                JOIN users u ON u.id = t.user_id
                LEFT JOIN LATERAL (
                    SELECT a.* FROM payment_attempts a
                    WHERE a.account_payment_transaction_id = t.id
                    ORDER BY a.created_at DESC, a.id DESC LIMIT 1
                ) pay ON TRUE
                LEFT JOIN payment_channels ch ON ch.id = pay.channel_id
                """;
        Long total = jdbc.queryForObject("SELECT count(*) " + from + filter, Long.class, args.toArray());
        List<Object> rowArgs = new ArrayList<>(args);
        rowArgs.add(s);
        rowArgs.add((long) (p - 1) * s);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT t.id, t.user_id, u.user_no, u.username, u.email, t.amount,
                       t.status AS business_status, COALESCE(pay.status, t.status) AS payment_status,
                       t.payment_method, t.channel_code_snapshot AS channel_code,
                       ch.channel_name, pay.payment_type, t.subsite_id, t.created_at,
                       t.completed_at, t.recovery_reason
                """ + from + filter + " ORDER BY t.created_at DESC, t.id DESC LIMIT ? OFFSET ?",
                rowArgs.toArray());
        return PageResult.of(rows, p, s, total == null ? 0 : total);
    }
}
