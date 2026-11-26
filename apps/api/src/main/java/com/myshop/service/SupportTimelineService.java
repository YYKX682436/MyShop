package com.myshop.service;

import com.myshop.constant.ErrorCode;
import com.myshop.context.RequestContext;
import com.myshop.context.SiteRequestContext;
import com.myshop.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Server-side aggregation of legacy order threads into one customer timeline. */
@Service
@RequiredArgsConstructor
public class SupportTimelineService {
    private static final UUID MAIN_SCOPE = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final String ACTIVITY_SELECT = """
            SELECT a.seq,a.source_type,a.source_id,a.happened_at,
              sm.content AS support_content,sm.sender_type AS support_sender,sm.client_key AS support_key,
              sm.order_id AS support_order_id,sm.source_announcement_id,
              am.content AS sale_content,am.sender_type AS sale_sender,am.client_key AS sale_key,
              r.id AS request_id,r.reason,r.rejection_reason,r.submitted_at,
              e.status AS supplier_status,e.reply AS supplier_reply,
              se.event_type,se.amount AS event_amount,se.direction,se.balance_transaction_id,se.balance_after,
              se.target_level_name,se.order_id AS event_order_id,
              target_order.id AS target_order_id,target_order.order_no,target_order.status AS order_status,
              target_order.actual_amount,refund_ledger.id AS refund_ledger_id,refund_ledger.amount AS ledger_amount
            FROM support_activity a
            JOIN support_conversations src ON src.id=a.conversation_id
            LEFT JOIN support_conversations origin ON origin.id=a.conversation_id
            LEFT JOIN support_messages sm ON a.source_type='SUPPORT_MESSAGE' AND sm.id=a.source_id
            LEFT JOIN after_sale_messages am ON a.source_type='AFTER_SALE_MESSAGE' AND am.id=a.source_id
            LEFT JOIN supplier_complaint_events e ON a.source_type='SUPPLIER_UPDATE' AND e.id=a.source_id
            LEFT JOIN supplier_aftercare_operations op ON op.id=e.operation_id
            LEFT JOIN LATERAL (SELECT id FROM after_sale_requests refunded
                WHERE refunded.order_id=origin.order_id AND refunded.status='REFUNDED'
                ORDER BY refunded.handled_at DESC NULLS LAST,refunded.id DESC LIMIT 1) refunded
              ON a.source_type='ORDER_REFUND'
            LEFT JOIN after_sale_requests r ON r.id=CASE
              WHEN a.source_type='SUPPORT_MESSAGE' THEN sm.after_sale_request_id
              WHEN a.source_type IN ('AFTER_SALE_APPLY','AFTER_SALE_REJECT') THEN a.source_id
              WHEN a.source_type='AFTER_SALE_MESSAGE' THEN am.request_id
              WHEN a.source_type='SUPPLIER_UPDATE' THEN op.after_sale_id
              WHEN a.source_type='ORDER_REFUND' THEN refunded.id ELSE NULL END
            LEFT JOIN support_system_events se ON a.source_type='SYSTEM_RESULT' AND se.id=a.source_id
            LEFT JOIN orders target_order ON target_order.id=coalesce(sm.order_id,origin.order_id,r.order_id,se.order_id)
            LEFT JOIN LATERAL (SELECT ledger.id,ledger.amount FROM balance_transactions ledger
                WHERE ledger.order_id=target_order.id AND ledger.type='REFUND'
                ORDER BY ledger.created_at DESC,ledger.id DESC LIMIT 1) refund_ledger
              ON a.source_type='ORDER_REFUND'
            """;

    private final JdbcTemplate jdbc;

    private record Identity(UUID userId, byte[] guestHash) {}
    private record Thread(UUID id, UUID siteId, UUID userId, byte[] guestHash) {}

