package com.myshop.service.impl;

import com.myshop.constant.CardKeyStatus;
import com.myshop.constant.ErrorCode;
import com.myshop.constant.OrderStatus;
import com.myshop.constant.OrderType;
import com.myshop.constant.StockMode;
import com.myshop.entity.CardImportBatch;
import com.myshop.entity.CardKey;
import com.myshop.entity.Order;
import com.myshop.entity.OrderItem;
import com.myshop.entity.Product;
import com.myshop.entity.ProductSpec;
import com.myshop.exception.BusinessException;
import com.myshop.model.request.AdminCardKeyBatchPickupRequest;
import com.myshop.repository.CardImportBatchRepository;
import com.myshop.repository.CardKeyRepository;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.ProductSpecRepository;
import com.myshop.service.ManualStockService;
import com.myshop.utils.CardContentParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminCardKeyServiceImplTest {

    @Mock private CardKeyRepository cardKeyRepository;
    @Mock private CardImportBatchRepository batchRepository;
    @Mock private ProductRepository productRepository;
    @Mock private ProductSpecRepository productSpecRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private ManualStockService manualStockService;

    private AdminCardKeyServiceImpl service;
    private UUID productId;
    private Product product;

    @BeforeEach
    void setUp() {
        service = new AdminCardKeyServiceImpl(cardKeyRepository, batchRepository, productRepository,
                productSpecRepository, orderRepository, orderItemRepository, manualStockService);
        productId = UUID.randomUUID();
        product = new Product();
        product.setId(productId);
        product.setDeliveryType("AUTO");
        product.setStockMode(StockMode.CARD_KEY);
        product.setProductNo(1L);
        product.setTitle("测试商品");
        product.setRetailPrice(new java.math.BigDecimal("12.00"));
        product.setCostPrice(new java.math.BigDecimal("5.00"));
        product.setSpecEnabled(false);
        product.setIsDeleted(0);
        lenient().when(productRepository.findById(productId)).thenReturn(Optional.of(product));
    }

    private void stubBatchSave() {
        when(batchRepository.save(any(CardImportBatch.class))).thenAnswer(invocation -> {
            CardImportBatch batch = invocation.getArgument(0);
            if (batch.getId() == null) batch.setId(UUID.randomUUID());
            return batch;
        });
    }

    private void stubPickupSaves(UUID orderId, UUID orderItemId) {
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setId(orderId);
            order.setOrderNo(1000000001L);
            return order;
        });
        when(orderItemRepository.saveAndFlush(any(OrderItem.class))).thenAnswer(invocation -> {
            OrderItem item = invocation.getArgument(0);
            item.setId(orderItemId);
            return item;
        });
        when(productRepository.findByIdForUpdate(productId)).thenReturn(Optional.of(product));
    }

    @Test
    void specEnabledWithoutSpecIdIsRejectedBeforeAnyWrites() {
        product.setSpecEnabled(true);

        BusinessException exception = assertThrows(BusinessException.class, () -> service.importCardKeys(
                Map.of("product_id", productId.toString(),
                        "content_columns", List.of("A")), UUID.randomUUID()));

        assertEquals(ErrorCode.BAD_REQUEST, exception.getCode());
        verify(batchRepository, never()).save(any(CardImportBatch.class));
        verify(cardKeyRepository, never()).saveAll(anyList());
        verify(productSpecRepository, never()).findById(any());
    }

    @Test
    void specEnabledWithValidOwnedSpecImportsKeysIntoThatSpec() {
        product.setSpecEnabled(true);
        UUID specId = UUID.randomUUID();
        ProductSpec spec = new ProductSpec();
        spec.setId(specId);
        spec.setProductId(productId);
        spec.setIsDeleted(0);
        when(productSpecRepository.findById(specId)).thenReturn(Optional.of(spec));
        stubBatchSave();

        service.importCardKeys(
                Map.of("product_id", productId.toString(),
                        "spec_id", specId.toString(),
                        "content_columns", List.of("A")), UUID.randomUUID());

        ArgumentCaptor<List<CardKey>> keys = ArgumentCaptor.forClass(List.class);
        verify(cardKeyRepository).saveAll(keys.capture());
        assertEquals(specId, keys.getValue().get(0).getSpecId());
    }

    @Test
    void specEnabledWithForeignSpecIsRejectedBeforeAnyWrites() {
        product.setSpecEnabled(true);
        UUID foreignSpecId = UUID.randomUUID();

        BusinessException exception = assertThrows(BusinessException.class, () -> service.importCardKeys(
                Map.of("product_id", productId.toString(),
                        "spec_id", foreignSpecId.toString(),
                        "content_columns", List.of("A")), UUID.randomUUID()));

        assertEquals(ErrorCode.SPEC_NOT_FOUND, exception.getCode());
        verify(batchRepository, never()).save(any(CardImportBatch.class));
        verify(cardKeyRepository, never()).saveAll(anyList());
    }

    @Test
    void specEnabledWithDeletedOwnedSpecIsRejectedBeforeAnyWrites() {
        product.setSpecEnabled(true);
        UUID deletedSpecId = UUID.randomUUID();
        ProductSpec deletedSpec = new ProductSpec();
        deletedSpec.setId(deletedSpecId);
        deletedSpec.setProductId(productId);
        deletedSpec.setIsDeleted(1);
        when(productSpecRepository.findById(deletedSpecId)).thenReturn(Optional.of(deletedSpec));

        BusinessException exception = assertThrows(BusinessException.class, () -> service.importCardKeys(
                Map.of("product_id", productId.toString(),
                        "spec_id", deletedSpecId.toString(),
                        "content_columns", List.of("A")), UUID.randomUUID()));

        assertEquals(ErrorCode.SPEC_NOT_FOUND, exception.getCode());
        verify(batchRepository, never()).save(any(CardImportBatch.class));
        verify(cardKeyRepository, never()).saveAll(anyList());
    }

    @Test
    void specDisabledWithoutSpecIdImportsNormally() {
        product.setSpecEnabled(false);
        stubBatchSave();

        Map<String, Object> result = service.importCardKeys(
                Map.of("product_id", productId.toString(),
                        "content_columns", List.of("A")), UUID.randomUUID());

        assertEquals(1, result.get("success_count"));
        ArgumentCaptor<List<CardKey>> keys = ArgumentCaptor.forClass(List.class);
        verify(cardKeyRepository).saveAll(keys.capture());
        assertNull(keys.getValue().get(0).getSpecId());
    }

    @Test
    void specDisabledWithAnySpecIdIsRejectedBeforeAnyWrites() {
        product.setSpecEnabled(false);
        UUID specId = UUID.randomUUID();

        BusinessException exception = assertThrows(BusinessException.class, () -> service.importCardKeys(
                Map.of("product_id", productId.toString(),
                        "spec_id", specId.toString(),
                        "content_columns", List.of("A")), UUID.randomUUID()));

        assertEquals(ErrorCode.BAD_REQUEST, exception.getCode());
        verify(batchRepository, never()).save(any(CardImportBatch.class));
        verify(cardKeyRepository, never()).saveAll(anyList());
        verify(productSpecRepository, never()).findById(any());
    }

    @Test
    void singleContentColumnCreatesOnePartKeys() {
        stubBatchSave();
        Map<String, Object> result = service.importCardKeys(
                Map.of("product_id", productId.toString(),
                        "content_columns", List.of(" A \n B ")), UUID.randomUUID());

        assertEquals(2, result.get("total_count"));
        assertEquals(2, result.get("success_count"));
        assertEquals(0, result.get("duplicate_count"));
        assertEquals(0, result.get("fail_count"));
        ArgumentCaptor<List<CardKey>> keys = ArgumentCaptor.forClass(List.class);
        verify(cardKeyRepository).saveAll(keys.capture());
        assertEquals(List.of("A"), keys.getValue().get(0).getContentParts());
        assertEquals(CardKeyStatus.AVAILABLE, keys.getValue().get(0).getStatus());
    }

    @Test
    void currentBatchDuplicatesAreAlwaysFilteredEvenWhenHistoryCheckIsOff() {
        stubBatchSave();
        Map<String, Object> result = service.importCardKeys(
                Map.of("product_id", productId.toString(),
                        "content_columns", List.of("A\nA"),
                        "check_duplicate", false), UUID.randomUUID());

        assertEquals(2, result.get("total_count"));
        assertEquals(1, result.get("success_count"));
        assertEquals(1, result.get("duplicate_count"));
        assertEquals(0, result.get("fail_count"));
        verify(cardKeyRepository, never()).findExistingFingerprints(any(), any(), anyList());
    }

    @Test
    void duplicateCheckOffStillAllowsAHistoricalDuplicate() {
        stubBatchSave();
        Map<String, Object> result = service.importCardKeys(
                Map.of("product_id", productId.toString(),
                        "content_columns", List.of("A"),
                        "check_duplicate", false), UUID.randomUUID());

        assertEquals(1, result.get("success_count"));
        assertEquals(0, result.get("duplicate_count"));
        verify(cardKeyRepository, never()).findExistingFingerprints(any(), any(), anyList());
    }

    @Test
    void historicalDuplicateUsesOneBatchFingerprintQueryAndFullTuple() {
        stubBatchSave();
        String fingerprint = CardContentParser.fingerprint(List.of("1", "a"));
        when(cardKeyRepository.findExistingFingerprints(productId, null,
                List.of(fingerprint, CardContentParser.fingerprint(List.of("2", "b")))))
                .thenReturn(List.of(fingerprint));

        Map<String, Object> result = service.importCardKeys(
                Map.of("product_id", productId.toString(),
                        "content_columns", List.of("1\n2", "a\nb")), UUID.randomUUID());

        assertEquals(2, result.get("total_count"));
        assertEquals(1, result.get("success_count"));
        assertEquals(1, result.get("duplicate_count"));
        assertNotNull(result.get("fail_detail"));
        verify(cardKeyRepository).findExistingFingerprints(productId, null,
                List.of(fingerprint, CardContentParser.fingerprint(List.of("2", "b"))));
    }

    @Test
    void singleCardPickupReturnsOneCardKeyForOneMultiPartCard() {
        UUID orderId = UUID.randomUUID();
        UUID orderItemId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        CardKey key = card(cardId, List.of("账号", "密码"), CardKeyStatus.AVAILABLE);
        stubPickupSaves(orderId, orderItemId);
        when(cardKeyRepository.findById(cardId)).thenReturn(Optional.of(key));
        when(cardKeyRepository.findByIdForUpdate(cardId)).thenReturn(Optional.of(key));

        var result = service.pickupCardKey(cardId, UUID.randomUUID());

        assertEquals(orderId, result.orderId());
        assertEquals(1000000001L, result.orderNo());
        assertEquals(1, result.count());
        assertEquals(1, result.cards().size());
        assertEquals(List.of("账号", "密码"), result.cards().get(0).contentParts());
        assertEquals(CardKeyStatus.SOLD, key.getStatus());
        assertEquals(orderId, key.getOrderId());
        assertEquals(orderItemId, key.getOrderItemId());
        verify(cardKeyRepository).findByIdForUpdate(cardId);
        verify(cardKeyRepository, never()).findAndLockAvailable(any(), any(), anyInt());
    }

    @Test
    void batchPickupCountsCardKeysInsteadOfContentParts() {
        UUID orderId = UUID.randomUUID();
        UUID orderItemId = UUID.randomUUID();
        CardKey key = card(UUID.randomUUID(), List.of("账号", "密码", "二次验证"), CardKeyStatus.AVAILABLE);
        stubPickupSaves(orderId, orderItemId);
        when(cardKeyRepository.findAndLockAvailable(productId, null, 1)).thenReturn(List.of(key));

        var result = service.batchPickup(new AdminCardKeyBatchPickupRequest(productId, null, 1), UUID.randomUUID());

        assertEquals(1, result.count());
        assertEquals(1, result.cards().size());
        assertEquals(orderId, key.getOrderId());
        ArgumentCaptor<OrderItem> itemCaptor = ArgumentCaptor.forClass(OrderItem.class);
        verify(orderItemRepository).saveAndFlush(itemCaptor.capture());
        assertEquals(1, itemCaptor.getValue().getQuantity());
        assertEquals(BigDecimal.ZERO.setScale(2), itemCaptor.getValue().getUnitPrice());
        assertEquals(BigDecimal.ZERO.setScale(2), itemCaptor.getValue().getSubtotal());
    }

    @Test
    void batchPickupReturnsExactlyNCardKeysAndBindsOneOrderItem() {
        UUID orderId = UUID.randomUUID();
        UUID orderItemId = UUID.randomUUID();
        CardKey first = card(UUID.randomUUID(), List.of("A1", "B1"), CardKeyStatus.AVAILABLE);
        CardKey second = card(UUID.randomUUID(), List.of("A2", "B2"), CardKeyStatus.AVAILABLE);
        stubPickupSaves(orderId, orderItemId);
        when(cardKeyRepository.findAndLockAvailable(productId, null, 2)).thenReturn(List.of(first, second));

        var result = service.batchPickup(new AdminCardKeyBatchPickupRequest(productId, null, 2), UUID.randomUUID());

        assertEquals(2, result.count());
        assertEquals(1000000001L, result.orderNo());
        assertEquals(2, result.cards().size());
        assertEquals(List.of(first.getId(), second.getId()),
                result.cards().stream().map(card -> card.id()).toList());
        assertEquals(CardKeyStatus.SOLD, first.getStatus());
        assertEquals(CardKeyStatus.SOLD, second.getStatus());
        assertEquals(orderId, first.getOrderId());
        assertEquals(orderId, second.getOrderId());
        assertEquals(orderItemId, first.getOrderItemId());
        assertEquals(orderItemId, second.getOrderItemId());
        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).saveAndFlush(orderCaptor.capture());
        assertEquals(OrderType.ADMIN_PICKUP, orderCaptor.getValue().getOrderType());
        assertEquals(OrderStatus.DELIVERED, orderCaptor.getValue().getStatus());
        assertEquals(BigDecimal.ZERO.setScale(2), orderCaptor.getValue().getTotalAmount());
        assertEquals(BigDecimal.ZERO.setScale(2), orderCaptor.getValue().getActualAmount());
        assertNotNull(orderCaptor.getValue().getDeliveredAt());
    }

    @Test
    void cardKeyListEnrichesSoldOrderWithBusinessOrderNumber() {
        UUID orderId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        Order order = new Order();
        order.setId(orderId);
        order.setOrderNo(1000000001L);
        CardKey key = card(cardId, List.of("sold"), CardKeyStatus.SOLD);
        key.setOrderId(orderId);
        PageRequest pageable = PageRequest.of(0, 20);
        when(cardKeyRepository.findByProductIdAndOptionalSpecId(productId, null, pageable))
                .thenReturn(new PageImpl<>(List.of(key), pageable, 1));
        when(orderRepository.findByIdIn(List.of(orderId))).thenReturn(List.of(order));

        var result = service.listCardKeys(productId, null, 1, 20);
        Map<?, ?> listed = (Map<?, ?>) result.getList().get(0);

        assertEquals(orderId, listed.get("order_id"));
        assertEquals(1000000001L, listed.get("order_no"));
    }

    @Test
    void batchPickupShortageDoesNotCreateOrderOrMarkPartOfTheBatch() {
        CardKey onlyAvailable = card(UUID.randomUUID(), List.of("only"), CardKeyStatus.AVAILABLE);
        when(productRepository.findByIdForUpdate(productId)).thenReturn(Optional.of(product));
        when(cardKeyRepository.findAndLockAvailable(productId, null, 2)).thenReturn(List.of(onlyAvailable));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.batchPickup(new AdminCardKeyBatchPickupRequest(productId, null, 2), UUID.randomUUID()));

        assertEquals(ErrorCode.INSUFFICIENT_STOCK, exception.getCode());
        assertEquals(CardKeyStatus.AVAILABLE, onlyAvailable.getStatus());
        verify(orderRepository, never()).saveAndFlush(any(Order.class));
        verify(orderItemRepository, never()).saveAndFlush(any(OrderItem.class));
        verify(cardKeyRepository, never()).saveAll(anyList());
    }

    @Test
    void soldOrInvalidCardCannotBePickedUpBySingleCardId() {
        UUID cardId = UUID.randomUUID();
        CardKey key = card(cardId, List.of("secret"), CardKeyStatus.SOLD);
        when(cardKeyRepository.findById(cardId)).thenReturn(Optional.of(key));
        when(cardKeyRepository.findByIdForUpdate(cardId)).thenReturn(Optional.of(key));
        when(productRepository.findByIdForUpdate(productId)).thenReturn(Optional.of(product));

        assertThrows(BusinessException.class, () -> service.pickupCardKey(cardId, UUID.randomUUID()));

        key.setStatus(CardKeyStatus.INVALID);
        assertThrows(BusinessException.class, () -> service.pickupCardKey(cardId, UUID.randomUUID()));
        verify(orderRepository, never()).saveAndFlush(any(Order.class));
        verify(cardKeyRepository, never()).saveAll(anyList());
    }

    private CardKey card(UUID id, List<String> parts, CardKeyStatus status) {
        CardKey key = new CardKey();
        key.setId(id);
        key.setProductId(productId);
        key.setContentParts(parts);
        key.setStatus(status);
        key.setContentFingerprint(CardContentParser.fingerprint(parts));
        return key;
    }
}
