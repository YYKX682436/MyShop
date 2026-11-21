package com.myshop.service;

import com.myshop.constant.PricingSource;
import com.myshop.entity.*;
import com.myshop.exception.BusinessException;
import com.myshop.model.pricing.PriceResolution;
import com.myshop.repository.SubsiteSettingsRepository;
import com.myshop.repository.UserLevelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SubsitePricingServiceTest {
    private final PricingService pricing = mock(PricingService.class);
    private final SubsiteSettingsRepository settings = mock(SubsiteSettingsRepository.class);
    private final UserLevelRepository levels = mock(UserLevelRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SubsitePricingService service = new SubsitePricingService(pricing, settings, levels, jdbc);
    private final UUID owner = UUID.randomUUID();
    private final UUID supplyLevelId = UUID.randomUUID();
    private final Subsite site = new Subsite();
    private final Product product = new Product();
    private final UserLevel supplyLevel = new UserLevel();
    private final UserLevel customerLevel = new UserLevel();

    @BeforeEach void setup() {
        site.setId(UUID.randomUUID()); site.setOwnerId(owner);
        product.setId(UUID.randomUUID()); product.setRetailPrice(new BigDecimal("15.00")); product.setCostPrice(new BigDecimal("8.00"));
        supplyLevel.setId(supplyLevelId); customerLevel.setId(UUID.randomUUID());
        SubsiteSettings config = new SubsiteSettings(); config.setSupplyLevelId(supplyLevelId);
        when(settings.findById(1)).thenReturn(Optional.of(config));
        when(levels.findById(supplyLevelId)).thenReturn(Optional.of(supplyLevel));
        when(pricing.resolveForUser(product, null, supplyLevel, owner)).thenReturn(new PriceResolution(new BigDecimal("10.00"), new BigDecimal("8.00"), new BigDecimal("15.00"), PricingSource.USER_SECRET, false, null, supplyLevelId, "代理"));
        when(jdbc.queryForList(anyString(), eq(BigDecimal.class), any(), any(), any(), any())).thenReturn(List.of());
        when(jdbc.queryForList(anyString(), eq(BigDecimal.class), any(), any())).thenReturn(List.of());
    }
    @Test void guestAndUnconfiguredLevelUseMainRetail() {
        assertEquals(new BigDecimal("15.00"), service.resolve(site, product, null, null, null).unitPrice());
        var price = service.resolve(site, product, null, customerLevel, UUID.randomUUID());
        assertEquals(new BigDecimal("15.00"), price.unitPrice());
        assertEquals(new BigDecimal("10.00"), price.supplyPrice());
        assertEquals(new BigDecimal("8.00"), price.costPrice());
        verify(pricing, times(2)).resolveForUser(product, null, supplyLevel, owner);
    }
    @Test void ownerPaysSupplyWithoutProfit() {
        assertEquals(new BigDecimal("10.00"), service.resolve(site, product, null, customerLevel, owner).unitPrice());
    }
    @Test void levelSpecialOverridesConfiguredMarkup() {
        when(jdbc.queryForList(contains("subsite_product_prices"), eq(BigDecimal.class), eq(site.getId()), eq(product.getId()), isNull(), eq(customerLevel.getId())))
                .thenReturn(List.of(new BigDecimal("17.00")));
        assertEquals(new BigDecimal("17.00"), service.resolve(site, product, null, customerLevel, UUID.randomUUID()).unitPrice());
    }
    @Test void configuredMarkupIsActivePricingRule() {
        when(jdbc.queryForList(contains("subsite_level_markups"), eq(BigDecimal.class), eq(site.getId()), eq(customerLevel.getId())))
                .thenReturn(List.of(new BigDecimal("20.0000")));
        assertEquals(new BigDecimal("12.00"), service.resolve(site, product, null, customerLevel, UUID.randomUUID()).unitPrice());
    }
    @Test void absentSupplyLevelFailsClosed() {
        when(settings.findById(1)).thenReturn(Optional.empty());
        assertThrows(BusinessException.class, () -> service.resolve(site, product, null, null, null));
    }
}
