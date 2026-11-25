package com.myshop.model.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.myshop.constant.PricingType;
import com.myshop.entity.UserLevel;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record UserLevelResponse(
        UUID id,
        String name,
        PricingType pricingType,
        BigDecimal markupRate,
        @JsonProperty("is_default") boolean isDefault,
        boolean enabled,
        int sortOrder,
        int upgradeRank,
        boolean selfUpgradeEnabled,
        BigDecimal upgradePrice,
        boolean publicPriceEnabled,
        String remark,
        long userCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static UserLevelResponse from(UserLevel level, long userCount) {
        return new UserLevelResponse(level.getId(), level.getName(), level.getPricingType(),
                level.getMarkupRate(), level.isDefault(), level.isEnabled(), level.getSortOrder(),
                level.getUpgradeRank(), level.isSelfUpgradeEnabled(), level.getUpgradePrice(),
                level.isPublicPriceEnabled(), level.getRemark(), userCount,
                level.getCreatedAt(), level.getUpdatedAt());
    }
}
