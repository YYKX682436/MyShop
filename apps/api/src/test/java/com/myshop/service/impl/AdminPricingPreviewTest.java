package com.myshop.service.impl;

import com.myshop.constant.PricingPreviewStatus;
import com.myshop.constant.PricingType;
import com.myshop.entity.UserLevel;
import com.myshop.model.request.AdminPricingPreviewOverrideRequest;
import com.myshop.model.request.AdminPricingPreviewRequest;
import com.myshop.model.request.AdminPricingPreviewSpecRequest;
import com.myshop.repository.CardKeyRepository;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.ProductCategoryRepository;
import com.myshop.repository.ProductLevelPriceOverrideRepository;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.ProductSpecRepository;
import com.myshop.repository.UserLevelRepository;
import com.myshop.service.ManualStockService;
import com.myshop.service.ProductInventoryBatchService;
import com.myshop.service.PricingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminPricingPreviewTest {

    @Mock private ProductRepository productRepository;
    @Mock private ProductSpecRepository productSpecRepository;
    @Mock private ProductLevelPriceOverrideRepository overrideRepository;
    @Mock private UserLevelRepository userLevelRepository;
    @Mock private ProductCategoryRepository categoryRepository;
    @Mock private CardKeyRepository cardKeyRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private ProductInventoryBatchService inventoryBatchService;
    @Mock private PricingService pricingService;
    @Mock private ManualStockService manualStockService;

    private AdminProductServiceImpl service() {
        return new AdminProductServiceImpl(productRepository, productSpecRepository, overrideRepository,
                userLevelRepository, categoryRepository, cardKeyRepository, orderItemRepository,
                inventoryBatchService, pricingService, manualStockService);
    }

    @Test
    void costOnlyDraftReturnsMarkupRuleAndWaitsForRetailPrice() {
        UUID levelId = UUID.randomUUID();
        when(userLevelRepository.findAllOrdered()).thenReturn(List.of(level(levelId, PricingType.COST_MARKUP, "5")));

        var response = service().previewPricing(new AdminPricingPreviewRequest(
                money("10.01"), null, false, List.of(), List.of()));

        var row = response.pricingMatrix().get(0);
        assertEquals(money("10.51"), row.rulePrice());
        assertEquals(null, row.finalPrice());
        assertEquals(PricingPreviewStatus.WAITING_RETAIL, row.pricingStatus());
    }

    @Test
    void costAndRetailDraftUsesRoundedRuleAndRetailCap() {
        UUID levelId = UUID.randomUUID();
        when(userLevelRepository.findAllOrdered()).thenReturn(List.of(level(levelId, PricingType.COST_MARKUP, "20")));

        var response = service().previewPricing(new AdminPricingPreviewRequest(
                money("90"), money("100"), false, List.of(), List.of()));

        var row = response.pricingMatrix().get(0);
        assertEquals(money("108.00"), row.rulePrice());
        assertEquals(money("100.00"), row.finalPrice());
        assertTrue(row.retailCapped());
        assertEquals(PricingPreviewStatus.READY, row.pricingStatus());
    }

    @Test
    void retailLevelWaitsForRetailPriceWithoutRequiringOtherProductFields() {
        UUID levelId = UUID.randomUUID();
        when(userLevelRepository.findAllOrdered()).thenReturn(List.of(level(levelId, PricingType.RETAIL, "ignored")));

        var response = service().previewPricing(new AdminPricingPreviewRequest(
                null, null, false, null, null));

        var row = response.pricingMatrix().get(0);
        assertEquals(null, row.rulePrice());
        assertEquals(null, row.finalPrice());
        assertEquals(PricingPreviewStatus.WAITING_RETAIL, row.pricingStatus());
    }

    @Test
    void incompleteSpecificationDoesNotBlockAnotherSpecificationPreview() {
        UUID levelId = UUID.randomUUID();
        UUID completeId = UUID.randomUUID();
        UUID incompleteId = UUID.randomUUID();
        when(userLevelRepository.findAllOrdered()).thenReturn(List.of(level(levelId, PricingType.COST_MARKUP, "5")));

        var response = service().previewPricing(new AdminPricingPreviewRequest(
                null, null, true,
                List.of(
                        new AdminPricingPreviewSpecRequest(completeId, money("10"), money("20")),
                        new AdminPricingPreviewSpecRequest(incompleteId, money("15"), null)),
                List.of()));

        var complete = response.pricingMatrix().stream().filter(row -> completeId.equals(row.specId())).findFirst().orElseThrow();
        var incomplete = response.pricingMatrix().stream().filter(row -> incompleteId.equals(row.specId())).findFirst().orElseThrow();
        assertEquals(money("10.50"), complete.rulePrice());
        assertEquals(money("10.50"), complete.finalPrice());
        assertEquals(money("15.75"), incomplete.rulePrice());
        assertEquals(null, incomplete.finalPrice());
        assertEquals(PricingPreviewStatus.WAITING_RETAIL, incomplete.pricingStatus());
    }

    @Test
    void overrideIsUsedForActualPriceAndMayProduceBelowCostWarning() {
        UUID levelId = UUID.randomUUID();
        when(userLevelRepository.findAllOrdered()).thenReturn(List.of(level(levelId, PricingType.COST_MARKUP, "10")));

        var response = service().previewPricing(new AdminPricingPreviewRequest(
                money("80"), money("100"), false, List.of(),
                List.of(new AdminPricingPreviewOverrideRequest(levelId, null, money("70")))));

        var row = response.pricingMatrix().get(0);
        assertEquals(money("88.00"), row.rulePrice());
        assertEquals(money("70.00"), row.overridePrice());
        assertEquals(money("70.00"), row.finalPrice());
        assertTrue(row.belowCost());
        assertEquals(1, response.warnings().size());
    }

    private UserLevel level(UUID id, PricingType type, String markup) {
        UserLevel level = new UserLevel();
        level.setId(id);
        level.setName(type.name());
        level.setPricingType(type);
        level.setMarkupRate(type == PricingType.COST_MARKUP ? money(markup) : null);
        level.setEnabled(true);
        return level;
    }

    private BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2);
    }
}
