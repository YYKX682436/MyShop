package com.myshop.service;

import com.myshop.constant.ErrorCode;
import com.myshop.constant.PricingSource;
import com.myshop.constant.PricingType;
import com.myshop.entity.UserLevel;
import com.myshop.exception.BusinessException;
import com.myshop.model.pricing.PriceResolution;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PricingCalculatorTest {

    private final PricingCalculator calculator = new PricingCalculator();

    @Test
    void guestAndRetailLevelUseRetailPrice() {
        PriceResolution guest = calculator.calculate(money("80"), money("100"), null, null);
        PriceResolution retail = calculator.calculate(money("80"), money("100"), level(PricingType.RETAIL, null), null);

        assertEquals(money("100.00"), guest.price());
        assertEquals(PricingSource.RETAIL, guest.pricingSource());
        assertEquals(money("100.00"), retail.price());
        assertEquals(PricingSource.RETAIL, retail.pricingSource());
    }

    @Test
    void costMarkupUsesHalfUpAndCapsAtRetail() {
        PriceResolution rounded = calculator.calculate(money("10.01"), money("99.99"),
                level(PricingType.COST_MARKUP, money("5")), null);
        PriceResolution capped = calculator.calculate(money("90"), money("100"),
                level(PricingType.COST_MARKUP, money("20")), null);

        assertEquals(money("10.51"), rounded.price());
        assertEquals(PricingSource.LEVEL_MARKUP, rounded.pricingSource());
        assertEquals(money("108.00"), capped.calculatedPrice());
        assertEquals(money("100.00"), capped.price());
        assertEquals(PricingSource.RETAIL, capped.pricingSource());
        org.junit.jupiter.api.Assertions.assertTrue(capped.retailCapped());
    }

    @Test
    void overrideWinsAndMayBeBelowCostButNeverAboveRetail() {
        UserLevel level = level(PricingType.COST_MARKUP, money("50"));
        PriceResolution override = calculator.calculate(money("80"), money("100"), level, money("70"));

        assertEquals(money("70.00"), override.price());
        assertEquals(PricingSource.LEVEL_OVERRIDE, override.pricingSource());
        assertEquals(money("80.00"), override.costPrice());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> calculator.calculate(money("80"), money("100"), level, money("100.01")));
        assertEquals(ErrorCode.BAD_REQUEST, exception.getCode());
    }

    @Test
    void costChangeRecalculatesDerivedPriceWhileOverrideStaysFixed() {
        UserLevel level = level(PricingType.COST_MARKUP, money("10"));
        PriceResolution first = calculator.calculate(money("50"), money("100"), level, null);
        PriceResolution changed = calculator.calculate(money("60"), money("100"), level, null);
        PriceResolution fixed = calculator.calculate(money("60"), money("100"), level, money("65"));

        assertEquals(money("55.00"), first.price());
        assertEquals(money("66.00"), changed.price());
        assertEquals(money("65.00"), fixed.price());
        assertEquals(PricingSource.LEVEL_OVERRIDE, fixed.pricingSource());
    }

    private UserLevel level(PricingType type, BigDecimal markup) {
        UserLevel level = new UserLevel();
        level.setPricingType(type);
        level.setMarkupRate(markup);
        level.setEnabled(true);
        return level;
    }

    private BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2);
    }
}
