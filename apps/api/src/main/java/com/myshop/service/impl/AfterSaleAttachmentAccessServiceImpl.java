package com.myshop.service.impl;

import com.myshop.constant.ErrorCode;
import com.myshop.constant.UserRole;
import com.myshop.context.RequestContext;
import com.myshop.entity.AfterSaleAttachment;
import com.myshop.entity.AfterSaleRequest;
import com.myshop.entity.Order;
import com.myshop.exception.BusinessException;
import com.myshop.repository.AfterSaleAttachmentRepository;
import com.myshop.repository.AfterSaleRequestRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.service.AfterSaleAttachmentAccessService;
import com.myshop.service.AfterSaleAttachmentService;
import com.myshop.service.PrivateUploadStorage;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

@Service
public class AfterSaleAttachmentAccessServiceImpl implements AfterSaleAttachmentAccessService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp");

    private final AfterSaleAttachmentRepository attachmentRepository;
    private final AfterSaleRequestRepository requestRepository;
    private final OrderRepository orderRepository;
    private final PrivateUploadStorage privateUploadStorage;

    public AfterSaleAttachmentAccessServiceImpl(AfterSaleAttachmentRepository attachmentRepository,
                                                AfterSaleRequestRepository requestRepository,
                                                OrderRepository orderRepository,
                                                PrivateUploadStorage privateUploadStorage) {
        this.attachmentRepository = attachmentRepository;
        this.requestRepository = requestRepository;
        this.orderRepository = orderRepository;
        this.privateUploadStorage = privateUploadStorage;
    }

    @Override
    public AttachmentDownload open(UUID attachmentId) {
        AfterSaleAttachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "售后凭证不存在"));
        AfterSaleRequest request = requestRepository.findById(attachment.getRequestId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "售后申请不存在"));
        Order order = orderRepository.findById(request.getOrderId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));
        requireAccess(request, order);
        if (!ALLOWED_CONTENT_TYPES.contains(attachment.getContentType())
                || attachment.getFileSize() <= 0
                || attachment.getFileSize() > AfterSaleAttachmentService.MAX_ATTACHMENT_SIZE) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "售后凭证暂时不可用");
        }

        final Path path;
        try {
            path = privateUploadStorage.resolveForRead(attachment.getStorageKey());
            return new AttachmentDownload(new InputStreamResource(
                    Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)),
                    attachment.getContentType(), attachment.getFileSize(), attachment.getOriginalFilename());
        } catch (IOException | IllegalArgumentException | SecurityException exception) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "售后凭证暂时不可用");
        }
    }

    private void requireAccess(AfterSaleRequest request, Order order) {
        RequestContext.UserInfo current = RequestContext.get();
        if (current == null || current.getUserId() == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        boolean admin = UserRole.ADMIN.name().equals(current.getRole())
                || UserRole.SUPER_ADMIN.name().equals(current.getRole());
        boolean owner = UserRole.USER.name().equals(current.getRole())
                && current.getUserId().equals(request.getUserId())
                && current.getUserId().equals(order.getUserId());
        if (!admin && !owner) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问此售后凭证");
        }
    }
}