    @Transactional(readOnly = true)
    public Map<String, Object> customerSummary(String token) {
        Identity identity = customer(token);
        Thread thread = findCustomerRoot(identity);
        if (thread == null) {
            Map<String, Object> empty = new LinkedHashMap<>(); empty.put("unread", 0L); empty.put("latest", null); return empty;
        }
        Map<String, Object> latest = summary(thread, false, null, identity);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("unread", latest.get("unread")); result.put("latest", latest);
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(UUID id, boolean admin, String token) {
        if (admin) return summary(authorizedRoot(id, true, token), true, RequestContext.getUserId(), null);
        List<Map<String, Object>> origins = jdbc.query("""
                SELECT c.id,c.site_id,c.user_id,c.guest_token_hash,c.order_id,o.order_no::text AS order_no
                FROM support_conversations c LEFT JOIN orders o ON o.id=c.order_id WHERE c.id=?
                """, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getObject("id", UUID.class)); row.put("site_id", rs.getObject("site_id", UUID.class));
            row.put("user_id", rs.getObject("user_id", UUID.class)); row.put("guest_hash", rs.getBytes("guest_token_hash"));
            row.put("order_id", rs.getObject("order_id", UUID.class)); row.put("order_no", rs.getString("order_no"));
            return row;
        }, id);
        if (origins.isEmpty()) throw notFound();
        Map<String, Object> origin = origins.getFirst();
        UUID orderId = (UUID) origin.get("order_id");
        if (orderId == null) return summary(authorizedRoot(id, false, token), false, null, customer(token));
        Identity identity = customer(token);
        UUID userId = (UUID) origin.get("user_id");
        byte[] guestHash = (byte[]) origin.get("guest_hash");
        boolean owner = identity.userId() != null ? identity.userId().equals(userId)
                : userId == null && guestHash != null && MessageDigest.isEqual(identity.guestHash(), guestHash);
        if (!owner) throw forbidden();
        UUID originSite = (UUID) origin.get("site_id");
        UUID currentSite = SiteRequestContext.getSiteId();
        if (!java.util.Objects.equals(currentSite, originSite)) {
            boolean disabledHistoricalSite = currentSite == null && originSite != null && Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT enabled=false FROM subsites WHERE id=?", Boolean.class, originSite));
            if (!disabledHistoricalSite) throw forbidden();
        }
        Thread root = jdbc.query("SELECT id,site_id,user_id,guest_token_hash FROM support_conversations "
                        + "WHERE site_scope=? AND order_id IS NULL AND user_id IS NOT DISTINCT FROM ? "
                        + "AND guest_token_hash IS NOT DISTINCT FROM ? ORDER BY created_at LIMIT 1",
                (rs, n) -> thread(rs), originSite == null ? MAIN_SCOPE : originSite, userId, guestHash)
                .stream().findFirst().orElseThrow(SupportTimelineService::notFound);
        Map<String, Object> result = summary(root, false, null, identity);
        result.put("order_id", orderId); result.put("order_no", origin.get("order_no"));
        List<Map<String, Object>> pending = jdbc.query("""
                SELECT r.id,r.submitted_at FROM after_sale_requests r WHERE r.order_id=? AND r.status='PENDING'
                ORDER BY r.submitted_at DESC,r.id DESC LIMIT 1
                """, (rs, n) -> Map.<String, Object>of("request_id", rs.getObject("id", UUID.class),
                "round", saleRound(rs.getObject("id", UUID.class))), orderId);
        if (!pending.isEmpty()) { result.putAll(pending.getFirst()); }
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(boolean admin, String token, String siteFilter, String kind,
                                           boolean unreadOnly, boolean pendingOnly, String orderNo,
                                           UUID levelId, int page, int size) {
        return list(admin, token, siteFilter, kind, unreadOnly, pendingOnly, orderNo, levelId, page, size, null);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(boolean admin, String token, String siteFilter, String kind,
                                           boolean unreadOnly, boolean pendingOnly, String orderNo,
                                           UUID levelId, int page, int size, String searchTerm) {
        int boundedSize = Math.max(1, Math.min(size, 50));
        int boundedPage = Math.max(0, Math.min(page, 100_000));
        if (!admin) {
            Identity identity = customer(token);
            if (boundedPage > 0) return List.of();
            Thread root = findCustomerRoot(identity);
            return root == null ? List.of() : List.of(summary(root, false, null, identity));
        }
        requireAdmin();
        UUID scope = null;
        if (siteFilter != null && !siteFilter.isBlank()) {
            try { scope = "main".equals(siteFilter) ? MAIN_SCOPE : UUID.fromString(siteFilter); }
            catch (IllegalArgumentException error) { throw bad("站点筛选无效"); }
        }
        if (kind != null && !kind.isBlank() && !Set.of("all", "consultation", "after_sale").contains(kind))
            throw bad("会话筛选无效");
        String normalizedOrder = orderNo == null ? "" : orderNo.trim();
        String normalizedSearch = searchTerm == null ? "" : searchTerm.trim();
        if (normalizedSearch.length() > 120) throw bad("搜索内容不能超过 120 个字符");
        long offset = (long) boundedPage * boundedSize;
        List<Thread> candidates = adminRootBatch(scope, kind, normalizedOrder, normalizedSearch, levelId,
                unreadOnly, pendingOnly, RequestContext.getUserId(), Math.toIntExact(Math.min(offset, Integer.MAX_VALUE)), boundedSize);
        return adminSummaryBatch(candidates, RequestContext.getUserId());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> activities(UUID id, boolean admin, String token, Long after, Long before, int size) {
        Thread thread = authorizedRoot(id, admin, token);
        if ((after != null && before != null) || (after != null && after < 0) || (before != null && before < 1))
            throw bad("消息游标无效");
        int boundedSize = Math.max(1, Math.min(size, 50));
        Identity identity = admin ? null : customer(token);
        Long firstUnread = after == null && before == null && !admin ? firstUnread(thread, identity) : null;
        boolean ascending = after != null || (before == null && firstUnread != null);
        String condition = "";
        List<Object> extra = new ArrayList<>();
        if (after != null) { condition = " AND a.seq>?"; extra.add(after); }
        else if (before != null) { condition = " AND a.seq<?"; extra.add(before); }
        else if (firstUnread != null) { condition = " AND a.seq>=?"; extra.add(firstUnread); }
        String direction = ascending ? "ASC" : "DESC";
        List<Map<String, Object>> rows = queryActivities(thread, admin, identity, condition, extra,
                boundedSize + (firstUnread == null || before != null ? 1 : 0), direction);
        boolean more;
        if (firstUnread != null && before == null && after == null) {
            Long firstSeq = rows.isEmpty() ? firstUnread : Long.parseLong(rows.getFirst().get("cursor").toString());
            more = existsActivityBefore(thread, admin, identity, firstSeq);
        } else {
            more = rows.size() > boundedSize;
        }
        if (rows.size() > boundedSize) rows.removeLast();
        if (!ascending) java.util.Collections.reverse(rows);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", rows); result.put("has_more", more);
        result.put("oldest_cursor", rows.isEmpty() ? null : rows.getFirst().get("cursor"));
        result.put("newest_cursor", rows.isEmpty() ? null : rows.getLast().get("cursor"));
        result.put("first_unread_cursor", firstUnread == null ? null : Long.toString(firstUnread));
        return result;
    }

    @Transactional
    public void markRead(UUID id, boolean admin, String token, List<String> cursors) {
        Thread thread = authorizedRoot(id, admin, token);
        if (cursors == null || cursors.isEmpty()) return;
        if (cursors.size() > 50) throw bad("已读范围过大");
        List<Long> seqs = new ArrayList<>();
        try { for (String cursor : cursors) seqs.add(Long.parseLong(cursor)); }
        catch (RuntimeException error) { throw bad("消息游标无效"); }
        if (seqs.stream().anyMatch(seq -> seq < 1)) throw bad("消息游标无效");
        Identity identity = admin ? null : customer(token);
        List<Object> args = new ArrayList<>();
        String scope = sourceScope(thread, admin, identity, args);
        String activityScope = activityScope(admin, args);
        args.add(bigintArray(seqs));
        String sql = "SELECT a.conversation_id,a.seq FROM support_activity a JOIN support_conversations src ON src.id=a.conversation_id "
                + "WHERE " + scope + activityScope + " AND a.seq=ANY(?::bigint[]) AND a."
                + (admin ? "notify_admin" : "notify_customer") + " AND NOT a.legacy";
        List<Object[]> visible = jdbc.query(sql, (rs, n) -> new Object[]{rs.getObject(1, UUID.class), rs.getLong(2)}, args.toArray());
        UUID reader = admin ? RequestContext.getUserId() : null;
        for (Object[] row : visible) {
            jdbc.update("""
                    INSERT INTO support_activity_receipts(conversation_id,seq,viewer_type,viewer_user_id)
                    VALUES (?,?,?,?) ON CONFLICT DO NOTHING
                    """, row[0], row[1], admin ? "ADMIN" : "CUSTOMER", reader);
        }
    }

    private Map<String, Object> summary(Thread thread, boolean admin, UUID adminId, Identity identity) {
        List<Map<String, Object>> metadata = jdbc.query("""
                SELECT c.site_id,s.name AS site_name,c.user_id,u.username,u.user_level_id,l.name AS level_name
                FROM support_conversations c LEFT JOIN subsites s ON s.id=c.site_id
                LEFT JOIN users u ON u.id=c.user_id LEFT JOIN user_levels l ON l.id=u.user_level_id
                WHERE c.id=? AND c.order_id IS NULL
                """, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("site_id", rs.getObject("site_id", UUID.class)); row.put("site_name", rs.getString("site_name"));
            row.put("customer_name", rs.getString("username")); row.put("level_name", rs.getString("level_name"));
            row.put("user_id", rs.getObject("user_id", UUID.class)); return row;
        }, thread.id());
        if (metadata.isEmpty()) throw notFound();
        Map<String, Object> meta = metadata.getFirst();
        Long latestSeq = latestSequence(thread, admin, identity);
        Map<String, Object> latest = latestSeq == null ? null : queryActivities(thread, admin, identity,
                " AND a.seq=?", List.of(latestSeq), 1, "DESC").stream().findFirst().orElse(null);
        long unread = unreadCount(thread, admin, adminId, identity);
        List<Map<String, Object>> pendingSales = pendingSales(thread, admin, identity);
        boolean waitingAdmin = pendingSales.stream().anyMatch(row -> "ADMIN".equals(row.get("waiting_for")));
        boolean waitingCustomer = pendingSales.stream().anyMatch(row -> "CUSTOMER".equals(row.get("waiting_for")));
        boolean lastCustomerMessage = latestSupportSender(thread, admin, identity).map("CUSTOMER"::equals).orElse(false);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", thread.id()); row.put("site_id", meta.get("site_id")); row.put("site_name", meta.get("site_name"));
        if (admin) {
            row.put("customer_name", meta.get("customer_name") == null ? "游客" : meta.get("customer_name"));
            row.put("user_id", meta.get("user_id"));
        }
        row.put("customer_level_name", meta.get("level_name"));
        row.put("order_id", null); row.put("order_no", null); row.put("order_status", null);
        row.put("latest_at", latest == null ? null : latest.get("created_at"));
        row.put("latest_cursor", latest == null ? null : latest.get("cursor"));
        row.put("latest_type", latest == null ? null : latest.get("source_type"));
        row.put("preview", latest == null ? "" : preview(latest));
        row.put("unread", unread);
        row.put("waiting_for", waitingAdmin ? "ADMIN" : waitingCustomer ? "CUSTOMER" : null);
        row.put("needs_attention", waitingAdmin || (pendingSales.isEmpty() && lastCustomerMessage));
        row.put("pending_after_sales", admin ? pendingSales : List.of());
        return row;
    }

    /** One summary query plus one pending-case query for an admin list batch. */
    private List<Map<String, Object>> adminSummaryBatch(List<Thread> threads, UUID adminId) {
        if (threads.isEmpty()) return List.of();
        UUID[] ids = threads.stream().map(Thread::id).toArray(UUID[]::new);
        String idParameters = String.join(",", Collections.nCopies(ids.length, "?"));
        List<Object> summaryArgs = new ArrayList<>(ids.length + 2);
        summaryArgs.add(adminId); summaryArgs.add(adminId);
        for (UUID id : ids) summaryArgs.add(id);
        Map<UUID, Map<String, Object>> base = new LinkedHashMap<>();
        jdbc.query("""
                SELECT root.id,root.site_id,site.name AS site_name,root.user_id,buyer.username,level.name AS level_name,
                  latest.seq,latest.happened_at,latest.source_type,latest.preview_content,latest.event_type,
                  latest.event_amount,latest.direction,latest.balance_after,latest.target_level_name,latest.order_no,
                  latest.refund_amount,latest.actual_amount,latest.ledger_id,latest.has_attachments,last_message.sender_type AS support_sender,
                  unread.count AS unread
                FROM support_conversations root
                LEFT JOIN subsites site ON site.id=root.site_id
                LEFT JOIN users buyer ON buyer.id=root.user_id
                LEFT JOIN user_levels level ON level.id=buyer.user_level_id
                LEFT JOIN LATERAL (
                  SELECT a.seq,a.happened_at,a.source_type,
                    CASE a.source_type WHEN 'SUPPORT_MESSAGE' THEN sm.content WHEN 'AFTER_SALE_MESSAGE' THEN am.content
                      WHEN 'AFTER_SALE_APPLY' THEN request.reason WHEN 'AFTER_SALE_REJECT' THEN request.rejection_reason
                      WHEN 'SUPPLIER_UPDATE' THEN supplier.reply ELSE NULL END AS preview_content,
                    event.event_type,event.amount AS event_amount,event.direction,event.balance_after,
                    event.target_level_name,order_row.order_no::text AS order_no,
                    refund_ledger.amount AS refund_amount,order_row.actual_amount,coalesce(event.balance_transaction_id,refund_ledger.id) AS ledger_id,
                    CASE a.source_type
                      WHEN 'SUPPORT_MESSAGE' THEN EXISTS(SELECT 1 FROM support_attachments x WHERE x.message_id=sm.id)
                      WHEN 'AFTER_SALE_MESSAGE' THEN EXISTS(SELECT 1 FROM after_sale_attachments x WHERE x.message_id=am.id)
                      WHEN 'AFTER_SALE_APPLY' THEN EXISTS(SELECT 1 FROM after_sale_attachments x WHERE x.request_id=request.id AND x.message_id IS NULL)
                      WHEN 'SUPPLIER_UPDATE' THEN EXISTS(SELECT 1 FROM supplier_complaint_attachments x WHERE x.event_id=supplier.id)
                      ELSE false END AS has_attachments,
                    sm.sender_type AS support_sender
                  FROM support_activity a
                  JOIN support_conversations source ON source.id=a.conversation_id
                  LEFT JOIN support_messages sm ON a.source_type='SUPPORT_MESSAGE' AND sm.id=a.source_id
                  LEFT JOIN after_sale_messages am ON a.source_type='AFTER_SALE_MESSAGE' AND am.id=a.source_id
                  LEFT JOIN supplier_complaint_events supplier ON a.source_type='SUPPLIER_UPDATE' AND supplier.id=a.source_id
                  LEFT JOIN supplier_aftercare_operations operation ON operation.id=supplier.operation_id
                  LEFT JOIN LATERAL (SELECT id FROM after_sale_requests refunded
                    WHERE refunded.order_id=source.order_id AND refunded.status='REFUNDED'
                    ORDER BY refunded.handled_at DESC NULLS LAST,refunded.id DESC LIMIT 1) refund_request
                    ON a.source_type='ORDER_REFUND'
                  LEFT JOIN after_sale_requests request ON request.id=CASE
                    WHEN a.source_type='SUPPORT_MESSAGE' THEN sm.after_sale_request_id
                    WHEN a.source_type IN ('AFTER_SALE_APPLY','AFTER_SALE_REJECT') THEN a.source_id
                    WHEN a.source_type='AFTER_SALE_MESSAGE' THEN am.request_id
                    WHEN a.source_type='SUPPLIER_UPDATE' THEN operation.after_sale_id
                    WHEN a.source_type='ORDER_REFUND' THEN refund_request.id ELSE NULL END
                  LEFT JOIN support_system_events event ON a.source_type='SYSTEM_RESULT' AND event.id=a.source_id
                  LEFT JOIN orders order_row ON order_row.id=coalesce(sm.order_id,source.order_id,request.order_id,event.order_id)
                  LEFT JOIN LATERAL (SELECT ledger.id,ledger.amount FROM balance_transactions ledger
                    WHERE ledger.order_id=order_row.id AND ledger.type='REFUND'
                    ORDER BY ledger.created_at DESC,ledger.id DESC LIMIT 1) refund_ledger ON a.source_type='ORDER_REFUND'
                  WHERE source.site_scope=root.site_scope AND source.user_id IS NOT DISTINCT FROM root.user_id
                    AND source.guest_token_hash IS NOT DISTINCT FROM root.guest_token_hash
                  ORDER BY a.seq DESC LIMIT 1
                ) latest ON true
                LEFT JOIN LATERAL (
                  SELECT count(*) AS count FROM support_activity a
                  JOIN support_conversations source ON source.id=a.conversation_id
                  LEFT JOIN support_read_states read_state ON read_state.conversation_id=a.conversation_id
                    AND read_state.viewer_type='ADMIN' AND read_state.viewer_user_id=?
                  WHERE source.site_scope=root.site_scope AND source.user_id IS NOT DISTINCT FROM root.user_id
                    AND source.guest_token_hash IS NOT DISTINCT FROM root.guest_token_hash
                    AND a.notify_admin AND NOT a.legacy AND a.seq>coalesce(read_state.read_seq,0)
                    AND NOT EXISTS (SELECT 1 FROM support_activity_receipts receipt
                      WHERE receipt.conversation_id=a.conversation_id AND receipt.seq=a.seq
                        AND receipt.viewer_type='ADMIN' AND receipt.reader_key=?::uuid)
                ) unread ON true
                LEFT JOIN LATERAL (
                  SELECT message.sender_type FROM support_activity activity
                  JOIN support_conversations source ON source.id=activity.conversation_id
                  JOIN support_messages message ON activity.source_type='SUPPORT_MESSAGE' AND message.id=activity.source_id
                  WHERE source.site_scope=root.site_scope AND source.user_id IS NOT DISTINCT FROM root.user_id
                    AND source.guest_token_hash IS NOT DISTINCT FROM root.guest_token_hash
                  ORDER BY activity.seq DESC LIMIT 1
                ) last_message ON true
                WHERE root.id IN (""" + idParameters + ") AND root.order_id IS NULL",
                (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> {
            while (rs.next()) {
                UUID id = rs.getObject("id", UUID.class);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", id); row.put("site_id", rs.getObject("site_id", UUID.class));
                row.put("site_name", rs.getString("site_name")); row.put("customer_name", rs.getString("username") == null ? "游客" : rs.getString("username"));
                row.put("user_id", rs.getObject("user_id", UUID.class)); row.put("customer_level_name", rs.getString("level_name"));
                row.put("order_id", null); row.put("order_no", null); row.put("order_status", null);
                long seq = rs.getLong("seq"); boolean hasLatest = !rs.wasNull();
                row.put("latest_at", hasLatest ? timestamp(rs, "happened_at") : null);
                row.put("latest_cursor", hasLatest ? Long.toString(seq) : null);
                String sourceType = rs.getString("source_type"); row.put("latest_type", sourceType);
                Map<String, Object> latest = null;
                if (hasLatest) {
                    latest = new LinkedHashMap<>(); latest.put("source_type", sourceType);
                    latest.put("content", rs.getString("preview_content")); latest.put("order_no", rs.getString("order_no"));
                    latest.put("refund_amount", rs.getBigDecimal("refund_amount")); latest.put("event_type", rs.getString("event_type"));
                    latest.put("amount", rs.getBigDecimal("event_amount")); latest.put("direction", rs.getString("direction"));
                    latest.put("balance_after", rs.getBigDecimal("balance_after")); latest.put("level_name", rs.getString("target_level_name"));
                    latest.put("has_attachments", rs.getBoolean("has_attachments"));
                }
                row.put("preview", latest == null ? "" : preview(latest));
                row.put("unread", rs.getLong("unread"));
                row.put("needs_attention", false); row.put("waiting_for", null); row.put("pending_after_sales", new ArrayList<Map<String, Object>>());
                row.put("_last_support_sender", hasLatest ? rs.getString("support_sender") : null);
                base.put(id, row);
            }
            return null;
        }, summaryArgs.toArray());

        Map<UUID, List<Map<String, Object>>> pendingByRoot = new LinkedHashMap<>();
        jdbc.query("""
                SELECT root.id AS root_id,r.id AS request_id,o.id AS order_id,o.order_no::text AS order_no,
                  r.waiting_for,r.submitted_at,
                  (SELECT count(*) FROM after_sale_requests current JOIN after_sale_requests earlier
                    ON earlier.order_id=current.order_id AND (earlier.submitted_at,earlier.id)<=(current.submitted_at,current.id)
                    WHERE current.id=r.id) AS round
                FROM support_conversations root
                JOIN LATERAL (
                  SELECT source.order_id FROM support_conversations source
                    WHERE source.site_scope=root.site_scope AND source.user_id IS NOT DISTINCT FROM root.user_id
                      AND source.guest_token_hash IS NOT DISTINCT FROM root.guest_token_hash AND source.order_id IS NOT NULL
                  UNION SELECT message.order_id FROM support_messages message
                    JOIN support_conversations source ON source.id=message.conversation_id
                    WHERE source.site_scope=root.site_scope AND source.user_id IS NOT DISTINCT FROM root.user_id
                      AND source.guest_token_hash IS NOT DISTINCT FROM root.guest_token_hash AND message.order_id IS NOT NULL
                  UNION SELECT event.order_id FROM support_system_events event
                    JOIN support_conversations source ON source.id=event.conversation_id
                    WHERE source.site_scope=root.site_scope AND source.user_id IS NOT DISTINCT FROM root.user_id
                      AND source.guest_token_hash IS NOT DISTINCT FROM root.guest_token_hash AND event.order_id IS NOT NULL
                  UNION SELECT request.order_id FROM support_activity activity
                    JOIN support_conversations source ON source.id=activity.conversation_id
                    LEFT JOIN after_sale_messages sale_message ON activity.source_type='AFTER_SALE_MESSAGE' AND sale_message.id=activity.source_id
                    LEFT JOIN supplier_complaint_events supplier ON activity.source_type='SUPPLIER_UPDATE' AND supplier.id=activity.source_id
                    LEFT JOIN supplier_aftercare_operations operation ON operation.id=supplier.operation_id
                    JOIN after_sale_requests request ON request.id=CASE
                      WHEN activity.source_type IN ('AFTER_SALE_APPLY','AFTER_SALE_REJECT') THEN activity.source_id
                      WHEN activity.source_type='AFTER_SALE_MESSAGE' THEN sale_message.request_id
                      WHEN activity.source_type='SUPPLIER_UPDATE' THEN operation.after_sale_id ELSE NULL END
                    WHERE source.site_scope=root.site_scope AND source.user_id IS NOT DISTINCT FROM root.user_id
                      AND source.guest_token_hash IS NOT DISTINCT FROM root.guest_token_hash
                ) linked_order ON true
                JOIN after_sale_requests r ON r.order_id=linked_order.order_id
                JOIN orders o ON o.id=r.order_id
                WHERE root.id IN (""" + idParameters + ") AND root.order_id IS NULL AND r.status='PENDING'\n"
                + "GROUP BY root.id,r.id,o.id,r.waiting_for,r.submitted_at\n"
                + "ORDER BY root.id,r.submitted_at,r.id",
                (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> {
            while (rs.next()) {
                UUID rootId = rs.getObject("root_id", UUID.class);
                Map<String, Object> sale = new LinkedHashMap<>();
                sale.put("request_id", rs.getObject("request_id", UUID.class)); sale.put("order_id", rs.getObject("order_id", UUID.class));
                sale.put("order_no", rs.getString("order_no")); sale.put("waiting_for", rs.getString("waiting_for"));
                sale.put("round", rs.getInt("round")); sale.put("submitted_at", timestamp(rs, "submitted_at"));
                pendingByRoot.computeIfAbsent(rootId, ignored -> new ArrayList<>()).add(sale);
            }
            return null;
        }, (Object[]) ids);

        List<Map<String, Object>> result = new ArrayList<>(threads.size());
        for (Thread thread : threads) {
            Map<String, Object> row = base.get(thread.id());
            if (row == null) continue;
            @SuppressWarnings("unchecked") List<Map<String, Object>> pendingSales = pendingByRoot.getOrDefault(thread.id(), List.of());
            boolean waitingAdmin = pendingSales.stream().anyMatch(sale -> "ADMIN".equals(sale.get("waiting_for")));
            boolean waitingCustomer = pendingSales.stream().anyMatch(sale -> "CUSTOMER".equals(sale.get("waiting_for")));
            boolean lastCustomerMessage = "CUSTOMER".equals(row.remove("_last_support_sender"));
            row.put("waiting_for", waitingAdmin ? "ADMIN" : waitingCustomer ? "CUSTOMER" : null);
            row.put("needs_attention", waitingAdmin || (pendingSales.isEmpty() && lastCustomerMessage));
            row.put("pending_after_sales", pendingSales);
            result.add(row);
        }
        return result;
    }

    private List<Map<String, Object>> queryActivities(Thread thread, boolean admin, Identity identity,
                                                       String condition, List<Object> extra, int limit, String direction) {
        List<Object> args = new ArrayList<>();
        String scope = sourceScope(thread, admin, identity, args);
        String activityScope = activityScope(admin, args);
        args.addAll(extra); args.add(limit);
        String sql = ACTIVITY_SELECT + " WHERE " + scope + activityScope + condition + " ORDER BY a.seq " + direction + " LIMIT ?";
        return jdbc.query(sql, (rs, n) -> activity(rs, admin), args.toArray());
    }

    private Map<String, Object> activity(ResultSet rs, boolean admin) throws SQLException {
        String type = rs.getString("source_type"); UUID sourceId = rs.getObject("source_id", UUID.class);
        UUID requestId = rs.getObject("request_id", UUID.class);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", type + ":" + sourceId); row.put("cursor", Long.toString(rs.getLong("seq")));
        row.put("source_type", type); row.put("created_at", timestamp(rs, "happened_at")); row.put("request_id", requestId);
        row.put("client_key", switch (type) {
            case "SUPPORT_MESSAGE" -> rs.getObject("support_key", UUID.class);
            case "AFTER_SALE_MESSAGE" -> rs.getObject("sale_key", UUID.class);
            default -> null;
        });
        row.put("sender_type", switch (type) {
            case "SUPPORT_MESSAGE" -> rs.getString("support_sender");
            case "AFTER_SALE_MESSAGE" -> rs.getString("sale_sender");
            default -> "SYSTEM";
        });
        row.put("content", switch (type) {
            case "SUPPORT_MESSAGE" -> rs.getString("support_content");
            case "AFTER_SALE_MESSAGE" -> rs.getString("sale_content");
            case "AFTER_SALE_APPLY" -> rs.getString("reason");
            case "AFTER_SALE_REJECT" -> rs.getString("rejection_reason");
            case "SUPPLIER_UPDATE" -> rs.getString("supplier_reply");
            default -> null;
        });
        row.put("order_id", rs.getObject("target_order_id", UUID.class));
        row.put("order_no", rs.getString("order_no")); row.put("order_status", rs.getString("order_status"));
        if (requestId != null) row.put("round", saleRound(requestId));
        if ("ORDER_REFUND".equals(type)) {
            BigDecimal amount = rs.getBigDecimal("ledger_amount");
            BigDecimal actual = rs.getBigDecimal("actual_amount");
            if (amount == null && actual != null && actual.signum() == 0) amount = actual;
            row.put("refund_amount", amount);
            row.put("refund_destination", amount == null ? null : amount.signum() == 0 ? "NO_PAYMENT" : "BALANCE");
            row.put("ledger_id", rs.getObject("refund_ledger_id", UUID.class));
        }
        if ("SUPPLIER_UPDATE".equals(type)) row.put("supplier_status", rs.getInt("supplier_status"));
        if ("SYSTEM_RESULT".equals(type)) {
            row.put("event_type", rs.getString("event_type")); row.put("amount", rs.getBigDecimal("event_amount"));
            row.put("direction", rs.getString("direction")); row.put("ledger_id", rs.getObject("balance_transaction_id", UUID.class));
            row.put("balance_after", rs.getBigDecimal("balance_after")); row.put("level_name", rs.getString("target_level_name"));
        }
        UUID noticeId = rs.getObject("source_announcement_id", UUID.class);
        if (noticeId != null) {
            row.put("notice_id", noticeId);
            // A withdrawn or newly restricted notice title is not copied into
            // the support transcript. The user's message itself is retained.
            row.put("notice_title", "商城通知");
        }
        row.put("attachments", activityAttachments(type, sourceId, requestId, admin));
        return row;
    }

    private List<Map<String, Object>> activityAttachments(String type, UUID sourceId, UUID requestId, boolean admin) {
        String query; String prefix; Object key;
        switch (type) {
            case "SUPPORT_MESSAGE" -> {
                query = "SELECT id,content_type,file_size FROM support_attachments WHERE message_id=? ORDER BY id";
                prefix = admin ? "/api/admin/support/attachments/" : "/api/support/attachments/"; key = sourceId;
            }
            case "AFTER_SALE_MESSAGE" -> {
                query = "SELECT id,content_type,file_size FROM after_sale_attachments WHERE message_id=? ORDER BY created_at,id";
                prefix = "/api/after-sale-attachments/"; key = sourceId;
            }
            case "AFTER_SALE_APPLY" -> {
                query = "SELECT id,content_type,file_size FROM after_sale_attachments WHERE request_id=? AND message_id IS NULL ORDER BY created_at,id";
                prefix = "/api/after-sale-attachments/"; key = requestId;
            }
            case "SUPPLIER_UPDATE" -> {
                query = "SELECT id,content_type,file_size FROM supplier_complaint_attachments WHERE event_id=? ORDER BY id";
                prefix = "/api/supplier-complaint-attachments/"; key = sourceId;
            }
            default -> { return List.of(); }
        }
        return jdbc.query(query, (rs, n) -> Map.<String, Object>of(
                "id", rs.getObject("id", UUID.class), "content_type", rs.getString("content_type"),
                "file_size", rs.getLong("file_size"), "url", prefix + rs.getObject("id", UUID.class)), key);
    }

    private List<Map<String, Object>> pendingSales(Thread thread, boolean admin, Identity identity) {
        List<Object> args = new ArrayList<>();
        StringBuilder linked = new StringBuilder();
        List<Object> scopeArgs = new ArrayList<>();
        String scope = sourceScope(thread, admin, identity, scopeArgs);
        linked.append("SELECT src.order_id FROM support_conversations src WHERE ").append(scope).append(" AND src.order_id IS NOT NULL");
        args.addAll(scopeArgs); scopeArgs.clear();
        scope = sourceScope(thread, admin, identity, scopeArgs);
        linked.append(" UNION SELECT message.order_id FROM support_messages message JOIN support_conversations src ON src.id=message.conversation_id WHERE ")
                .append(scope).append(" AND message.order_id IS NOT NULL");
        args.addAll(scopeArgs); scopeArgs.clear();
        scope = sourceScope(thread, admin, identity, scopeArgs);
        linked.append(" UNION SELECT event.order_id FROM support_system_events event JOIN support_conversations src ON src.id=event.conversation_id WHERE ")
                .append(scope).append(" AND event.order_id IS NOT NULL");
        args.addAll(scopeArgs);
        scopeArgs.clear();
        scope = sourceScope(thread, admin, identity, scopeArgs);
        linked.append(" UNION SELECT request.order_id FROM support_activity activity JOIN support_conversations src ON src.id=activity.conversation_id ")
                .append("LEFT JOIN after_sale_messages sale_message ON activity.source_type='AFTER_SALE_MESSAGE' AND sale_message.id=activity.source_id ")
                .append("LEFT JOIN supplier_complaint_events supplier ON activity.source_type='SUPPLIER_UPDATE' AND supplier.id=activity.source_id ")
                .append("LEFT JOIN supplier_aftercare_operations operation ON operation.id=supplier.operation_id ")
                .append("JOIN after_sale_requests request ON request.id=CASE WHEN activity.source_type IN ('AFTER_SALE_APPLY','AFTER_SALE_REJECT') THEN activity.source_id ")
                .append("WHEN activity.source_type='AFTER_SALE_MESSAGE' THEN sale_message.request_id WHEN activity.source_type='SUPPLIER_UPDATE' THEN operation.after_sale_id ELSE NULL END WHERE ")
                .append(scope);
        args.addAll(scopeArgs);
        return jdbc.query("""
                SELECT DISTINCT r.id AS request_id,o.id AS order_id,o.order_no::text AS order_no,r.waiting_for,r.submitted_at,
                  (SELECT count(*) FROM after_sale_requests current JOIN after_sale_requests earlier
                    ON earlier.order_id=current.order_id AND (earlier.submitted_at,earlier.id)<=(current.submitted_at,current.id)
                    WHERE current.id=r.id) AS round
                FROM (""" + linked + ") linked_order JOIN after_sale_requests r ON r.order_id=linked_order.order_id\n"
                + "JOIN orders o ON o.id=r.order_id WHERE r.status='PENDING' ORDER BY r.submitted_at,r.id",
                (rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("request_id", rs.getObject("request_id", UUID.class)); row.put("order_id", rs.getObject("order_id", UUID.class));
                    row.put("order_no", rs.getString("order_no")); row.put("waiting_for", rs.getString("waiting_for"));
                    row.put("round", rs.getInt("round")); row.put("submitted_at", timestamp(rs, "submitted_at")); return row;
                }, args.toArray());
    }

    private Long latestSequence(Thread thread, boolean admin, Identity identity) {
        List<Object> args = new ArrayList<>(); String scope = sourceScope(thread, admin, identity, args);
        String activityScope = activityScope(admin, args);
        return jdbc.queryForObject("SELECT max(a.seq) FROM support_activity a JOIN support_conversations src ON src.id=a.conversation_id WHERE " + scope + activityScope,
                Long.class, args.toArray());
    }

    private long unreadCount(Thread thread, boolean admin, UUID adminId, Identity identity) {
        List<Object> args = new ArrayList<>();
        String readType = admin ? "ADMIN" : "CUSTOMER";
        UUID reader = admin ? adminId : MAIN_SCOPE;
        args.add(readType); args.add(reader); args.add(reader);
        String scope = sourceScope(thread, admin, identity, args);
        String activityScope = activityScope(admin, args);
        args.add(readType); args.add(reader);
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM support_activity a JOIN support_conversations src ON src.id=a.conversation_id
                LEFT JOIN support_read_states r ON r.conversation_id=a.conversation_id AND r.viewer_type=?
                  AND ((?::uuid='00000000-0000-0000-0000-000000000000'::uuid AND r.viewer_user_id IS NULL)
                    OR r.viewer_user_id=?::uuid)
                WHERE """ + " " + scope + activityScope + " AND a.notify_" + (admin ? "admin" : "customer") + " AND NOT a.legacy"
                + " AND a.seq>coalesce(r.read_seq,0) AND NOT EXISTS (SELECT 1 FROM support_activity_receipts receipt "
                + "WHERE receipt.conversation_id=a.conversation_id AND receipt.seq=a.seq AND receipt.viewer_type=? "
                + "AND receipt.reader_key=?::uuid)", Long.class, args.toArray());
        return count == null ? 0L : count;
    }

    private Long firstUnread(Thread thread, Identity identity) {
        List<Object> args = new ArrayList<>();
        args.add("CUSTOMER");
        String scope = sourceScope(thread, false, identity, args);
        String activityScope = activityScope(false, args);
        args.add("CUSTOMER"); args.add(MAIN_SCOPE);
        return jdbc.queryForObject("""
                SELECT min(a.seq) FROM support_activity a JOIN support_conversations src ON src.id=a.conversation_id
                LEFT JOIN support_read_states r ON r.conversation_id=a.conversation_id AND r.viewer_type=? AND r.viewer_user_id IS NULL
                WHERE """ + " " + scope + activityScope + " AND a.notify_customer AND NOT a.legacy AND a.seq>coalesce(r.read_seq,0)"
                + " AND NOT EXISTS (SELECT 1 FROM support_activity_receipts receipt WHERE receipt.conversation_id=a.conversation_id"
                + " AND receipt.seq=a.seq AND receipt.viewer_type=? AND receipt.reader_key=?::uuid)", Long.class, args.toArray());
    }

    private boolean existsActivityBefore(Thread thread, boolean admin, Identity identity, long seq) {
        List<Object> args = new ArrayList<>(); String scope = sourceScope(thread, admin, identity, args);
        String activityScope = activityScope(admin, args); args.add(seq);
        return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM support_activity a JOIN support_conversations src ON src.id=a.conversation_id WHERE "
                + scope + activityScope + " AND a.seq<?)", Boolean.class, args.toArray());
    }

    private java.util.Optional<String> latestSupportSender(Thread thread, boolean admin, Identity identity) {
        List<Object> args = new ArrayList<>(); String scope = sourceScope(thread, admin, identity, args);
        String activityScope = activityScope(admin, args);
        List<String> rows = jdbc.query(ACTIVITY_SELECT + " WHERE " + scope + activityScope + " AND a.source_type='SUPPORT_MESSAGE' ORDER BY a.seq DESC LIMIT 1",
                (rs, n) -> rs.getString("support_sender"), args.toArray());
        return rows.stream().findFirst();
    }

    private List<Thread> customerRoots(Identity identity) {
        UUID current = SiteRequestContext.getSiteId(); List<Object> args = new ArrayList<>();
        String owner = identity.userId() != null ? "c.user_id=?" : "c.guest_token_hash=?";
        args.add(identity.userId() != null ? identity.userId() : identity.guestHash());
        args.add(current == null ? MAIN_SCOPE : current);
        String sites = current == null ? "(c.site_scope=? OR (c.site_id IS NOT NULL AND EXISTS (SELECT 1 FROM subsites dormant WHERE dormant.id=c.site_id AND dormant.enabled=false)) "
                + "OR EXISTS (SELECT 1 FROM support_system_events account_event WHERE account_event.conversation_id=c.id "
                + "AND account_event.source_type IN ('ACCOUNT_PAYMENT','BALANCE_TRANSACTION')) )"
                : "c.site_scope=?";
        if (current == null) args.set(1, MAIN_SCOPE);
        return jdbc.query("SELECT c.id,c.site_id,c.user_id,c.guest_token_hash FROM support_conversations c WHERE c.order_id IS NULL AND "
                        + owner + " AND " + sites + " ORDER BY (c.site_id IS NULL) DESC,c.created_at DESC",
                (rs, n) -> thread(rs), args.toArray());
    }

    private Thread findCustomerRoot(Identity identity) {
        List<Thread> roots = customerRoots(identity);
        return roots.isEmpty() ? null : roots.getFirst();
    }

    private List<Thread> adminRootBatch(UUID siteScope, String kind, String orderNo, String searchTerm, UUID levelId,
                                        boolean unreadOnly, boolean pendingOnly, UUID adminId, int offset, int limit) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE root.order_id IS NULL ");
        if (siteScope != null) { where.append(" AND root.site_scope=? "); args.add(siteScope); }
        if (levelId != null) { where.append(" AND root.user_id IS NOT NULL AND buyer.user_level_id=? "); args.add(levelId); }
        String linkedOrders = "SELECT x.order_id FROM support_conversations x WHERE x.site_scope=root.site_scope AND x.user_id IS NOT DISTINCT FROM root.user_id AND x.guest_token_hash IS NOT DISTINCT FROM root.guest_token_hash AND x.order_id IS NOT NULL "
                + "UNION SELECT m.order_id FROM support_messages m JOIN support_conversations x ON x.id=m.conversation_id WHERE x.site_scope=root.site_scope AND x.user_id IS NOT DISTINCT FROM root.user_id AND x.guest_token_hash IS NOT DISTINCT FROM root.guest_token_hash AND m.order_id IS NOT NULL "
                + "UNION SELECT e.order_id FROM support_system_events e JOIN support_conversations x ON x.id=e.conversation_id WHERE x.site_scope=root.site_scope AND x.user_id IS NOT DISTINCT FROM root.user_id AND x.guest_token_hash IS NOT DISTINCT FROM root.guest_token_hash AND e.order_id IS NOT NULL "
                + "UNION SELECT request.order_id FROM support_activity activity JOIN support_conversations x ON x.id=activity.conversation_id "
                + "LEFT JOIN after_sale_messages sale_message ON activity.source_type='AFTER_SALE_MESSAGE' AND sale_message.id=activity.source_id "
                + "LEFT JOIN supplier_complaint_events supplier ON activity.source_type='SUPPLIER_UPDATE' AND supplier.id=activity.source_id "
                + "LEFT JOIN supplier_aftercare_operations operation ON operation.id=supplier.operation_id "
                + "JOIN after_sale_requests request ON request.id=CASE WHEN activity.source_type IN ('AFTER_SALE_APPLY','AFTER_SALE_REJECT') THEN activity.source_id "
                + "WHEN activity.source_type='AFTER_SALE_MESSAGE' THEN sale_message.request_id WHEN activity.source_type='SUPPLIER_UPDATE' THEN operation.after_sale_id ELSE NULL END "
                + "WHERE x.site_scope=root.site_scope AND x.user_id IS NOT DISTINCT FROM root.user_id AND x.guest_token_hash IS NOT DISTINCT FROM root.guest_token_hash";
        if (!orderNo.isBlank()) {
            where.append(" AND EXISTS (SELECT 1 FROM (" + linkedOrders + ") linked_order JOIN orders searched_order ON searched_order.id=linked_order.order_id WHERE searched_order.order_no::text ILIKE ?) ");
            args.add("%" + orderNo + "%");
        }
        if (!searchTerm.isBlank()) {
            where.append(" AND ((buyer.username ILIKE ? OR buyer.email ILIKE ? OR buyer.user_no::text ILIKE ?) "
                    + "OR EXISTS (SELECT 1 FROM (" + linkedOrders + ") linked_order JOIN orders searched_order ON searched_order.id=linked_order.order_id "
                    + "WHERE searched_order.order_no::text ILIKE ?)) ");
            String value = "%" + searchTerm + "%";
            args.add(value); args.add(value); args.add(value); args.add(value);
        }
        if (unreadOnly) {
            where.append(" AND EXISTS (SELECT 1 FROM support_activity unread_activity "
                    + "JOIN support_conversations unread_source ON unread_source.id=unread_activity.conversation_id "
                    + "LEFT JOIN support_read_states read_state ON read_state.conversation_id=unread_activity.conversation_id "
                    + "AND read_state.viewer_type='ADMIN' AND read_state.viewer_user_id=? "
                    + "WHERE unread_source.site_scope=root.site_scope AND unread_source.user_id IS NOT DISTINCT FROM root.user_id "
                    + "AND unread_source.guest_token_hash IS NOT DISTINCT FROM root.guest_token_hash "
                    + "AND unread_activity.notify_admin AND NOT unread_activity.legacy AND unread_activity.seq>coalesce(read_state.read_seq,0) "
                    + "AND NOT EXISTS (SELECT 1 FROM support_activity_receipts receipt WHERE receipt.conversation_id=unread_activity.conversation_id "
                    + "AND receipt.seq=unread_activity.seq AND receipt.viewer_type='ADMIN' AND receipt.reader_key=?::uuid)) ");
            args.add(adminId); args.add(adminId);
        }
        if (pendingOnly) {
            where.append(" AND (EXISTS (SELECT 1 FROM (" + linkedOrders + ") linked_order "
                    + "JOIN after_sale_requests request ON request.order_id=linked_order.order_id "
                    + "WHERE request.status='PENDING' AND request.waiting_for='ADMIN') "
                    + "OR (NOT EXISTS (SELECT 1 FROM (" + linkedOrders + ") linked_order "
                    + "JOIN after_sale_requests request ON request.order_id=linked_order.order_id WHERE request.status='PENDING') "
                    + "AND (SELECT message.sender_type FROM support_activity activity "
                    + "JOIN support_conversations source ON source.id=activity.conversation_id "
                    + "JOIN support_messages message ON activity.source_type='SUPPORT_MESSAGE' AND message.id=activity.source_id "
                    + "WHERE source.site_scope=root.site_scope AND source.user_id IS NOT DISTINCT FROM root.user_id "
                    + "AND source.guest_token_hash IS NOT DISTINCT FROM root.guest_token_hash ORDER BY activity.seq DESC LIMIT 1)='CUSTOMER')) ");
        }
        if ("after_sale".equals(kind)) where.append(" AND EXISTS (SELECT 1 FROM (" + linkedOrders + ") linked_order JOIN after_sale_requests r ON r.order_id=linked_order.order_id) ");
        if ("consultation".equals(kind)) where.append(" AND EXISTS (SELECT 1 FROM support_conversations x JOIN support_messages m ON m.conversation_id=x.id WHERE x.site_scope=root.site_scope AND x.user_id IS NOT DISTINCT FROM root.user_id AND x.guest_token_hash IS NOT DISTINCT FROM root.guest_token_hash) ");
        args.add(limit); args.add(offset);
        return jdbc.query("""
                SELECT root.id,root.site_id,root.user_id,root.guest_token_hash,max(a.seq) AS latest_seq
                FROM support_conversations root
                LEFT JOIN users buyer ON buyer.id=root.user_id
                JOIN support_conversations src ON src.site_scope=root.site_scope
                  AND src.user_id IS NOT DISTINCT FROM root.user_id
                  AND src.guest_token_hash IS NOT DISTINCT FROM root.guest_token_hash
                JOIN support_activity a ON a.conversation_id=src.id
                """ + where + " GROUP BY root.id,root.site_id,root.user_id,root.guest_token_hash"
                + " ORDER BY max(a.seq) DESC,root.id LIMIT ? OFFSET ?",
                (rs, n) -> thread(rs), args.toArray());
    }

    private Thread authorizedRoot(UUID id, boolean admin, String token) {
        if (admin) requireAdmin();
        List<Thread> rows = jdbc.query("SELECT id,site_id,user_id,guest_token_hash FROM support_conversations WHERE id=? AND order_id IS NULL",
                (rs, n) -> thread(rs), id);
        if (rows.isEmpty()) throw notFound();
        Thread thread = rows.getFirst();
        if (admin) return thread;
        Identity identity = customer(token);
        boolean owner = identity.userId() != null ? identity.userId().equals(thread.userId())
                : thread.userId() == null && thread.guestHash() != null && MessageDigest.isEqual(identity.guestHash(), thread.guestHash());
        if (!owner) throw forbidden();
        UUID currentSite = SiteRequestContext.getSiteId();
        if (java.util.Objects.equals(currentSite, thread.siteId())) return thread;
        if (currentSite == null && thread.siteId() != null && Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT enabled=false FROM subsites WHERE id=?", Boolean.class, thread.siteId()))) return thread;
        throw forbidden();
    }

    private String sourceScope(Thread thread, boolean admin, Identity identity, List<Object> args) {
        if (admin) {
            args.add(thread.siteId() == null ? MAIN_SCOPE : thread.siteId());
            if (thread.userId() != null) { args.add(thread.userId()); return "src.site_scope=? AND src.user_id=?"; }
            args.add(thread.guestHash()); return "src.site_scope=? AND src.guest_token_hash=?";
        }
        Identity owner = identity == null ? customer(null) : identity;
        String ownerClause;
        if (owner.userId() != null) { args.add(owner.userId()); ownerClause = "src.user_id=?"; }
        else { args.add(owner.guestHash()); ownerClause = "src.guest_token_hash=?"; }
        UUID current = SiteRequestContext.getSiteId();
        args.add(current == null ? MAIN_SCOPE : current);
        if (current != null) return ownerClause + " AND src.site_scope=?";
        return ownerClause + " AND (src.site_scope=? OR (src.site_id IS NOT NULL AND EXISTS "
                + "(SELECT 1 FROM subsites dormant WHERE dormant.id=src.site_id AND dormant.enabled=false)))";
    }

    /** On the main site, dormant-site order history and the user's account results cross the site boundary. */
    private String activityScope(boolean admin, List<Object> args) {
        if (admin || SiteRequestContext.getSiteId() != null) return "";
        args.add(MAIN_SCOPE);
        return " AND (src.site_scope=? OR (src.site_id IS NOT NULL AND EXISTS "
                + "(SELECT 1 FROM subsites dormant WHERE dormant.id=src.site_id AND dormant.enabled=false) "
                + "AND (src.order_id IS NOT NULL OR (a.source_type='SYSTEM_RESULT' AND EXISTS "
                + "(SELECT 1 FROM support_system_events account_event WHERE account_event.id=a.source_id "
                + "AND account_event.source_type IN ('ACCOUNT_PAYMENT','BALANCE_TRANSACTION'))) OR "
                + "(a.source_type='SUPPORT_MESSAGE' AND EXISTS (SELECT 1 FROM support_messages contextual_message "
                + "WHERE contextual_message.id=a.source_id AND contextual_message.order_id IS NOT NULL)))))";
    }

    private static Thread thread(ResultSet rs) throws SQLException {
        return new Thread(rs.getObject("id", UUID.class), rs.getObject("site_id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getBytes("guest_token_hash"));
    }

    private static LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column); return value == null ? null : value.toLocalDateTime();
    }

    private static String preview(Map<String, Object> latest) {
        if ("ORDER_REFUND".equals(latest.get("source_type"))) {
            Object refundValue = latest.get("refund_amount");
            if (refundValue instanceof BigDecimal money) {
                if (money.signum() == 0) return "订单 #" + latest.getOrDefault("order_no", "") + " 已关闭";
                return "订单 #" + latest.getOrDefault("order_no", "") + " 已退款 ¥"
                        + money.setScale(2, RoundingMode.HALF_UP).toPlainString() + "，款项已退回账户余额";
            }
            return "退款处理结果";
        }
        Object content = latest.get("content");
        if (content instanceof String text && !text.isBlank()) {
            return text.length() > 160 ? text.substring(0, 160) : text;
        }
        if (Boolean.TRUE.equals(latest.get("has_attachments"))) return "[图片]";
        if (!((List<?>) latest.getOrDefault("attachments", List.of())).isEmpty()) return "[图片]";
        String event = (String) latest.get("event_type");
        BigDecimal amount = latest.get("amount") instanceof BigDecimal value ? value : null;
        String money = amount == null ? "" : " ¥" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
        if ("RECHARGE_CREDITED".equals(event)) return "充值到账" + money;
        if ("LEVEL_UPGRADE_COMPLETED".equals(event)) return "等级升级成功：" + latest.getOrDefault("level_name", "会员等级");
        if ("LEVEL_UPGRADE_RECOVERED".equals(event)) return "等级升级未完成，金额已转入余额" + money;
        if ("BALANCE_ADJUSTMENT".equals(event)) return ("CREDIT".equals(latest.get("direction")) ? "余额增加" : "余额扣减") + money;
        if ("ORDER_DELIVERED".equals(event)) return "订单 #" + latest.getOrDefault("order_no", "") + " 已完成发货";
        return switch ((String) latest.get("source_type")) {
            case "AFTER_SALE_APPLY" -> "申请售后";
            case "AFTER_SALE_REJECT" -> "售后已处理";
            case "ORDER_REFUND" -> "退款处理结果";
            case "SUPPLIER_UPDATE" -> "上游处理更新";
            default -> "";
        };
    }

    private long saleRound(UUID requestId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM after_sale_requests current JOIN after_sale_requests earlier
                  ON earlier.order_id=current.order_id AND (earlier.submitted_at,earlier.id)<=(current.submitted_at,current.id)
                WHERE current.id=?
                """, Long.class, requestId);
    }

    private static String bigintArray(List<Long> values) { return "{" + String.join(",", values.stream().map(String::valueOf).toList()) + "}"; }

    private Identity customer(String token) {
        UUID user = RequestContext.getUserId();
        if (user != null) return new Identity(user, null);
        String value = token == null ? "" : token.trim();
        if (value.isEmpty() || value.length() > 255) throw forbidden();
        try { return new Identity(null, MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private void requireAdmin() {
        if (SiteRequestContext.getSiteId() != null || RequestContext.getUserId() == null
                || !Set.of("ADMIN", "SUPER_ADMIN").contains(RequestContext.getRole())) throw forbidden();
    }

    private static BusinessException bad(String message) { return new BusinessException(ErrorCode.BAD_REQUEST, message); }
    private static BusinessException forbidden() { return new BusinessException(ErrorCode.FORBIDDEN, "无权访问此会话"); }
    private static BusinessException notFound() { return new BusinessException(ErrorCode.NOT_FOUND, "会话或消息不存在"); }
}
