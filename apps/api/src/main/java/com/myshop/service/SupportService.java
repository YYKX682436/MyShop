package com.myshop.service;

import com.myshop.constant.ErrorCode;
import com.myshop.context.RequestContext;
import com.myshop.context.SiteRequestContext;
import com.myshop.entity.Order;
import com.myshop.exception.BusinessException;
import com.myshop.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Private support messages and a cursor-indexed view over the existing sale history. */
@Service
@RequiredArgsConstructor
public class SupportService {
    private final JdbcTemplate jdbc;
    private final OrderRepository orders;
    private final AfterSaleAttachmentService attachments;
    private final PrivateUploadStorage storage;
    private final AfterSaleService afterSales;
    private final AnnouncementService announcements;
    private static final UUID MAIN_SCOPE = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private record Identity(UUID userId, byte[] guestHash, String token) {}
    private record Conversation(UUID id, UUID siteId, UUID userId, byte[] guestHash, UUID orderId) {}
    public record Download(org.springframework.core.io.Resource resource, String contentType, long size, String filename) {}

    @Transactional
    public Map<String, Object> orderContext(UUID orderId, String token) {
        return orderContext(orderId, null, null, token);
    }

    @Transactional
    public Map<String, Object> orderContext(UUID orderId, UUID requestId, Integer round, String token) {
        Identity identity = customer(token);
        Order order = authorizedOrder(orderId, identity);
        UUID siteId = SiteRequestContext.getSiteId();
        Conversation root = getOrCreate(siteId, identity, null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conversation_id", root.id());
        result.put("order_id", order.getId());
        result.put("order_no", Long.toString(order.getOrderNo()));
        result.put("order_status", order.getStatus().name());
        if (requestId != null) {
            result.put("request_id", requestId); result.put("round", round);
        }
        return result;
    }

    @Transactional
    public Map<String, Object> generalContext(String token) {
        Identity identity = customer(token);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conversation_id", getOrCreate(SiteRequestContext.getSiteId(), identity, null).id());
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> adminOrderContext(UUID orderId) {
        requireAdmin();
        if (!orders.existsById(orderId)) throw notFound();
        var rows = jdbc.query("""
                SELECT root.id FROM support_conversations order_thread
                JOIN support_conversations root ON root.site_scope=order_thread.site_scope
                  AND root.order_id IS NULL AND root.user_id IS NOT DISTINCT FROM order_thread.user_id
                  AND root.guest_token_hash IS NOT DISTINCT FROM order_thread.guest_token_hash
                WHERE order_thread.order_id=? ORDER BY order_thread.created_at LIMIT 1
                """,
                (rs, n) -> rs.getObject(1, UUID.class), orderId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conversation_id", rows.isEmpty() ? null : rows.getFirst());
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(UUID id, boolean admin, String token) {
        Conversation c = authorizedConversation(id, admin, token);
        return conversationSummary(c.id(), admin, admin ? RequestContext.getUserId() : null);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(boolean admin, String token, String siteFilter, String kind,
                                          boolean unreadOnly, boolean pendingOnly, int page, int size) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE true ");
        if (admin) {
            requireAdmin();
            if (siteFilter != null && !siteFilter.isBlank()) {
                UUID scope;
                try { scope = "main".equals(siteFilter) ? MAIN_SCOPE : UUID.fromString(siteFilter); }
                catch (IllegalArgumentException e) { throw bad("站点筛选无效"); }
                where.append(" AND c.site_scope=? "); args.add(scope);
            }
            if ("after_sale".equals(kind)) where.append(" AND EXISTS (SELECT 1 FROM after_sale_requests ar WHERE ar.order_id=c.order_id) ");
            else if ("consultation".equals(kind)) where.append(" AND (c.order_id IS NULL OR EXISTS (SELECT 1 FROM support_messages sm WHERE sm.conversation_id=c.id)) ");
            else if (kind != null && !kind.isBlank() && !"all".equals(kind)) throw bad("会话筛选无效");
        } else {
            Identity identity = customer(token);
            UUID siteId = SiteRequestContext.getSiteId();
            if (identity.userId() != null) {
                where.append(" AND c.user_id=? AND (c.site_scope=? OR (? AND c.order_id IS NOT NULL AND o.user_id=c.user_id)) ");
                args.add(identity.userId()); args.add(siteId == null ? MAIN_SCOPE : siteId); args.add(siteId == null);
            } else {
                where.append(" AND c.guest_token_hash=? AND c.site_scope=? ");
                args.add(identity.guestHash()); args.add(siteId == null ? MAIN_SCOPE : siteId);
            }
        }
        int boundedSize = Math.max(1, Math.min(size, 50));
        int boundedPage = Math.max(0, Math.min(page, 100_000));
        return summaries(where.toString(), args, admin, admin ? RequestContext.getUserId() : null,
                unreadOnly, pendingOnly, boundedSize, boundedPage * boundedSize);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> customerSummary(String token) {
        Identity identity = customer(token);
        UUID siteId = SiteRequestContext.getSiteId();
        String owner; Object[] args;
        if (identity.userId() != null) {
            owner = "c.user_id=? AND (c.site_scope=? OR (? AND c.order_id IS NOT NULL AND o.user_id=c.user_id))";
            args = new Object[]{identity.userId(), siteId == null ? MAIN_SCOPE : siteId, siteId == null};
        } else {
            owner = "c.guest_token_hash=? AND c.site_scope=?";
            args = new Object[]{identity.guestHash(), siteId == null ? MAIN_SCOPE : siteId};
        }
        Long unread = jdbc.queryForObject("""
                SELECT count(*) FROM support_activity a JOIN support_conversations c ON c.id=a.conversation_id
                LEFT JOIN orders o ON o.id=c.order_id
                LEFT JOIN support_read_states r ON r.conversation_id=c.id AND r.viewer_type='CUSTOMER'
                WHERE a.notify_customer AND NOT a.legacy AND a.seq>coalesce(r.read_seq,0) AND
                """ + owner, Long.class, args);
        var latest = list(false, token, null, null, false, false, 0, 1);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("unread", unread); result.put("latest", latest.isEmpty() ? null : latest.getFirst());
        return result;
    }

    private Map<String, Object> conversationSummary(UUID id, boolean admin, UUID adminId) {
        var rows = summaries(" WHERE c.id=? ", List.of(id), admin, adminId, false, false, 1, 0);
        if (rows.isEmpty()) throw notFound();
        return rows.getFirst();
    }

    private List<Map<String, Object>> summaries(String where, List<Object> args, boolean admin, UUID adminId,
                                                boolean unreadOnly, boolean pendingOnly, int limit, int offset) {
        String viewerJoin = admin ? "rr.viewer_type='ADMIN' AND rr.viewer_user_id=?" : "rr.viewer_type='CUSTOMER'";
        String notifyColumn = admin ? "notify_admin" : "notify_customer";
        String sql = """
                SELECT * FROM (
                  SELECT c.id,c.site_id,s.name AS site_name,c.order_id,o.order_no,o.status AS order_status,
                    CASE WHEN c.user_id IS NULL THEN '游客' ELSE buyer.username END AS customer_name,
                    latest.seq AS latest_seq,latest.happened_at AS latest_at,latest.source_type AS latest_type,
                    CASE WHEN latest.source_type='SUPPORT_MESSAGE' AND btrim(sm.content)=''
                           AND EXISTS (SELECT 1 FROM support_attachments attachment WHERE attachment.message_id=sm.id)
                      THEN '[图片]' ELSE coalesce(sm.content,am.content,CASE latest.source_type
                        WHEN 'AFTER_SALE_APPLY' THEN '申请售后' WHEN 'AFTER_SALE_REJECT' THEN '售后已处理'
                        WHEN 'ORDER_REFUND' THEN '退款成功' WHEN 'SUPPLIER_UPDATE' THEN '上游处理更新' ELSE '' END)
                    END AS preview,
                    (SELECT count(*) FROM support_activity a WHERE a.conversation_id=c.id AND NOT a.legacy
                      AND a.seq>coalesce(rr.read_seq,0) AND a.""" + notifyColumn + """
                    ) AS unread,
                    pending.waiting_for,
                    CASE WHEN pending.id IS NOT NULL THEN pending.waiting_for='ADMIN'
                      ELSE coalesce(last_support.sender_type='CUSTOMER',false) END AS needs_attention
                  FROM support_conversations c
                  LEFT JOIN subsites s ON s.id=c.site_id
                  LEFT JOIN users buyer ON buyer.id=c.user_id
                  LEFT JOIN orders o ON o.id=c.order_id
                  LEFT JOIN support_read_states rr ON rr.conversation_id=c.id AND
                """ + viewerJoin + "\n" + """
                  LEFT JOIN LATERAL (SELECT a.seq,a.source_type,a.source_id,a.happened_at
                    FROM support_activity a WHERE a.conversation_id=c.id ORDER BY a.seq DESC LIMIT 1) latest ON true
                  LEFT JOIN support_messages sm ON latest.source_type='SUPPORT_MESSAGE' AND sm.id=latest.source_id
                  LEFT JOIN after_sale_messages am ON latest.source_type='AFTER_SALE_MESSAGE' AND am.id=latest.source_id
                  LEFT JOIN LATERAL (SELECT r.id,r.waiting_for FROM after_sale_requests r
                    WHERE r.order_id=c.order_id AND r.status='PENDING' ORDER BY r.submitted_at DESC LIMIT 1) pending ON true
                  LEFT JOIN LATERAL (
                    SELECT message.sender_type FROM support_activity activity
                    JOIN support_messages message ON message.id=activity.source_id
                    WHERE activity.conversation_id=c.id AND activity.source_type='SUPPORT_MESSAGE'
                    ORDER BY activity.seq DESC LIMIT 1
                  ) last_support ON true
                """ + where + """
                ) rows WHERE true
                """ + (unreadOnly ? " AND unread>0 " : "")
                + (pendingOnly ? " AND needs_attention " : "")
                + " ORDER BY latest_seq DESC NULLS LAST,id DESC LIMIT ? OFFSET ?";
        List<Object> bindings = new ArrayList<>();
        if (admin) bindings.add(adminId);
        bindings.addAll(args); bindings.add(limit); bindings.add(offset);
        return jdbc.query(sql, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getObject("id", UUID.class));
            row.put("site_id", rs.getObject("site_id", UUID.class));
            row.put("site_name", rs.getString("site_name"));
            if (admin) row.put("customer_name", rs.getString("customer_name"));
            row.put("order_id", rs.getObject("order_id", UUID.class));
            row.put("order_no", rs.getString("order_no"));
            row.put("order_status", rs.getString("order_status"));
            var latest = rs.getTimestamp("latest_at");
            row.put("latest_at", latest == null ? null : latest.toLocalDateTime());
            row.put("latest_type", rs.getString("latest_type"));
            row.put("preview", rs.getString("preview"));
            row.put("unread", rs.getLong("unread"));
            row.put("waiting_for", rs.getString("waiting_for"));
            row.put("needs_attention", rs.getBoolean("needs_attention"));
            return row;
        }, bindings.toArray());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> activities(UUID conversationId, boolean admin, String token,
                                          Long after, Long before, int size) {
        Conversation conversation = authorizedConversation(conversationId, admin, token);
        if ((after != null && before != null) || (after != null && after < 0) || (before != null && before < 1))
            throw bad("消息游标无效");
        int boundedSize = Math.max(1, Math.min(size, 50));
        boolean ascending = after != null;
        String condition = after != null ? " AND a.seq>? " : before != null ? " AND a.seq<? " : "";
        List<Object> args = new ArrayList<>(); args.add(conversation.id());
        if (after != null) args.add(after); else if (before != null) args.add(before);
        args.add(boundedSize + 1);
        String sql = """
                SELECT a.seq,a.source_type,a.source_id,a.happened_at,
                  sm.content AS support_content,sm.sender_type AS support_sender,sm.client_key AS support_key,
                  am.content AS sale_content,am.sender_type AS sale_sender,am.client_key AS sale_key,
                  r.id AS request_id,r.reason,r.rejection_reason,r.submitted_at,
                  e.status AS supplier_status,e.reply AS supplier_reply,
                  o.actual_amount,ledger.amount AS ledger_amount,o.status AS order_status,o.order_no
                FROM support_activity a
                JOIN support_conversations c ON c.id=a.conversation_id
                LEFT JOIN orders o ON o.id=c.order_id
                LEFT JOIN balance_transactions ledger ON ledger.order_id=o.id AND ledger.type='REFUND' AND a.source_type='ORDER_REFUND'
                LEFT JOIN support_messages sm ON a.source_type='SUPPORT_MESSAGE' AND sm.id=a.source_id
                LEFT JOIN after_sale_messages am ON a.source_type='AFTER_SALE_MESSAGE' AND am.id=a.source_id
                LEFT JOIN supplier_complaint_events e ON a.source_type='SUPPLIER_UPDATE' AND e.id=a.source_id
                LEFT JOIN supplier_aftercare_operations op ON op.id=e.operation_id
                LEFT JOIN LATERAL (SELECT id FROM after_sale_requests refunded
                    WHERE refunded.order_id=c.order_id AND refunded.status='REFUNDED'
                    ORDER BY refunded.handled_at DESC NULLS LAST,refunded.id DESC LIMIT 1) refunded ON a.source_type='ORDER_REFUND'
                LEFT JOIN after_sale_requests r ON r.id=CASE WHEN a.source_type IN ('AFTER_SALE_APPLY','AFTER_SALE_REJECT')
                  THEN a.source_id WHEN a.source_type='AFTER_SALE_MESSAGE' THEN am.request_id
                  WHEN a.source_type='SUPPLIER_UPDATE' THEN op.after_sale_id WHEN a.source_type='ORDER_REFUND' THEN refunded.id ELSE NULL END
                WHERE a.conversation_id=?
                """ + condition + " ORDER BY a.seq " + (ascending ? "ASC" : "DESC") + " LIMIT ?";
        List<Map<String, Object>> rows = jdbc.query(sql, (rs, n) -> activity(rs, admin), args.toArray());
        boolean hasMore = rows.size() > boundedSize;
        if (hasMore) rows.removeLast();
        if (!ascending) java.util.Collections.reverse(rows);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", rows);
        result.put("has_more", hasMore);
        result.put("oldest_cursor", rows.isEmpty() ? null : rows.getFirst().get("cursor"));
        result.put("newest_cursor", rows.isEmpty() ? null : rows.getLast().get("cursor"));
        return result;
    }

    private Map<String, Object> activity(ResultSet rs, boolean admin) throws SQLException {
        String type = rs.getString("source_type");
        UUID id = rs.getObject("source_id", UUID.class);
        UUID requestId = rs.getObject("request_id", UUID.class);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", type + ":" + id);
        row.put("cursor", Long.toString(rs.getLong("seq")));
        row.put("source_type", type);
        var happened = rs.getTimestamp("happened_at");
        row.put("created_at", happened == null ? null : happened.toLocalDateTime());
        row.put("request_id", requestId);
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
        if (requestId != null) {
            row.put("round", jdbc.queryForObject("""
                    SELECT count(*) FROM after_sale_requests current JOIN after_sale_requests earlier
                      ON earlier.order_id=current.order_id AND (earlier.submitted_at,earlier.id)<=(current.submitted_at,current.id)
                    WHERE current.id=?
                    """, Integer.class, requestId));
        }
        if ("ORDER_REFUND".equals(type)) {
            var amount = rs.getBigDecimal("ledger_amount");
            var actualAmount = rs.getBigDecimal("actual_amount");
            if (amount == null && actualAmount != null && actualAmount.signum() == 0) amount = actualAmount;
            row.put("refund_amount", amount);
            row.put("refund_destination", amount == null ? null : amount.signum() == 0 ? "NO_PAYMENT" : "BALANCE");
            row.put("order_status", rs.getString("order_status"));
            row.put("order_no", rs.getString("order_no"));
        }
        if ("SUPPLIER_UPDATE".equals(type)) row.put("supplier_status", rs.getInt("supplier_status"));
        row.put("attachments", activityAttachments(type, id, requestId, admin));
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
        return jdbc.query(query, (rs, n) -> {
            UUID id = rs.getObject("id", UUID.class);
            return Map.<String, Object>of("id", id, "content_type", rs.getString("content_type"),
                    "file_size", rs.getLong("file_size"), "url", prefix + id);
        }, key);
    }

    @Transactional
    public void markRead(UUID conversationId, boolean admin, String token, long cursor) {
        Conversation conversation = authorizedConversation(conversationId, admin, token);
        if (cursor < 1 || jdbc.queryForObject("SELECT count(*) FROM support_activity WHERE conversation_id=? AND seq=?",
                Integer.class, conversation.id(), cursor) != 1) throw bad("消息游标无效");
        if (admin) {
            jdbc.update("""
                    INSERT INTO support_read_states(conversation_id,viewer_type,viewer_user_id,read_seq) VALUES (?,'ADMIN',?,?)
                    ON CONFLICT (conversation_id,viewer_user_id) WHERE viewer_type='ADMIN'
                    DO UPDATE SET read_seq=greatest(support_read_states.read_seq,excluded.read_seq),
                      read_at=CASE WHEN excluded.read_seq>support_read_states.read_seq THEN CURRENT_TIMESTAMP ELSE support_read_states.read_at END
                    """, conversation.id(), RequestContext.getUserId(), cursor);
        } else {
            jdbc.update("""
                    INSERT INTO support_read_states(conversation_id,viewer_type,read_seq) VALUES (?,'CUSTOMER',?)
                    ON CONFLICT (conversation_id) WHERE viewer_type='CUSTOMER'
                    DO UPDATE SET read_seq=greatest(support_read_states.read_seq,excluded.read_seq),
                      read_at=CASE WHEN excluded.read_seq>support_read_states.read_seq THEN CURRENT_TIMESTAMP ELSE support_read_states.read_at END
                    """, conversation.id(), cursor);
        }
    }

    @Transactional(readOnly = true)
    public Download openAttachment(UUID id, boolean admin, String token) {
        var rows = jdbc.query("""
                SELECT a.storage_key,a.content_type,a.file_size,a.original_filename,m.conversation_id
                FROM support_attachments a JOIN support_messages m ON m.id=a.message_id WHERE a.id=?
                """, (rs, n) -> new Object[]{rs.getString(1),rs.getString(2),rs.getLong(3),rs.getString(4),rs.getObject(5, UUID.class)}, id);
        if (rows.isEmpty()) throw notFound();
        Object[] row = rows.getFirst();
        authorizedConversation((UUID) row[4], admin, token);
        String mime = (String) row[1]; long size = (Long) row[2];
        if (!Set.of("image/jpeg", "image/png", "image/webp", "image/gif", "image/svg+xml").contains(mime)
                || size <= 0 || size > AfterSaleAttachmentService.MAX_ATTACHMENT_SIZE) throw notFound();
        try {
            var path = storage.resolveForRead((String) row[0]);
            if (Files.size(path) != size) throw notFound();
            return new Download(new InputStreamResource(Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)), mime, size, (String) row[3]);
        } catch (IOException | IllegalArgumentException | SecurityException error) { throw notFound(); }
    }

    @Transactional
    public Map<String, Object> send(UUID conversationId, UUID orderId, UUID sourceAnnouncementId,
                                    UUID clientKey, String content, List<MultipartFile> files,
                                    boolean admin, String token) {
        if (clientKey == null) throw bad("缺少消息幂等键");
        String normalized = content == null ? "" : content.trim();
        if (normalized.codePointCount(0, normalized.length()) > 1000) throw bad("消息不能超过 1000 字");
        Identity identity = admin ? null : customer(token);
        if (sourceAnnouncementId != null) {
            if (admin || announcements == null) throw forbidden();
            announcements.publicDetail(SiteRequestContext.getSiteId(), sourceAnnouncementId, token);
        }
        Conversation conversation;
        if (conversationId != null) {
            conversation = authorizedConversation(conversationId, admin, token);
        } else {
            if (admin) throw bad("请选择会话");
            if (orderId != null) authorizedOrder(orderId, identity);
            List<AfterSaleAttachmentService.ValidatedAttachment> validated = attachments.validate(files);
            if (normalized.isEmpty() && validated.isEmpty()) throw bad("消息不能为空");
            conversation = getOrCreate(SiteRequestContext.getSiteId(), identity, null);
            return persistMessage(conversation, clientKey, normalized, validated, false, orderId, sourceAnnouncementId);
        }
        if (!admin && orderId != null) authorizedOrder(orderId, identity);
        List<AfterSaleAttachmentService.ValidatedAttachment> validated = attachments.validate(files);
        if (normalized.isEmpty() && validated.isEmpty()) throw bad("消息不能为空");
        return persistMessage(conversation, clientKey, normalized, validated, admin, orderId, sourceAnnouncementId);
    }

    /** Backward-compatible internal entry point for callers that do not attach a notice reference. */
    @Transactional
    public Map<String, Object> send(UUID conversationId, UUID orderId, UUID clientKey, String content,
                                    List<MultipartFile> files, boolean admin, String token) {
        return send(conversationId, orderId, null, clientKey, content, files, admin, token);
    }

    public void replyAfterSale(UUID conversationId, UUID clientKey, String content,
                               UUID orderId, UUID requestId, Integer round,
                               List<MultipartFile> files, boolean admin, String token) {
        Conversation conversation = authorizedConversation(conversationId, admin, token);
        UUID targetOrder = orderId == null ? conversation.orderId() : orderId;
        if (targetOrder == null || requestId == null || round == null) throw bad("请选择具体售后申请");
        if (!admin) authorizedOrder(targetOrder, customer(token));
        else if (!orders.existsById(targetOrder)) throw notFound();
        if (admin) afterSales.replyFromAdminWithContext(targetOrder, requestId, round, content, files, clientKey);
        else afterSales.replyFromCustomerWithContext(targetOrder, requestId, round, RequestContext.getUserId(), content, files, clientKey);
    }

    private Map<String, Object> persistMessage(Conversation conversation, UUID key, String content,
                                              List<AfterSaleAttachmentService.ValidatedAttachment> files,
                                              boolean admin, UUID orderReference, UUID sourceAnnouncementId) {
        UUID orderId = orderReference == null ? conversation.orderId() : orderReference;
        String context = "order=" + (orderId == null ? "" : orderId) + "\u0000notice="
                + (sourceAnnouncementId == null ? "" : sourceAnnouncementId);
        byte[] fingerprint = orderReference == null && sourceAnnouncementId == null
                ? MessageFingerprint.calculate(content, files)
                : MessageFingerprint.calculate(content, files, context);
        UUID sender = RequestContext.getUserId();
        String side = admin ? "ADMIN" : "CUSTOMER";
        if (orderId != null) {
            // Match the after-sale order-first lock. A concurrent application
            // or terminal action cannot race a normal support reply.
            orders.findByIdForUpdate(orderId).orElseThrow(SupportService::notFound);
            var retry = jdbc.query("SELECT id,request_fingerprint,sender_user_id FROM support_messages WHERE conversation_id=? AND sender_type=? AND client_key=?",
                    (rs, n) -> new Object[]{rs.getObject(1, UUID.class), rs.getBytes(2), rs.getObject(3, UUID.class)}, conversation.id(), side, key);
            if (!retry.isEmpty()) {
                Object[] previous = retry.getFirst();
                if (!MessageDigest.isEqual(fingerprint, (byte[]) previous[1]) || !Objects.equals(sender, previous[2]))
                    throw bad("幂等键已用于不同的消息");
                return Map.of("conversation_id", conversation.id(), "message_id", previous[0]);
            }
            var saleRetry = jdbc.query("SELECT id,request_fingerprint FROM after_sale_messages WHERE order_id=? AND sender_type=? AND sender_user_id=? AND client_key=?",
                    (rs, n) -> new Object[]{rs.getObject(1, UUID.class),rs.getBytes(2)}, orderId, side, sender, key);
            if (!saleRetry.isEmpty()) {
                Object[] previous = saleRetry.getFirst();
                if (!MessageDigest.isEqual(fingerprint, (byte[]) previous[1])) throw bad("幂等键已用于不同的消息");
                return Map.of("conversation_id", conversation.id(), "message_id", previous[0]);
            }
        }
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO support_messages(id,conversation_id,sender_type,sender_user_id,content,client_key,request_fingerprint,order_id,source_announcement_id)
                VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT (conversation_id,sender_type,client_key) DO NOTHING
                """, id, conversation.id(), side, sender, content, key, fingerprint, orderId, sourceAnnouncementId);
        if (inserted == 0) {
            var existing = jdbc.query("SELECT id,request_fingerprint,sender_user_id FROM support_messages WHERE conversation_id=? AND sender_type=? AND client_key=?",
                    (rs, n) -> new Object[]{rs.getObject(1, UUID.class), rs.getBytes(2), rs.getObject(3, UUID.class)}, conversation.id(), side, key);
            if (existing.size() != 1 || !MessageDigest.isEqual(fingerprint, (byte[]) existing.getFirst()[1])
                    || !Objects.equals(sender, existing.getFirst()[2])) throw bad("幂等键已用于不同的消息");
            id = (UUID) existing.getFirst()[0];
        } else {
            List<String> written = new ArrayList<>();
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        if (status != TransactionSynchronization.STATUS_COMMITTED) written.forEach(storage::deleteManagedFile);
                    }
                });
            }
            try {
                for (var file : files) {
                    UUID attachmentId = UUID.randomUUID();
                    String path = "support/" + conversation.id() + "/" + attachmentId + "." + file.extension();
                    storage.write(path, file.file()); written.add(path);
                    jdbc.update("INSERT INTO support_attachments(id,message_id,storage_key,original_filename,content_type,file_size) VALUES (?,?,?,?,?,?)",
                            attachmentId, id, path, file.originalFilename(), file.contentType(), file.file().getSize());
                }
            } catch (RuntimeException error) {
                written.forEach(storage::deleteManagedFile);
                throw error;
            }
        }
        return Map.of("conversation_id", conversation.id(), "message_id", id);
    }

    private Conversation getOrCreate(UUID siteId, Identity identity, UUID orderId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO support_conversations(id,site_id,user_id,guest_token_hash,order_id) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",
                id, siteId, identity.userId(), identity.guestHash(), orderId);
        UUID found = findConversation(siteId, identity, orderId);
        if (found == null) throw new IllegalStateException("support conversation was not created");
        return new Conversation(found, siteId, identity.userId(), identity.guestHash(), orderId);
    }

    private UUID findConversation(UUID siteId, Identity identity, UUID orderId) {
        String owner = identity.userId() == null ? "guest_token_hash=?" : "user_id=?";
        Object key = identity.userId() == null ? identity.guestHash() : identity.userId();
        return jdbc.query("SELECT id FROM support_conversations WHERE site_scope=? AND order_scope=? AND " + owner,
                (rs, n) -> rs.getObject(1, UUID.class), siteId == null ? MAIN_SCOPE : siteId,
                orderId == null ? MAIN_SCOPE : orderId, key).stream().findFirst().orElse(null);
    }

    private Order authorizedOrder(UUID orderId, Identity identity) {
        Order order = orders.findById(orderId).orElseThrow(SupportService::notFound);
        OrderOwnership.requireAccess(order, identity.userId(), identity.token());
        // A logged-in account never adopts a guest session's order or chat.
        if (identity.userId() != null && order.getUserId() == null) throw forbidden();
        return order;
    }

    private Conversation authorizedConversation(UUID id, boolean admin, String token) {
        if (admin) requireAdmin();
        var rows = jdbc.query("SELECT id,site_id,user_id,guest_token_hash,order_id FROM support_conversations WHERE id=?",
                (rs, n) -> new Conversation(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getBytes(4), rs.getObject(5, UUID.class)), id);
        if (rows.isEmpty()) throw notFound();
        Conversation c = rows.getFirst();
        if (admin) return c;
        Identity identity = customer(token);
        boolean owner = identity.userId() != null ? identity.userId().equals(c.userId())
                : c.userId() == null && c.guestHash() != null && MessageDigest.isEqual(identity.guestHash(), c.guestHash());
        if (!owner) throw forbidden();
        if (c.orderId() != null) {
            Order order = authorizedOrder(c.orderId(), identity);
            if (!Objects.equals(order.getSubsiteId(), c.siteId())) throw forbidden();
        } else if (!Objects.equals(SiteRequestContext.getSiteId(), c.siteId())) throw forbidden();
        return c;
    }

    private Identity customer(String token) {
        UUID user = RequestContext.getUserId();
        if (user != null) return new Identity(user, null, null);
        String clean = token == null ? "" : token.trim();
        if (clean.isEmpty() || clean.length() > 255) throw forbidden();
        try { return new Identity(null, MessageDigest.getInstance("SHA-256").digest(clean.getBytes(StandardCharsets.UTF_8)), clean); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private void requireAdmin() {
        if (SiteRequestContext.getSiteId() != null || RequestContext.getUserId() == null
                || !Set.of("ADMIN", "SUPER_ADMIN").contains(RequestContext.getRole())) throw forbidden();
    }


    private static BusinessException bad(String message) { return new BusinessException(ErrorCode.BAD_REQUEST, message); }
    private static BusinessException forbidden() { return new BusinessException(ErrorCode.FORBIDDEN, "无权访问此会话"); }
    private static BusinessException notFound() { return new BusinessException(ErrorCode.NOT_FOUND, "会话或订单不存在"); }
}
