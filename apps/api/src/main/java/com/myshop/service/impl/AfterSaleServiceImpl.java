package com.myshop.service.impl;

import com.myshop.constant.AfterSaleMessageSenderType;
import com.myshop.constant.AfterSaleRequestStatus;
import com.myshop.constant.AfterSaleWaitingFor;
import com.myshop.constant.ErrorCode;
import com.myshop.constant.OrderStatus;
import com.myshop.constant.OrderType;
import com.myshop.constant.UserRole;
import com.myshop.context.RequestContext;
import com.myshop.entity.AfterSaleAttachment;
import com.myshop.entity.AfterSaleMessage;
import com.myshop.entity.AfterSaleRequest;
import com.myshop.entity.Order;
import com.myshop.entity.User;
import com.myshop.exception.BusinessException;
import com.myshop.model.response.AdminOrderRefundResponse;
import com.myshop.repository.AfterSaleAttachmentRepository;
import com.myshop.repository.AfterSaleMessageRepository;
import com.myshop.repository.AfterSaleRequestRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.UserRepository;
import com.myshop.service.AfterSaleAttachmentService;
import com.myshop.service.AfterSaleService;
import com.myshop.service.OperationLogService;
import com.myshop.service.OrderRefundService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AfterSaleServiceImpl implements AfterSaleService {

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final AfterSaleRequestRepository requestRepository;
    private final AfterSaleAttachmentRepository attachmentRepository;
    private final AfterSaleMessageRepository messageRepository;
    private final AfterSaleAttachmentService attachmentService;
    private final OperationLogService operationLogService;
    private final OrderRefundService orderRefundService;
    private final Clock clock;

    public AfterSaleServiceImpl(OrderRepository orderRepository,
                                UserRepository userRepository,
                                AfterSaleRequestRepository requestRepository,
                                AfterSaleAttachmentRepository attachmentRepository,
                                AfterSaleMessageRepository messageRepository,
                                AfterSaleAttachmentService attachmentService,
                                OperationLogService operationLogService,
                                OrderRefundService orderRefundService,
                                Clock clock) {
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.requestRepository = requestRepository;
        this.attachmentRepository = attachmentRepository;
        this.messageRepository = messageRepository;
        this.attachmentService = attachmentService;
        this.operationLogService = operationLogService;
        this.orderRefundService = orderRefundService;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Map<String, Object> apply(UUID orderId, UUID userId, String reason,
                                     List<MultipartFile> attachments) {
        requireCustomerContext(userId);
        String normalizedReason = normalizeReason(reason);
        List<AfterSaleAttachmentService.ValidatedAttachment> files =
                attachmentService.validate(attachments);

        // The order row is the first lock for every after-sale transition.
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));
        if (order.getUserId() == null || !order.getUserId().equals(userId)
                || order.getOrderType() != OrderType.NORMAL) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只能为自己的注册用户订单申请售后");
        }
        if (userRepository.findById(userId).map(User::getRole).orElse(null) != UserRole.USER) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有普通用户可以申请售后");
        }
        OrderStatus sourceStatus = order.getStatus();
        if (sourceStatus != OrderStatus.PAID && sourceStatus != OrderStatus.DELIVERED) {
            if (sourceStatus == OrderStatus.AFTER_SALE) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "订单已有待处理的售后申请");
            }
            if (sourceStatus == OrderStatus.REFUNDED) {
                throw new BusinessException(ErrorCode.ORDER_ALREADY_REFUNDED, "订单已退款，不能再次申请售后");
            }
            if (sourceStatus == OrderStatus.PENDING) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "待付款订单不能申请售后");
            }
            if (sourceStatus == OrderStatus.EXPIRED) {
                throw new BusinessException(ErrorCode.ORDER_EXPIRED, "已过期订单不能申请售后");
            }
            throw new BusinessException(ErrorCode.BAD_REQUEST, "当前订单状态不能申请售后");
        }
        if (requestRepository.findFirstByOrderIdAndStatusOrderBySubmittedAtDesc(
                orderId, AfterSaleRequestStatus.PENDING).isPresent()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "订单已有待处理的售后申请");
        }

        UUID requestId = UUID.randomUUID();
        LocalDateTime submittedAt = now();
        AfterSaleRequest request = new AfterSaleRequest();
        request.setId(requestId);
        request.setOrderId(orderId);
        request.setUserId(userId);
        request.setStatus(AfterSaleRequestStatus.PENDING);
        request.setSourceOrderStatus(sourceStatus);
        request.setReason(normalizedReason);
        request.setWaitingFor(AfterSaleWaitingFor.ADMIN);
        request.setLastActivityAt(submittedAt);
        request.setSubmittedAt(submittedAt);

        List<String> storedKeys = new ArrayList<>();
        try {
            // Flush the parent first because attachments deliberately use
            // scalar foreign keys rather than an ORM cascade.
            requestRepository.saveAndFlush(request);
            registerRollbackCleanup(storedKeys);
            AfterSaleAttachmentService.StoredAttachments stored = attachmentService.store(
                    requestId, null, files);
            storedKeys.addAll(stored.storageKeys());

            order.setStatus(OrderStatus.AFTER_SALE);
            orderRepository.save(order);
            operationLogService.logCurrentRequest(
                    "order.after_sale.apply", "ORDER", orderId.toString(),
                    auditDetail(order, request, "申请售后", sourceStatus));
            return applyResult(order, request, stored.attachments());
        } catch (RuntimeException exception) {
            attachmentService.cleanup(storedKeys);
            throw exception;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> buildOrderView(UUID orderId) {
        List<AfterSaleRequest> requests = requestRepository
                .findByOrderIdOrderBySubmittedAtDesc(orderId);
        if (requests == null || requests.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("after_sale", null);
            empty.put("after_sale_history", List.of());
            return empty;
        }
        List<UUID> requestIds = requests.stream().map(AfterSaleRequest::getId).toList();
        List<AfterSaleAttachment> attachments = attachmentRepository
                .findByRequestIdInOrderByCreatedAtAsc(requestIds);
        List<AfterSaleMessage> messages = messageRepository
                .findByRequestIdInOrderByCreatedAtAscIdAsc(requestIds);
        Map<UUID, List<AfterSaleAttachment>> attachmentsByRequest = groupAttachmentsByRequest(attachments);
        Map<UUID, List<AfterSaleAttachment>> attachmentsByMessage = groupAttachmentsByMessage(attachments);
        Map<UUID, List<AfterSaleMessage>> messagesByRequest = messages == null
                ? Map.of()
                : messages.stream().collect(Collectors.groupingBy(
                AfterSaleMessage::getRequestId, LinkedHashMap::new, Collectors.toList()));
        List<Map<String, Object>> history = requests.stream()
                .map(request -> requestView(request,
                        attachmentsByRequest.getOrDefault(request.getId(), List.of()),
                        messagesByRequest.getOrDefault(request.getId(), List.of()),
                        attachmentsByMessage))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("after_sale", history.get(0));
        result.put("after_sale_history", history);
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Map<String, Object>> buildOrderSummaries(Collection<UUID> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> uniqueOrderIds = orderIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (uniqueOrderIds.isEmpty()) {
            return Map.of();
        }
        // Current AFTER_SALE orders have one pending request by database
        // contract. One query is enough for every list-page order; the full
        // historical conversation remains the buildOrderView path.
        List<AfterSaleRequest> requests = requestRepository
                .findByOrderIdInAndStatusOrderByLastActivityAtDescIdDesc(
                        uniqueOrderIds, AfterSaleRequestStatus.PENDING);
        if (requests == null || requests.isEmpty()) {
            return Map.of();
        }
        return requests.stream().collect(Collectors.toMap(
                AfterSaleRequest::getOrderId,
                this::summaryView,
                (first, ignored) -> first,
                LinkedHashMap::new));
    }

    @Override
    @Transactional(readOnly = true)
    public long countNeedsAdminAttention() {
        return requestRepository.countByStatusAndWaitingFor(
                AfterSaleRequestStatus.PENDING, AfterSaleWaitingFor.ADMIN);
    }

    @Override
    @Transactional
    public void reject(UUID orderId, String rejectionReason) {
        RequestContext.UserInfo operator = requireAdminContext();
        String normalizedReason = normalizeRejectionReason(rejectionReason);
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));
        AfterSaleRequest request = pendingRequestForUpdate(orderId);
        ensurePendingOrder(order, request);

        LocalDateTime handledAt = terminalTimeAfter(request.getLastActivityAt());
        request.setStatus(AfterSaleRequestStatus.REJECTED);
        request.setRejectionReason(normalizedReason);
        request.setWaitingFor(null);
        request.setLastActivityAt(handledAt);
        request.setHandledBy(operator.getUserId());
        request.setHandledAt(handledAt);
        requestRepository.save(request);
        order.setStatus(request.getSourceOrderStatus());
        orderRepository.save(order);
        operationLogService.logCurrentRequest(
                "order.after_sale.reject", "ORDER", orderId.toString(),
                auditDetail(order, request, "驳回售后", request.getSourceOrderStatus())
                        + "，处理说明字符数=" + normalizedReason.codePointCount(0, normalizedReason.length()));
    }

    @Override
    @Transactional
    public Map<String, Object> replyFromCustomer(UUID orderId, UUID userId, String content,
                                                  List<MultipartFile> attachments) {
        requireCustomerContext(userId);
        return appendMessage(orderId, userId, AfterSaleMessageSenderType.CUSTOMER,
                content, attachments);
    }

    @Override
    @Transactional
    public Map<String, Object> replyFromAdmin(UUID orderId, String content,
                                               List<MultipartFile> attachments) {
        RequestContext.UserInfo operator = requireAdminContext();
        return appendMessage(orderId, operator.getUserId(), AfterSaleMessageSenderType.ADMIN,
                content, attachments);
    }

    @Override
    @Transactional
    public AdminOrderRefundResponse approve(UUID orderId, boolean confirmed,
                                            boolean reclaimCardKeys) {
        return orderRefundService.approveAfterSale(orderId, confirmed, reclaimCardKeys);
    }

    private Map<String, Object> appendMessage(UUID orderId, UUID senderId,
                                              AfterSaleMessageSenderType senderType,
                                              String content,
                                              List<MultipartFile> attachments) {
        String normalizedContent = normalizeMessage(content);
        List<AfterSaleAttachmentService.ValidatedAttachment> files =
                attachmentService.validate(attachments);

        // Customer replies, admin replies, reject, approve and fulfillment all
        // start with the same order-row lock. The pending request is locked
        // only after the order has established the transition boundary.
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));
        if (senderType == AfterSaleMessageSenderType.CUSTOMER
                && (order.getUserId() == null || !order.getUserId().equals(senderId)
                || order.getOrderType() != OrderType.NORMAL
                || userRepository.findById(senderId).map(User::getRole).orElse(null) != UserRole.USER)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只能回复自己的注册用户订单售后");
        }
        AfterSaleRequest request = pendingRequestForUpdate(orderId);
        ensurePendingOrder(order, request);
        if (senderType == AfterSaleMessageSenderType.CUSTOMER
                && !request.getUserId().equals(senderId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权回复此售后申请");
        }

        UUID messageId = UUID.randomUUID();
        LocalDateTime messageAt = now();
        AfterSaleMessage message = new AfterSaleMessage();
        message.setId(messageId);
        message.setRequestId(request.getId());
        message.setSenderType(senderType);
        message.setSenderUserId(senderId);
        message.setContent(normalizedContent);
        message.setCreatedAt(messageAt);

        List<String> storedKeys = new ArrayList<>();
        try {
            messageRepository.saveAndFlush(message);
            registerRollbackCleanup(storedKeys);
            AfterSaleAttachmentService.StoredAttachments stored = attachmentService.store(
                    request.getId(), messageId, files);
            storedKeys.addAll(stored.storageKeys());

            request.setWaitingFor(senderType == AfterSaleMessageSenderType.CUSTOMER
                    ? AfterSaleWaitingFor.ADMIN : AfterSaleWaitingFor.CUSTOMER);
            request.setLastActivityAt(messageAt);
            requestRepository.save(request);
            operationLogService.logCurrentRequest(
                    "order.after_sale.message", "ORDER", orderId.toString(),
                    "订单号=" + order.getOrderNo()
                            + "，售后申请ID=" + request.getId()
                            + "，消息ID=" + message.getId()
                            + "，发送方=" + senderType.name()
                            + "，发送方用户ID=" + senderId);
            return buildOrderView(orderId);
        } catch (RuntimeException exception) {
            attachmentService.cleanup(storedKeys);
            throw exception;
        }
    }

    private AfterSaleRequest pendingRequestForUpdate(UUID orderId) {
        return requestRepository.findFirstByOrderIdAndStatusForUpdate(
                        orderId, AfterSaleRequestStatus.PENDING)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "订单没有待处理的售后申请"));
    }

    private void ensurePendingOrder(Order order, AfterSaleRequest request) {
        if (request.getOrderId() == null || !request.getOrderId().equals(order.getId())
                || request.getStatus() != AfterSaleRequestStatus.PENDING
                || request.getUserId() == null || !request.getUserId().equals(order.getUserId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "售后申请与订单不一致");
        }
        if (order.getStatus() != OrderStatus.AFTER_SALE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "订单当前状态与售后申请不一致");
        }
        if (request.getSourceOrderStatus() != OrderStatus.PAID
                && request.getSourceOrderStatus() != OrderStatus.DELIVERED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "售后申请原始状态无效");
        }
    }

    private void requireCustomerContext(UUID userId) {
        RequestContext.UserInfo current = RequestContext.get();
        if (userId == null || current == null || !userId.equals(current.getUserId())
                || !UserRole.USER.name().equals(current.getRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅登录普通用户可以申请或回复售后");
        }
    }

    private RequestContext.UserInfo requireAdminContext() {
        RequestContext.UserInfo operator = RequestContext.get();
        if (operator == null || operator.getUserId() == null
                || (!UserRole.ADMIN.name().equals(operator.getRole())
                && !UserRole.SUPER_ADMIN.name().equals(operator.getRole()))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅管理员可以处理售后申请");
        }
        return operator;
    }

    private String normalizeReason(String value) {
        String normalized = value == null ? "" : value.strip();
        int length = normalized.codePointCount(0, normalized.length());
        if (length < 5 || length > 1000) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "售后说明必须为 5 至 1000 个字符");
        }
        return normalized;
    }

    private String normalizeMessage(String value) {
        String normalized = value == null ? "" : value.strip();
        int length = normalized.codePointCount(0, normalized.length());
        if (length < 1 || length > 1000) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "售后回复必须为 1 至 1000 个字符");
        }
        return normalized;
    }

    private String normalizeRejectionReason(String value) {
        String normalized = value == null ? "" : value.strip();
        int length = normalized.codePointCount(0, normalized.length());
        if (length < 1 || length > 500) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "驳回说明必须为 1 至 500 个字符");
        }
        return normalized;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private LocalDateTime terminalTimeAfter(LocalDateTime previousActivityAt) {
        LocalDateTime candidate = now();
        if (previousActivityAt == null) {
            return candidate;
        }
        LocalDateTime minimum = previousActivityAt.plusNanos(1_000_000);
        return candidate.isAfter(minimum) ? candidate : minimum;
    }

    private Map<UUID, List<AfterSaleAttachment>> groupAttachmentsByRequest(
            List<AfterSaleAttachment> attachments) {
        if (attachments == null) {
            return Map.of();
        }
        return attachments.stream().collect(Collectors.groupingBy(
                AfterSaleAttachment::getRequestId, LinkedHashMap::new, Collectors.toList()));
    }

    private Map<UUID, List<AfterSaleAttachment>> groupAttachmentsByMessage(
            List<AfterSaleAttachment> attachments) {
        if (attachments == null) {
            return Map.of();
        }
        return attachments.stream()
                .filter(attachment -> attachment.getMessageId() != null)
                .collect(Collectors.groupingBy(
                        AfterSaleAttachment::getMessageId, LinkedHashMap::new, Collectors.toList()));
    }

    private Map<String, Object> applyResult(Order order, AfterSaleRequest request,
                                            List<AfterSaleAttachment> attachments) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order_id", order.getId());
        result.put("status", order.getStatus().name());
        result.put("after_sale", requestView(request, attachments, List.of(), Map.of()));
        return result;
    }

    private Map<String, Object> summaryView(AfterSaleRequest request) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", request.getId());
        summary.put("order_id", request.getOrderId());
        summary.put("user_id", request.getUserId());
        summary.put("status", request.getStatus() == null ? null : request.getStatus().name());
        summary.put("source_order_status", request.getSourceOrderStatus() == null
                ? null : request.getSourceOrderStatus().name());
        summary.put("waiting_for", request.getWaitingFor() == null ? null : request.getWaitingFor().name());
        summary.put("last_activity_at", request.getLastActivityAt());
        summary.put("submitted_at", request.getSubmittedAt());
        summary.put("handled_at", request.getHandledAt());
        return summary;
    }

    private Map<String, Object> requestView(AfterSaleRequest request,
                                            List<AfterSaleAttachment> attachments,
                                            List<AfterSaleMessage> messages,
                                            Map<UUID, List<AfterSaleAttachment>> attachmentsByMessage) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", request.getId());
        view.put("order_id", request.getOrderId());
        view.put("user_id", request.getUserId());
        view.put("status", request.getStatus() == null ? null : request.getStatus().name());
        view.put("source_order_status", request.getSourceOrderStatus() == null
                ? null : request.getSourceOrderStatus().name());
        view.put("reason", request.getReason());
        view.put("rejection_reason", request.getRejectionReason());
        view.put("waiting_for", request.getWaitingFor() == null ? null : request.getWaitingFor().name());
        view.put("last_activity_at", request.getLastActivityAt());
        view.put("handled_by", request.getHandledBy());
        view.put("submitted_at", request.getSubmittedAt());
        view.put("handled_at", request.getHandledAt());
        view.put("attachments", (attachments == null ? List.<AfterSaleAttachment>of() : attachments)
                .stream()
                .filter(attachment -> attachment.getMessageId() == null)
                .map(this::attachmentView).toList());
        view.put("messages", (messages == null ? List.<AfterSaleMessage>of() : messages)
                .stream().map(message -> messageView(message,
                        attachmentsByMessage.getOrDefault(message.getId(), List.of()))).toList());
        return view;
    }

    private Map<String, Object> messageView(AfterSaleMessage message,
                                            List<AfterSaleAttachment> attachments) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", message.getId());
        view.put("request_id", message.getRequestId());
        view.put("sender_type", message.getSenderType() == null ? null : message.getSenderType().name());
        view.put("sender_user_id", message.getSenderUserId());
        view.put("content", message.getContent());
        view.put("created_at", message.getCreatedAt());
        view.put("attachments", (attachments == null ? List.<AfterSaleAttachment>of() : attachments)
                .stream().map(this::attachmentView).toList());
        return view;
    }

    private Map<String, Object> attachmentView(AfterSaleAttachment attachment) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", attachment.getId());
        view.put("message_id", attachment.getMessageId());
        view.put("original_filename", attachment.getOriginalFilename());
        view.put("content_type", attachment.getContentType());
        view.put("file_size", attachment.getFileSize());
        view.put("url", "/api/after-sale-attachments/" + attachment.getId());
        view.put("created_at", attachment.getCreatedAt());
        return view;
    }

    private String auditDetail(Order order, AfterSaleRequest request, String action,
                               OrderStatus sourceStatus) {
        return "订单号=" + order.getOrderNo()
                + "，售后申请ID=" + request.getId()
                + "，操作=" + action
                + "，申请前状态=" + sourceStatus.name();
    }

    private void registerRollbackCleanup(List<String> storedKeys) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    attachmentService.cleanup(storedKeys);
                }
            }
        });
    }
}
