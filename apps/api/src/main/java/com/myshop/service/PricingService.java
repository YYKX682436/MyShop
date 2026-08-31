package com.myshop.service;

import com.myshop.entity.Product;
import com.myshop.entity.ProductSpec;
import com.myshop.entity.User;
import com.myshop.entity.UserLevel;
import com.myshop.model.pricing.PriceResolution;
import com.myshop.model.pricing.PriceTarget;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Shared persistence-aware pricing boundary for every product/order surface. */
public interface PricingService {

    UserLevel resolvePublicLevel(UUID requestUserId);

    UserLevel requireActiveLevel(User user);

    PriceResolution resolve(Product product, ProductSpec spec, UserLevel level);

    PriceResolution resolve(Product product, ProductSpec spec, UserLevel level,
                            Collection<com.myshop.entity.ProductLevelPriceOverride> overrides);

    Map<PriceTarget, PriceResolution> resolveBatch(Collection<Product> products,
                                                   Map<UUID, List<ProductSpec>> specsByProduct,
                                                   UserLevel level);
}
