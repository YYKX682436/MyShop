package com.myshop.service.impl;

import com.myshop.constant.CardKeyStatus;
import com.myshop.constant.StockMode;
import com.myshop.entity.CardImportBatch;
import com.myshop.entity.CardKey;
import com.myshop.entity.Product;
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

    @BeforeEach
    void setUp() {
        service = new AdminCardKeyServiceImpl(cardKeyRepository, batchRepository, productRepository,
                productSpecRepository, orderItemRepository, manualStockService);
        productId = UUID.randomUUID();
        Product product = new Product();
        product.setId(productId);
        product.setDeliveryType("AUTO");
        product.setStockMode(StockMode.CARD_KEY);
        product.setIsDeleted(0);
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(batchRepository.save(any(CardImportBatch.class))).thenAnswer(invocation -> {
            CardImportBatch batch = invocation.getArgument(0);
            if (batch.getId() == null) batch.setId(UUID.randomUUID());
            return batch;
        });
    }

    @Test
    void legacyContentRequestCreatesOnePartKeys() {
        Map<String, Object> result = service.importCardKeys(
                Map.of("product_id", productId.toString(), "content", " A \n B "), UUID.randomUUID());

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
