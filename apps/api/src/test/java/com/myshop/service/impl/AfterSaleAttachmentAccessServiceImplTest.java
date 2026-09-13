package com.myshop.service.impl;

import com.myshop.constant.AfterSaleRequestStatus;
import com.myshop.constant.ErrorCode;
import com.myshop.constant.OrderStatus;
import com.myshop.constant.OrderType;
import com.myshop.context.RequestContext;
import com.myshop.entity.AfterSaleAttachment;
import com.myshop.entity.AfterSaleRequest;
import com.myshop.entity.Order;
import com.myshop.exception.BusinessException;
import com.myshop.repository.AfterSaleAttachmentRepository;
import com.myshop.repository.AfterSaleRequestRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.service.AfterSaleAttachmentAccessService;
import com.myshop.service.PrivateUploadStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AfterSaleAttachmentAccessServiceImplTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ADMIN_ID = UUID.randomUUID();

    @Mock private AfterSaleAttachmentRepository attachmentRepository;
    @Mock private AfterSaleRequestRepository requestRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private PrivateUploadStorage privateUploadStorage;

    @TempDir
    Path tempDirectory;

    private AfterSaleAttachmentAccessServiceImpl service;
    private UUID attachmentId;
    private AfterSaleAttachment attachment;
    private AfterSaleRequest request;
    private Order order;

    @BeforeEach
    void setUp() {
        service = new AfterSaleAttachmentAccessServiceImpl(
                attachmentRepository, requestRepository, orderRepository, privateUploadStorage);
        attachmentId = UUID.randomUUID();
        attachment = new AfterSaleAttachment();
        attachment.setId(attachmentId);
        attachment.setRequestId(UUID.randomUUID());
        attachment.setStorageKey("after-sales/request/proof.png");
        attachment.setOriginalFilename("proof.png");
        attachment.setContentType("image/png");
        attachment.setFileSize(4);

        request = new AfterSaleRequest();
        request.setId(attachment.getRequestId());
        request.setOrderId(UUID.randomUUID());
        request.setUserId(USER_ID);
        request.setStatus(AfterSaleRequestStatus.PENDING);
        request.setSourceOrderStatus(OrderStatus.PAID);

        order = new Order();
        order.setId(request.getOrderId());
        order.setUserId(USER_ID);
        order.setOrderType(OrderType.NORMAL);
        order.setStatus(OrderStatus.AFTER_SALE);
        order.setOrderNo(1000000001L);
        order.setActualAmount(java.math.BigDecimal.ONE);

        when(attachmentRepository.findById(attachmentId)).thenReturn(Optional.of(attachment));
        when(requestRepository.findById(request.getId())).thenReturn(Optional.of(request));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void ownerCanReadTheAttachmentOnlyAfterDatabaseAuthorization() throws IOException {
        RequestContext.set(new RequestContext.UserInfo(USER_ID, "buyer", "USER"));
        Path file = Files.write(tempDirectory.resolve("proof.png"), new byte[]{1, 2, 3, 4});
        when(privateUploadStorage.resolveForRead(attachment.getStorageKey())).thenReturn(file);

        AfterSaleAttachmentAccessService.AttachmentDownload download = service.open(attachmentId);

        assertEquals("image/png", download.contentType());
        assertEquals(4, download.size());
        assertEquals("proof.png", download.filename());
        assertTrue(download.resource().exists());
        verify(privateUploadStorage).resolveForRead("after-sales/request/proof.png");
    }

    @Test
    void anotherOrdinaryUserCannotReadEvenWhenTheAttachmentAndOrderExist() {
        RequestContext.set(new RequestContext.UserInfo(UUID.randomUUID(), "other", "USER"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.open(attachmentId));

        assertEquals(ErrorCode.FORBIDDEN, exception.getCode());
        verifyNoInteractions(privateUploadStorage);
    }

    @Test
    void administratorsCanReadAnAttachmentAfterDatabaseLookup() throws IOException {
        RequestContext.set(new RequestContext.UserInfo(ADMIN_ID, "admin", "ADMIN"));
        Path file = Files.write(tempDirectory.resolve("admin-proof.png"), new byte[]{4, 3, 2, 1});
        when(privateUploadStorage.resolveForRead(attachment.getStorageKey())).thenReturn(file);

        AfterSaleAttachmentAccessService.AttachmentDownload download = service.open(attachmentId);

        assertEquals("image/png", download.contentType());
        assertEquals(4, download.size());
        assertTrue(download.resource().exists());
        verify(privateUploadStorage).resolveForRead(attachment.getStorageKey());
    }

    @Test
    void administratorsCanReadButAPathTraversalStoredInTheDatabaseStillFailsClosed() {
        RequestContext.set(new RequestContext.UserInfo(ADMIN_ID, "admin", "ADMIN"));
        when(privateUploadStorage.resolveForRead("after-sales/request/proof.png"))
                .thenThrow(new IllegalArgumentException("traversal"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.open(attachmentId));

        assertEquals(ErrorCode.NOT_FOUND, exception.getCode());
        verify(privateUploadStorage).resolveForRead("after-sales/request/proof.png");
    }

    @Test
    void unauthenticatedReadIsRejectedAndDoesNotResolveAnyPhysicalPath() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.open(attachmentId));

        assertEquals(ErrorCode.UNAUTHORIZED, exception.getCode());
        verify(privateUploadStorage, never()).resolveForRead(org.mockito.ArgumentMatchers.anyString());
    }
}
