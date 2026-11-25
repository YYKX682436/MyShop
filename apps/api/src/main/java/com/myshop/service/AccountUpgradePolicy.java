package com.myshop.service;

import com.myshop.entity.UserLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Resolves a level's purchase and price-visibility rules for the active site. */
@Service
@RequiredArgsConstructor
public class AccountUpgradePolicy {
    private static final BigDecimal DEFAULT_LEVEL_PRICE = new BigDecimal("0.00");

    private final JdbcTemplate jdbc;

    public record Rule(boolean selfUpgradeEnabled, BigDecimal upgradePrice, boolean publicPriceEnabled) { }

    @Transactional(readOnly = true)
    public BigDecimal price(UUID subsiteId, UserLevel level) {
        if (level == null) return null;
        if (level.isDefault()) return DEFAULT_LEVEL_PRICE;
        if (subsiteId == null) return level.getUpgradePrice();
        return rule(subsiteId, level.getId()).upgradePrice();
    }

    @Transactional(readOnly = true)
    public boolean selfUpgradeEnabled(UUID subsiteId, UserLevel level) {
        if (level == null || !level.isEnabled() || level.isDefault()) return false;
        return subsiteId == null ? level.isSelfUpgradeEnabled()
                : rule(subsiteId, level.getId()).selfUpgradeEnabled();
    }

    @Transactional(readOnly = true)
    public boolean publicPriceEnabled(UUID subsiteId, UserLevel level) {
        if (level == null || !level.isEnabled() || level.isDefault() || price(subsiteId, level) == null
                || !selfUpgradeEnabled(subsiteId, level)) return false;
        if (subsiteId == null) return level.isPublicPriceEnabled();
        // Main-site privacy is the ceiling; a subsite may hide a public price,
        // but cannot reveal a level the platform marks as internal.
        return level.isPublicPriceEnabled() && rule(subsiteId, level.getId()).publicPriceEnabled();
    }

    public Rule rule(UUID subsiteId, UUID levelId) {
        if (subsiteId == null || levelId == null) return new Rule(false, null, false);
        List<Rule> rows = jdbc.query("""
                SELECT self_upgrade_enabled, upgrade_price, public_price_enabled
                FROM subsite_level_upgrade_rules
                WHERE subsite_id = ? AND user_level_id = ?
                """, (rs, rowNum) -> new Rule(rs.getBoolean(1), rs.getBigDecimal(2), rs.getBoolean(3)),
                subsiteId, levelId);
        return rows.isEmpty() ? new Rule(false, null, false) : rows.get(0);
    }
}
