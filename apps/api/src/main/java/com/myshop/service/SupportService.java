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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Customer conversation context, message persistence, and private attachment access. */
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
        // Keep the legacy guest order-thread as the verifiable identity-to-order
        // association while returning the customer's single root conversation.
        if (identity.userId() == null) getOrCreate(siteId, identity, orderId);
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
                SELECT root.id FROM support_conversations root
                WHERE root.order_id IS NULL AND EXISTS (
                  SELECT 1 FROM support_conversations source
                  WHERE source.site_scope=root.site_scope
                    AND source.user_id IS NOT DISTINCT FROM root.user_id
                    AND source.guest_token_hash IS NOT DISTINCT FROM root.guest_token_hash
                    AND (source.order_id=?
                      OR EXISTS (SELECT 1 FROM support_messages message WHERE message.conversation_id=source.id AND message.order_id=?)
                      OR EXISTS (SELECT 1 FROM support_system_events event WHERE event.conversation_id=source.id AND event.order_id=?)
                      OR EXISTS (SELECT 1 FROM support_activity activity
                        LEFT JOIN after_sale_messages sale_message ON activity.source_type='AFTER_SALE_MESSAGE' AND sale_message.id=activity.source_id
                        LEFT JOIN supplier_complaint_events supplier ON activity.source_type='SUPPLIER_UPDATE' AND supplier.id=activity.source_id
                        LEFT JOIN supplier_aftercare_operations operation ON operation.id=supplier.operation_id
                        JOIN after_sale_requests request ON request.id=CASE
                          WHEN activity.source_type IN ('AFTER_SALE_APPLY','AFTER_SALE_REJECT') THEN activity.source_id
                          WHEN activity.source_type='AFTER_SALE_MESSAGE' THEN sale_message.request_id
                          WHEN activity.source_type='SUPPLIER_UPDATE' THEN operation.after_sale_id ELSE NULL END
                        WHERE activity.conversation_id=source.id AND request.order_id=?))
                ) ORDER BY root.created_at LIMIT 1
                """,
                (rs, n) -> rs.getObject(1, UUID.class), orderId, orderId, orderId, orderId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conversation_id", rows.isEmpty() ? null : rows.getFirst());
        return result;
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
            if (orderId != null) {
                authorizedOrder(orderId, identity);
                if (identity.userId() == null) getOrCreate(SiteRequestContext.getSiteId(), identity, orderId);
            }
            List<AfterSaleAttachmentService.ValidatedAttachment> validated = attachments.validate(files);
            if (normalized.isEmpty() && validated.isEmpty()) throw bad("消息不能为空");
            conversation = getOrCreate(SiteRequestContext.getSiteId(), identity, null);
            if (orderId != null) validateOrderConversation(conversation, orderId);
            return persistMessage(conversation, clientKey, normalized, validated, false, orderId, sourceAnnouncementId);
        }
        UUID effectiveOrder = orderId == null ? conversation.orderId() : orderId;
        if (effectiveOrder != null) {
            if (!admin) authorizedOrder(effectiveOrder, identity);
            validateOrderConversation(conversation, effectiveOrder);
        }
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
        validateOrderConversation(conversation, targetOrder);
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

    /**
     * A valid administrator may still only associate a message with an order
     * belonging to the conversation's customer (or the same guest identity).
     * The main-site account exception mirrors OrderOwnership/SubsiteOrderAccess:
     * a registered buyer can reach their own subsite history from the main site.
     */
    private void validateOrderConversation(Conversation conversation, UUID orderId) {
        Order order = orders.findById(orderId).orElseThrow(SupportService::notFound);
        if (conversation.userId() != null) {
            if (!conversation.userId().equals(order.getUserId())) throw forbidden();
        } else {
            if (order.getUserId() != null || conversation.guestHash() == null) throw forbidden();
            if (!Objects.equals(conversation.orderId(), orderId)) {
                Boolean linked = jdbc.queryForObject("""
                        SELECT EXISTS (
                          SELECT 1 FROM support_conversations linked
                          WHERE linked.site_scope=? AND linked.order_id=?
                            AND linked.user_id IS NULL AND linked.guest_token_hash=?
                        ) OR EXISTS (
                          SELECT 1 FROM support_messages linked_message
                          WHERE linked_message.conversation_id=? AND linked_message.order_id=?
                        )
                        """, Boolean.class, conversation.siteId() == null ? MAIN_SCOPE : conversation.siteId(),
                        orderId, conversation.guestHash(), conversation.id(), orderId);
                if (!Boolean.TRUE.equals(linked)) throw forbidden();
            }
        }
        if (!Objects.equals(conversation.siteId(), order.getSubsiteId())
                && !(conversation.siteId() == null && conversation.userId() != null
                && conversation.userId().equals(order.getUserId()))) throw forbidden();
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
        } else if (!Objects.equals(SiteRequestContext.getSiteId(), c.siteId())) {
            boolean disabledHistoricalSite = identity.userId() != null && SiteRequestContext.getSiteId() == null
                    && c.siteId() != null && identity.userId().equals(c.userId())
                    && Boolean.TRUE.equals(jdbc.queryForObject("SELECT enabled=false FROM subsites WHERE id=?", Boolean.class, c.siteId()));
            if (!disabledHistoricalSite) throw forbidden();
        }
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
