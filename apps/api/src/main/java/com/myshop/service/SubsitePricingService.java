package com.myshop.service;

import com.myshop.constant.ErrorCode;
import com.myshop.constant.PricingSource;
import com.myshop.entity.Product;
import com.myshop.entity.ProductSpec;
import com.myshop.entity.Subsite;
import com.myshop.entity.UserLevel;
import com.myshop.exception.BusinessException;
import com.myshop.repository.SubsiteSettingsRepository;
import com.myshop.repository.UserLevelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

/** All subsite prices are calculated on the server; the purchase and sales prices remain separate. */
@Service
@RequiredArgsConstructor
public class SubsitePricingService {
    private final PricingService pricingService;
    private final SubsiteSettingsRepository settingsRepository;
    private final UserLevelRepository levelRepository;
    private final JdbcTemplate jdbc;

    public record SubsitePrice(BigDecimal unitPrice, BigDecimal supplyPrice, BigDecimal costPrice,
                               BigDecimal retailPrice, PricingSource pricingSource) {}

    @Transactional(readOnly = true)
    public SubsitePrice resolve(Subsite site, Product product, ProductSpec spec, UserLevel customerLevel, UUID customerId) {
        UUID levelId = settingsRepository.findById(1).map(s -> s.getSupplyLevelId())
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "尚未设置分站供货等级"));
        UserLevel supplyLevel = levelRepository.findById(levelId).filter(UserLevel::isEnabled)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "分站供货等级无效"));
        BigDecimal supply = pricingService.resolveForUser(product, spec, supplyLevel, site.getOwnerId()).price();
        BigDecimal retail = spec == null ? product.getRetailPrice() : spec.getRetailPrice();
        BigDecimal cost = spec == null ? product.getCostPrice() : spec.getCostPrice();
        if (site.getOwnerId().equals(customerId)) return new SubsitePrice(supply, supply, cost, retail, PricingSource.SUBSITE);
        UUID specId = spec == null ? null : spec.getId();
        BigDecimal explicit = null;
        if (customerLevel != null) {
            explicit = price(site.getId(), product.getId(), specId, customerLevel.getId());
        }
        if (explicit == null && customerLevel != null) {
            List<BigDecimal> markups = jdbc.queryForList("SELECT markup_rate FROM subsite_level_markups WHERE subsite_id = ? AND user_level_id = ?", BigDecimal.class, site.getId(), customerLevel.getId());
            if (!markups.isEmpty()) explicit = supply.multiply(BigDecimal.ONE.add(markups.get(0).movePointLeft(2))).setScale(2, RoundingMode.HALF_UP);
        }
        if (explicit == null) explicit = price(site.getId(), product.getId(), specId, null);
        return new SubsitePrice(explicit == null ? retail : explicit, supply, cost, retail,
                explicit == null ? PricingSource.RETAIL : PricingSource.SUBSITE);
    }

    @Transactional(readOnly = true)
    public boolean isHidden(UUID siteId, UUID productId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM subsite_hidden_products WHERE subsite_id = ? AND product_id = ?)", Boolean.class, siteId, productId));
    }

    private BigDecimal price(UUID siteId, UUID productId, UUID specId, UUID levelId) {
        List<BigDecimal> prices = jdbc.queryForList("SELECT price FROM subsite_product_prices WHERE subsite_id = ? AND product_id = ? AND spec_id IS NOT DISTINCT FROM ? AND user_level_id IS NOT DISTINCT FROM ?", BigDecimal.class, siteId, productId, specId, levelId);
        return prices.isEmpty() ? null : prices.get(0);
    }
}
