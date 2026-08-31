package com.myshop.service;

import com.myshop.constant.ErrorCode;
import com.myshop.constant.PricingSource;
import com.myshop.constant.PricingType;
import com.myshop.entity.UserLevel;
import com.myshop.exception.BusinessException;
import com.myshop.model.pricing.PriceResolution;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure pricing rules. It has no persistence and is intentionally the only
 * place that contains the cost-plus formula and money rounding policy.
 */
public class PricingCalculator {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    public PriceResolution calculate(BigDecimal costPrice,
                                     BigDecimal retailPrice,
                                     UserLevel level,
                                     BigDecimal overridePrice) {
        BigDecimal retail = money(retailPrice, "零售价必须大于 0");
        if (retail.signum() <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "零售价必须大于 0");
        }

        if (overridePrice != null) {
            BigDecimal override = money(overridePrice, "特殊价格式无效");
            if (override.signum() <= 0 || override.compareTo(retail) > 0) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "特殊价必须大于 0 且不能高于零售价");
            }
            return new PriceResolution(override, nullableMoney(costPrice), retail,
                    PricingSource.LEVEL_OVERRIDE, false, null,
                    level == null ? null : level.getId(),
                    level == null ? null : level.getName());
        }

        if (level == null || level.getPricingType() == PricingType.RETAIL) {
            return new PriceResolution(retail, nullableMoney(costPrice), retail,
                    PricingSource.RETAIL, false, null,
                    level == null ? null : level.getId(),
                    level == null ? null : level.getName());
        }

        if (level.getPricingType() != PricingType.COST_MARKUP || level.getMarkupRate() == null
                || level.getMarkupRate().signum() < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户等级定价规则无效");
        }
        BigDecimal markupRate;
        try {
            markupRate = level.getMarkupRate().setScale(4, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "加价率最多支持 4 位小数");
        }
        BigDecimal cost = money(costPrice, "成本价不能为空");
        if (cost.signum() < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "成本价不能小于 0");
        }
        BigDecimal calculated = cost
                .multiply(ONE_HUNDRED.add(markupRate))
                .divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);
        if (calculated.compareTo(retail) > 0) {
            return new PriceResolution(retail, cost, retail, PricingSource.RETAIL,
                    true, calculated, level.getId(), level.getName());
        }
        return new PriceResolution(calculated, cost, retail, PricingSource.LEVEL_MARKUP,
                false, calculated, level.getId(), level.getName());
    }

    private BigDecimal money(BigDecimal value, String message) {
        if (value == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, message);
        }
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "金额最多支持两位小数");
        }
    }

    private BigDecimal nullableMoney(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }
}
