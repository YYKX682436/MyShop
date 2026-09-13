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
import com.myshop.service.OperationLogService;
import com.myshop.service.OrderRefundService;
import com.myshop.service.PrivateUploadStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AfterSaleServiceImplTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 13, 12, 30);

    @Mock private OrderRepository orderRepository;
    @Mock private UserRepository userRepository;
    @Mock private AfterSaleRequestRepository requestRepository;
    @Mock private AfterSaleAttachmentRepository attachmentRepository;
    @Mock private PrivateUploadStorage privateUploadStorage;
    @Mock private OperationLogService operationLogService;
    @Mock private OrderRefundService orderRefundService;

    private AfterSaleServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AfterSaleServiceImpl(
                orderRepository, userRepository, requestRepository, attachmentRepository,
                privateUploadStorage, operationLogService, orderRefundService,
                Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
        RequestContext.set(new RequestContext.UserInfo(USER_ID, "buyer", "USER"));
        lenient().when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(user(USER_ID, UserRole.USER)));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void appliesWithoutAttachmentsAndMovesOnlyPaidOrDeliveredToAfterSale() {
        for (OrderStatus source : List.of(OrderStatus.PAID, OrderStatus.DELIVERED)) {
            UUID orderId = UUID.randomUUID();
            Order order = order(orderId, source);
            when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
            when(requestRepository.findFirstByOrderIdAndStatusOrderBySubmittedAtDesc(
                    orderId, AfterSaleRequestStatus.PENDING)).thenReturn(Optional.empty());

            Map<String, Object> result = service.apply(orderId, USER_ID, "  商品存在质量问题  ", List.of());

            assertEquals(OrderStatus.AFTER_SALE, order.getStatus());
            assertEquals(OrderStatus.AFTER_SALE.name(), result.get("status"));
            Map<?, ?> requestView = (Map<?, ?>) result.get("after_sale");
            assertEquals(AfterSaleRequestStatus.PENDING.name(), requestView.get("status"));
            assertEquals(source.name(), requestView.get("source_order_status"));
            assertEquals("商品存在质量问题", requestView.get("reason"));
            assertEquals(List.of(), requestView.get("attachments"));
            verify(orderRepository).save(order);
        }
    }

    @Test
    void trimsReasonAndAcceptsFiveAndOneThousandCodePoints() {
        ArgumentCaptor<AfterSaleRequest> requestCaptor = ArgumentCaptor.forClass(AfterSaleRequest.class);
        for (String reason : List.of("  12345  ", "字".repeat(1000))) {
            UUID orderId = UUID.randomUUID();
            Order order = order(orderId, OrderStatus.PAID);
            when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
            when(requestRepository.findFirstByOrderIdAndStatusOrderBySubmittedAtDesc(
                    orderId, AfterSaleRequestStatus.PENDING)).thenReturn(Optional.empty());

            service.apply(orderId, USER_ID, reason, null);
        }
        verify(requestRepository, times(2)).saveAndFlush(requestCaptor.capture());
        assertEquals("12345", requestCaptor.getAllValues().get(0).getReason());
        assertEquals("字".repeat(1000), requestCaptor.getAllValues().get(1).getReason());
    }

    @Test
    void rejectsBlankShortAndLongReasonsBeforeAnyMutation() {
        for (String reason : Arrays.asList(null, "", "    ", "1234", "字".repeat(1001))) {
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> service.apply(UUID.randomUUID(), USER_ID, reason, List.of()));
            assertEquals(ErrorCode.BAD_REQUEST, exception.getCode());
        }
        verifyNoInteractions(orderRepository, requestRepository, attachmentRepository,
                privateUploadStorage, operationLogService);
    }

    @Test
    void requiresTheCurrentOrdinaryUserAndOwnRegisteredNormalOrder() {
        UUID orderId = UUID.randomUUID();
        Order otherOwner = order(orderId, OrderStatus.PAID);
        otherOwner.setUserId(UUID.randomUUID());
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(otherOwner));

        BusinessException otherOwnerException = assertThrows(BusinessException.class,
                () -> service.apply(orderId, USER_ID, "有效售后说明", List.of()));
        assertEquals(ErrorCode.FORBIDDEN, otherOwnerException.getCode());

        RequestContext.set(new RequestContext.UserInfo(USER_ID, "admin", "ADMIN"));
        BusinessException roleException = assertThrows(BusinessException.class,
                () -> service.apply(orderId, USER_ID, "有效售后说明", List.of()));
        assertEquals(ErrorCode.FORBIDDEN, roleException.getCode());

        RequestContext.set(new RequestContext.UserInfo(USER_ID, "buyer", "USER"));
        Order guest = order(UUID.randomUUID(), OrderStatus.PAID);
        guest.setUserId(null);
        when(orderRepository.findByIdForUpdate(guest.getId())).thenReturn(Optional.of(guest));
        BusinessException guestException = assertThrows(BusinessException.class,
                () -> service.apply(guest.getId(), USER_ID, "有效售后说明", List.of()));
        assertEquals(ErrorCode.FORBIDDEN, guestException.getCode());
    }

    @Test
    void rejectsEveryIllegalOrderStateAndDuplicatePendingRequest() {
        for (OrderStatus status : List.of(OrderStatus.PENDING, OrderStatus.EXPIRED,
                OrderStatus.REFUNDED, OrderStatus.AFTER_SALE)) {
            UUID orderId = UUID.randomUUID();
            when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order(orderId, status)));

            BusinessException exception = assertThrows(BusinessException.class,
                    () -> service.apply(orderId, USER_ID, "有效售后说明", List.of()));
            assertEquals(status == OrderStatus.REFUNDED
                    ? ErrorCode.ORDER_ALREADY_REFUNDED :
                    status == OrderStatus.EXPIRED ? ErrorCode.ORDER_EXPIRED : ErrorCode.BAD_REQUEST,
                    exception.getCode());
        }

        UUID duplicateOrderId = UUID.randomUUID();
        when(orderRepository.findByIdForUpdate(duplicateOrderId))
                .thenReturn(Optional.of(order(duplicateOrderId, OrderStatus.PAID)));
        AfterSaleRequest pending = request(duplicateOrderId, OrderStatus.PAID, AfterSaleRequestStatus.PENDING);
        when(requestRepository.findFirstByOrderIdAndStatusOrderBySubmittedAtDesc(
                duplicateOrderId, AfterSaleRequestStatus.PENDING)).thenReturn(Optional.of(pending));

        BusinessException duplicate = assertThrows(BusinessException.class,
                () -> service.apply(duplicateOrderId, USER_ID, "有效售后说明", List.of()));
        assertEquals(ErrorCode.BAD_REQUEST, duplicate.getCode());
        verify(requestRepository, never()).saveAndFlush(any(AfterSaleRequest.class));
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void validatesAttachmentCountSizeExtensionMimeAndMagicBytes() {
        UUID orderId = UUID.randomUUID();
        List<MultipartFile> sixFiles = List.of(
                image("a.jpg", "image/jpeg", jpegBytes()), image("b.jpg", "image/jpeg", jpegBytes()),
                image("c.jpg", "image/jpeg", jpegBytes()), image("d.jpg", "image/jpeg", jpegBytes()),
                image("e.jpg", "image/jpeg", jpegBytes()), image("f.jpg", "image/jpeg", jpegBytes()));
        BusinessException tooMany = assertThrows(BusinessException.class,
                () -> service.apply(orderId, USER_ID, "有效售后说明", sixFiles));
        assertEquals(ErrorCode.BAD_REQUEST, tooMany.getCode());

        BusinessException tooLarge = assertThrows(BusinessException.class,
                () -> service.apply(orderId, USER_ID, "有效售后说明",
                        List.of(image("large.png", "image/png", new byte[5 * 1024 * 1024 + 1]))));
        assertEquals(ErrorCode.BAD_REQUEST, tooLarge.getCode());

        for (MultipartFile invalid : List.of(
                image("e.gif", "image/gif", pngBytes()),
                image("e.png", "image/jpeg", pngBytes()),
                image("e.png", "image/png", "not-a-png".getBytes()))) {
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> service.apply(orderId, USER_ID, "有效售后说明", List.of(invalid)));
            assertEquals(ErrorCode.BAD_REQUEST, exception.getCode());
        }
        verifyNoInteractions(orderRepository, requestRepository, attachmentRepository, privateUploadStorage);
    }

    @Test
    void acceptsJpegPngAndWebpAndNeverReturnsStorageKeyToTheClient() {
        UUID orderId = UUID.randomUUID();
        Order order = order(orderId, OrderStatus.DELIVERED);
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(requestRepository.findFirstByOrderIdAndStatusOrderBySubmittedAtDesc(
                orderId, AfterSaleRequestStatus.PENDING)).thenReturn(Optional.empty());

        Map<String, Object> result = service.apply(orderId, USER_ID, "交付内容与描述不符", List.of(
                image("proof.JPG", "image/jpeg", jpegBytes()),
                image("proof.png", "image/png", pngBytes()),
                image("proof.webp", "image/webp", webpBytes())));

        assertEquals(OrderStatus.AFTER_SALE, order.getStatus());
        Map<?, ?> requestView = (Map<?, ?>) result.get("after_sale");
        List<?> attachmentViews = (List<?>) requestView.get("attachments");
        assertEquals(3, attachmentViews.size());
        for (Object value : attachmentViews) {
            Map<?, ?> attachmentView = (Map<?, ?>) value;
            assertTrue(String.valueOf(attachmentView.get("url")).startsWith("/api/after-sale-attachments/"));
            assertFalse(attachmentView.containsKey("storage_key"));
        }
        verify(privateUploadStorage, org.mockito.Mockito.times(3))
                .write(any(String.class), any(MultipartFile.class));
        verify(attachmentRepository).saveAllAndFlush(anyList());
    }

    @Test
    void fileOrAttachmentDatabaseFailureCleansStoredFilesAndDoesNotSaveTheOrderState() {
        UUID orderId = UUID.randomUUID();
        Order order = order(orderId, OrderStatus.PAID);
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(requestRepository.findFirstByOrderIdAndStatusOrderBySubmittedAtDesc(
                orderId, AfterSaleRequestStatus.PENDING)).thenReturn(Optional.empty());
        doThrow(new IllegalStateException("database failure"))
                .when(attachmentRepository).saveAllAndFlush(anyList());

        assertThrows(IllegalStateException.class, () -> service.apply(orderId, USER_ID,
                "附件保存失败时保持一致", List.of(image("proof.png", "image/png", pngBytes()))));

        assertEquals(OrderStatus.PAID, order.getStatus());
        verify(privateUploadStorage).deleteManagedFile(any(String.class));
        verify(orderRepository, never()).save(order);
    }

    @Test
    void rejectionRestoresTheCapturedPaidOrDeliveredStateAndRequiresReply() {
        RequestContext.set(new RequestContext.UserInfo(ADMIN_ID, "admin", "ADMIN"));
        for (OrderStatus source : List.of(OrderStatus.PAID, OrderStatus.DELIVERED)) {
            UUID orderId = UUID.randomUUID();
            Order order = order(orderId, OrderStatus.AFTER_SALE);
            AfterSaleRequest request = request(orderId, source, AfterSaleRequestStatus.PENDING);
            when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
            when(requestRepository.findFirstByOrderIdAndStatusForUpdate(
                    orderId, AfterSaleRequestStatus.PENDING)).thenReturn(Optional.of(request));

            service.reject(orderId, "  请补充完整凭证  ");

            assertEquals(AfterSaleRequestStatus.REJECTED, request.getStatus());
            assertEquals("请补充完整凭证", request.getAdminReply());
            assertEquals(ADMIN_ID, request.getHandledBy());
            assertEquals(NOW, request.getHandledAt());
            assertEquals(source, order.getStatus());
            verify(requestRepository).save(request);
            verify(orderRepository).save(order);
        }
    }

    @Test
    void rejectionReplyIsValidatedBeforeLockingAndApprovalUsesUnifiedRefundService() {
        UUID orderId = UUID.randomUUID();
        RequestContext.set(new RequestContext.UserInfo(ADMIN_ID, "admin", "ADMIN"));
        assertThrows(BusinessException.class, () -> service.reject(orderId, ""));
        assertThrows(BusinessException.class, () -> service.reject(orderId, "字".repeat(501)));
        verifyNoInteractions(orderRepository, requestRepository);

        AdminOrderRefundResponse response = new AdminOrderRefundResponse(
                orderId, 1000000001L, OrderStatus.REFUNDED, new BigDecimal("9.00"),
                NOW, "BALANCE", BigDecimal.ZERO, new BigDecimal("9.00"), 0);
        when(orderRefundService.approveAfterSale(orderId, true, false)).thenReturn(response);

        assertEquals(response, service.approve(orderId, true, false));
        verify(orderRefundService).approveAfterSale(orderId, true, false);
    }

    @Test
    void orderViewContainsHistoricalStatusAndPrivateAttachmentUrlsOnly() {
        UUID orderId = UUID.randomUUID();
        AfterSaleRequest rejected = request(orderId, OrderStatus.PAID, AfterSaleRequestStatus.REJECTED);
        rejected.setAdminReply("不符合售后条件");
        rejected.setHandledBy(ADMIN_ID);
        rejected.setHandledAt(NOW.plusHours(1));
        AfterSaleRequest pending = request(orderId, OrderStatus.DELIVERED, AfterSaleRequestStatus.PENDING);
        AfterSaleAttachment attachment = new AfterSaleAttachment();
        attachment.setId(UUID.randomUUID());
        attachment.setRequestId(pending.getId());
        attachment.setStorageKey("after-sales/secret/physical.png");
        attachment.setOriginalFilename("凭证.png");
        attachment.setContentType("image/png");
        attachment.setFileSize(12);
        when(requestRepository.findByOrderIdOrderBySubmittedAtDesc(orderId))
                .thenReturn(List.of(pending, rejected));
        when(attachmentRepository.findByRequestIdInOrderByCreatedAtAsc(
                List.of(pending.getId(), rejected.getId()))).thenReturn(List.of(attachment));

        Map<String, Object> view = service.buildOrderView(orderId);
        assertEquals(2, ((List<?>) view.get("after_sale_history")).size());
        Map<?, ?> current = (Map<?, ?>) view.get("after_sale");
        Map<?, ?> currentAttachment = (Map<?, ?>) ((List<?>) current.get("attachments")).get(0);
        assertFalse(currentAttachment.containsKey("storage_key"));
        assertTrue(String.valueOf(currentAttachment.get("url")).contains(attachment.getId().toString()));
        Map<?, ?> old = (Map<?, ?>) ((List<?>) view.get("after_sale_history")).get(1);
        assertEquals("不符合售后条件", old.get("admin_reply"));
    }

    private Order order(UUID id, OrderStatus status) {
        Order order = new Order();
        order.setId(id);
        order.setOrderNo(1000000001L);
        order.setUserId(USER_ID);
        order.setOrderType(OrderType.NORMAL);
        order.setStatus(status);
        order.setActualAmount(new BigDecimal("9.00"));
        order.setTotalAmount(new BigDecimal("9.00"));
        return order;
    }

    private User user(UUID id, UserRole role) {
        User user = new User();
        user.setId(id);
        user.setUsername("buyer");
        user.setRole(role);
        return user;
    }

    private AfterSaleRequest request(UUID orderId, OrderStatus source, AfterSaleRequestStatus status) {
        AfterSaleRequest request = new AfterSaleRequest();
        request.setId(UUID.randomUUID());
        request.setOrderId(orderId);
        request.setUserId(USER_ID);
        request.setSourceOrderStatus(source);
        request.setStatus(status);
        request.setReason("有效售后说明");
        request.setSubmittedAt(NOW.minusMinutes(5));
        return request;
    }

    private MockMultipartFile image(String filename, String contentType, byte[] bytes) {
        return new MockMultipartFile("attachments", filename, contentType, bytes);
    }

    private byte[] jpegBytes() {
        return new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x00};
    }

    private byte[] pngBytes() {
        return new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00};
    }

    private byte[] webpBytes() {
        return new byte[]{0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00,
                0x57, 0x45, 0x42, 0x50};
    }
}
