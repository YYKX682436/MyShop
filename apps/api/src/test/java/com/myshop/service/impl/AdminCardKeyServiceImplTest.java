package com.myshop.service.impl;

import com.myshop.constant.CardKeyStatus;
import com.myshop.constant.ErrorCode;
import com.myshop.constant.StockMode;
import com.myshop.entity.CardImportBatch;
import com.myshop.entity.CardKey;
import com.myshop.entity.Product;
import com.myshop.entity.ProductSpec;
import com.myshop.exception.BusinessException;
import com.myshop.repository.CardImportBatchRepository;
import com.myshop.repository.CardKeyRepository;
import com.myshop.repository.OrderItemRepository;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminCardKeyServiceImplTest {

    @Mock private CardKeyRepository cardKeyRepository;
    @Mock private CardImportBatchRepository batchRepository;
    @Mock private ProductRepository productRepository;
    @Mock private ProductSpecRepository productSpecRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private ManualStockService manualStockService;

    private AdminCardKeyServiceImpl service;
    private UUID productId;
    private Product product;

    @BeforeEach
    void setUp() {
        service = new AdminCardKeyServiceImpl(cardKeyRepository, batchRepository, productRepository,
                productSpecRepository, orderItemRepository, manualStockService);
        productId = UUID.randomUUID();
        product = new Product();
        product.setId(productId);
        product.setDeliveryType("AUTO");
        product.setStockMode(StockMode.CARD_KEY);
        product.setSpecEnabled(false);
        product.setIsDeleted(0);
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(batchRepository.save(any(CardImportBatch.class))).thenAnswer(invocation -> {
            CardImportBatch batch = invocation.getArgument(0);
            if (batch.getId() == null) batch.setId(UUID.randomUUID());
            return batch;
        });
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
}
