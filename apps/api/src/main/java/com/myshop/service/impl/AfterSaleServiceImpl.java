package com.myshop.service.impl;

import com.myshop.constant.AfterSaleRequestStatus;
import com.myshop.constant.ErrorCode;
import com.myshop.constant.OrderStatus;
import com.myshop.constant.OrderType;
import com.myshop.constant.UserRole;
import com.myshop.context.RequestContext;
import com.myshop.entity.AfterSaleAttachment;
import com.myshop.entity.AfterSaleRequest;
import com.myshop.entity.Order;
import com.myshop.entity.User;
import com.myshop.exception.BusinessException;
import com.myshop.model.response.AdminOrderRefundResponse;
import com.myshop.repository.AfterSaleAttachmentRepository;
import com.myshop.repository.AfterSaleRequestRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.UserRepository;
import com.myshop.service.AfterSaleService;
import com.myshop.service.OperationLogService;
import com.myshop.service.OrderRefundService;
import com.myshop.service.PrivateUploadStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AfterSaleServiceImpl implements AfterSaleService {

    public static final int MAX_ATTACHMENTS = 5;
    public static final long MAX_ATTACHMENT_SIZE = 5L * 1024 * 1024;

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final AfterSaleRequestRepository requestRepository;
    private final AfterSaleAttachmentRepository attachmentRepository;
    private final PrivateUploadStorage privateUploadStorage;
    private final OperationLogService operationLogService;
    private final OrderRefundService orderRefundService;
    private final Clock clock;

    public AfterSaleServiceImpl(OrderRepository orderRepository,
                                UserRepository userRepository,
                                AfterSaleRequestRepository requestRepository,
                                AfterSaleAttachmentRepository attachmentRepository,
                                PrivateUploadStorage privateUploadStorage,
                                OperationLogService operationLogService,
                                OrderRefundService orderRefundService,
                                Clock clock) {
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.requestRepository = requestRepository;
        this.attachmentRepository = attachmentRepository;
        this.privateUploadStorage = privateUploadStorage;
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
        List<MultipartFile> files = normalizeAttachments(attachments);

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
        LocalDateTime submittedAt = LocalDateTime.now(clock);
        AfterSaleRequest request = new AfterSaleRequest();
        request.setId(requestId);
        request.setOrderId(orderId);
        request.setUserId(userId);
        request.setStatus(AfterSaleRequestStatus.PENDING);
        request.setSourceOrderStatus(sourceStatus);
        request.setReason(normalizedReason);
        request.setSubmittedAt(submittedAt);

        List<String> storedKeys = new ArrayList<>();
        try {
            // Flush the parent first because attachments deliberately use a
            // scalar request_id rather than a hidden ORM cascade.
            requestRepository.saveAndFlush(request);
            registerRollbackCleanup(storedKeys);

            List<AfterSaleAttachment> savedAttachments = new ArrayList<>(files.size());
            for (MultipartFile file : files) {
                String extension = extensionOf(file.getOriginalFilename());
                String storageKey = "after-sales/" + requestId + "/" + UUID.randomUUID() + "." + extension;
                privateUploadStorage.write(storageKey, file);
                storedKeys.add(storageKey);

                AfterSaleAttachment attachment = new AfterSaleAttachment();
                attachment.setId(UUID.randomUUID());
                attachment.setRequestId(requestId);
                attachment.setStorageKey(storageKey);
                attachment.setOriginalFilename(safeOriginalFilename(file.getOriginalFilename(), extension));
                attachment.setContentType(normalizedContentType(file));
                attachment.setFileSize(file.getSize());
                savedAttachments.add(attachment);
            }
            if (!savedAttachments.isEmpty()) {
                attachmentRepository.saveAllAndFlush(savedAttachments);
            }

            order.setStatus(OrderStatus.AFTER_SALE);
            orderRepository.save(order);
            operationLogService.logCurrentRequest(
                    "order.after_sale.apply", "ORDER", orderId.toString(),
                    auditDetail(order, request, "申请售后", sourceStatus, null));
            return applyResult(order, request, savedAttachments);
        } catch (RuntimeException exception) {
            cleanup(storedKeys);
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
        Map<UUID, List<AfterSaleAttachment>> attachmentsByRequest = attachments == null
                ? Map.of()
                : attachments.stream().collect(Collectors.groupingBy(
                AfterSaleAttachment::getRequestId, LinkedHashMap::new, Collectors.toList()));
        List<Map<String, Object>> history = requests.stream()
                .map(request -> requestView(request,
                        attachmentsByRequest.getOrDefault(request.getId(), List.of())))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("after_sale", history.get(0));
        result.put("after_sale_history", history);
        return result;
    }

    @Override
    @Transactional
    public void reject(UUID orderId, String adminReply) {
        RequestContext.UserInfo operator = requireAdminContext();
        String normalizedReply = normalizeAdminReply(adminReply);
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));
        AfterSaleRequest request = pendingRequestForUpdate(orderId);
        ensurePendingOrder(order, request);

        request.setStatus(AfterSaleRequestStatus.REJECTED);
        request.setAdminReply(normalizedReply);
        request.setHandledBy(operator.getUserId());
        request.setHandledAt(LocalDateTime.now(clock));
        requestRepository.save(request);
        order.setStatus(request.getSourceOrderStatus());
        orderRepository.save(order);
        operationLogService.logCurrentRequest(
                "order.after_sale.reject", "ORDER", orderId.toString(),
                auditDetail(order, request, "驳回售后", request.getSourceOrderStatus(), normalizedReply.length()));
    }

    @Override
    @Transactional
    public AdminOrderRefundResponse approve(UUID orderId, boolean confirmed,
                                            boolean reclaimCardKeys) {
        return orderRefundService.approveAfterSale(orderId, confirmed, reclaimCardKeys);
    }

    private AfterSaleRequest pendingRequestForUpdate(UUID orderId) {
        return requestRepository.findFirstByOrderIdAndStatusForUpdate(
                        orderId, AfterSaleRequestStatus.PENDING)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "订单没有待处理的售后申请"));
    }

    private void ensurePendingOrder(Order order, AfterSaleRequest request) {
        if (request.getOrderId() == null || !request.getOrderId().equals(order.getId())
                || request.getStatus() != AfterSaleRequestStatus.PENDING) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "售后申请已被其他操作处理");
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
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅登录普通用户可以申请售后");
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

    private List<MultipartFile> normalizeAttachments(List<MultipartFile> attachments) {
        if (attachments == null) return List.of();
        List<MultipartFile> files = attachments.stream().filter(file -> file != null).toList();
        if (files.size() > MAX_ATTACHMENTS) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "最多上传 5 张售后凭证");
        }
        files.forEach(this::validateAttachment);
        return files;
    }

    private void validateAttachment(MultipartFile file) {
        if (file.isEmpty() || file.getSize() <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "售后凭证不能为空");
        }
        if (file.getSize() > MAX_ATTACHMENT_SIZE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "每张售后凭证不能超过 5MB");
        }
        String extension = extensionOf(file.getOriginalFilename());
        String contentType = normalizedContentType(file);
        String expectedType = switch (extension) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            default -> null;
        };
        if (expectedType == null || !expectedType.equals(contentType)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "售后凭证仅支持 JPG、PNG、WebP，且扩展名与 MIME 必须匹配");
        }
        try (InputStream input = file.getInputStream()) {
            byte[] header = input.readNBytes(12);
            if (!magicMatches(extension, header)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "售后凭证文件内容与声明格式不一致");
            }
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "售后凭证读取失败");
        }
    }

    private boolean magicMatches(String extension, byte[] header) {
        return switch (extension) {
            case "jpg", "jpeg" -> header.length >= 3
                    && (header[0] & 0xff) == 0xff
                    && (header[1] & 0xff) == 0xd8
                    && (header[2] & 0xff) == 0xff;
            case "png" -> header.length >= 8
                    && startsWith(header, new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47,
                    0x0d, 0x0a, 0x1a, 0x0a});
            case "webp" -> header.length >= 12
                    && startsWith(header, 0, "RIFF".getBytes(StandardCharsets.US_ASCII))
                    && startsWith(header, 8, "WEBP".getBytes(StandardCharsets.US_ASCII));
            default -> false;
        };
    }

    private boolean startsWith(byte[] value, byte[] prefix) {
        return startsWith(value, 0, prefix);
    }

    private boolean startsWith(byte[] value, int offset, byte[] prefix) {
        if (value.length < offset + prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (value[offset + i] != prefix[i]) return false;
        }
        return true;
    }

    private String normalizeReason(String value) {
        String normalized = value == null ? "" : value.strip();
        int length = normalized.codePointCount(0, normalized.length());
        if (length < 5 || length > 1000) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "售后说明必须为 5 至 1000 个字符");
        }
        return normalized;
    }

    private String normalizeAdminReply(String value) {
        String normalized = value == null ? "" : value.strip();
        int length = normalized.codePointCount(0, normalized.length());
        if (length < 1 || length > 500) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "驳回说明必须为 1 至 500 个字符");
        }
        return normalized;
    }

    private String normalizedContentType(MultipartFile file) {
        return file.getContentType() == null ? "" : file.getContentType().trim().toLowerCase(Locale.ROOT);
    }

    private String extensionOf(String originalFilename) {
        String name = originalFilename == null ? "" : originalFilename.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        name = slash >= 0 ? name.substring(slash + 1) : name;
        int dot = name.lastIndexOf('.');
        if (dot < 1 || dot == name.length() - 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "售后凭证必须带有 JPG、PNG 或 WebP 扩展名");
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String safeOriginalFilename(String originalFilename, String extension) {
        String name = originalFilename == null ? "凭证." + extension : originalFilename.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.strip().replaceAll("[\\p{Cntrl}]", "_");
        if (name.isBlank()) name = "凭证." + extension;
        return name.length() > 255 ? name.substring(0, 255) : name;
    }

    private Map<String, Object> applyResult(Order order, AfterSaleRequest request,
                                            List<AfterSaleAttachment> attachments) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order_id", order.getId());
        result.put("status", order.getStatus().name());
        result.put("after_sale", requestView(request, attachments));
        return result;
    }

    private Map<String, Object> requestView(AfterSaleRequest request,
                                            List<AfterSaleAttachment> attachments) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", request.getId());
        view.put("order_id", request.getOrderId());
        view.put("user_id", request.getUserId());
        view.put("status", request.getStatus() == null ? null : request.getStatus().name());
        view.put("source_order_status", request.getSourceOrderStatus() == null
                ? null : request.getSourceOrderStatus().name());
        view.put("reason", request.getReason());
        view.put("admin_reply", request.getAdminReply());
        view.put("handled_by", request.getHandledBy());
        view.put("submitted_at", request.getSubmittedAt());
        view.put("handled_at", request.getHandledAt());
        view.put("attachments", (attachments == null ? List.<AfterSaleAttachment>of() : attachments)
                .stream().map(this::attachmentView).toList());
        return view;
    }

    private Map<String, Object> attachmentView(AfterSaleAttachment attachment) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", attachment.getId());
        view.put("original_filename", attachment.getOriginalFilename());
        view.put("content_type", attachment.getContentType());
        view.put("file_size", attachment.getFileSize());
        view.put("url", "/api/after-sale-attachments/" + attachment.getId());
        view.put("created_at", attachment.getCreatedAt());
        return view;
    }

    private String auditDetail(Order order, AfterSaleRequest request, String action,
                               OrderStatus sourceStatus, Integer replyLength) {
        String detail = "订单号=" + order.getOrderNo()
                + "，售后申请ID=" + request.getId()
                + "，操作=" + action
                + "，申请前状态=" + sourceStatus.name();
        return replyLength == null ? detail : detail + "，处理说明字符数=" + replyLength;
    }

    private void registerRollbackCleanup(List<String> storedKeys) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    cleanup(storedKeys);
                }
            }
        });
    }

    private void cleanup(List<String> storedKeys) {
        if (storedKeys == null) return;
        for (String storedKey : storedKeys) {
            privateUploadStorage.deleteManagedFile(storedKey);
        }
    }
}
