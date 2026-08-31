package com.myshop.service.impl;

import com.myshop.constant.ErrorCode;
import com.myshop.constant.UserRole;
import com.myshop.entity.Product;
import com.myshop.entity.ProductLevelPriceOverride;
import com.myshop.entity.ProductSpec;
import com.myshop.entity.User;
import com.myshop.entity.UserLevel;
import com.myshop.exception.BusinessException;
import com.myshop.model.pricing.PriceResolution;
import com.myshop.model.pricing.PriceTarget;
import com.myshop.repository.ProductLevelPriceOverrideRepository;
import com.myshop.repository.UserLevelRepository;
import com.myshop.repository.UserRepository;
import com.myshop.service.PricingCalculator;
import com.myshop.service.PricingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PricingServiceImpl implements PricingService {

    private final UserRepository userRepository;
    private final UserLevelRepository userLevelRepository;
    private final ProductLevelPriceOverrideRepository overrideRepository;
    private final PricingCalculator calculator = new PricingCalculator();

    @Override
    @Transactional(readOnly = true)
    public UserLevel resolvePublicLevel(UUID requestUserId) {
        if (requestUserId == null) return null;
        return userRepository.findById(requestUserId)
                .filter(user -> user.getIsDeleted() == 0 && user.getRole() == UserRole.USER)
                .map(User::getUserLevelId)
                .flatMap(userLevelRepository::findById)
                .filter(UserLevel::isEnabled)
                .orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public UserLevel requireActiveLevel(User user) {
        if (user == null || user.getRole() != UserRole.USER || user.getIsDeleted() != 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "登录账号不存在或已被禁用");
        }
        if (user.getUserLevelId() == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "当前账号未分配有效用户等级");
        }
        return userLevelRepository.findById(user.getUserLevelId())
                .filter(UserLevel::isEnabled)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "当前用户等级已停用"));
    }

    @Override
    @Transactional(readOnly = true)
    public PriceResolution resolve(Product product, ProductSpec spec, UserLevel level) {
        List<ProductLevelPriceOverride> overrides = level == null
                ? List.of()
                : overrideRepository == null ? List.of()
                : overrideRepository.findByProductIdAndUserLevelId(product.getId(), level.getId());
        return resolve(product, spec, level, overrides);
    }

    @Override
    public PriceResolution resolve(Product product, ProductSpec spec, UserLevel level,
                                   Collection<ProductLevelPriceOverride> overrides) {
        UUID specId = spec == null ? null : spec.getId();
        ProductLevelPriceOverride selected = overrides == null ? null : overrides.stream()
                .filter(override -> java.util.Objects.equals(override.getSpecId(), specId))
                .findFirst().orElse(null);
        if (spec == null) {
            return calculator.calculate(product.getCostPrice(), product.getRetailPrice(), level,
                    selected == null ? null : selected.getPrice());
        }
        return calculator.calculate(spec.getCostPrice(), spec.getRetailPrice(), level,
                selected == null ? null : selected.getPrice());
    }

    @Override
    @Transactional(readOnly = true)
    public Map<PriceTarget, PriceResolution> resolveBatch(Collection<Product> products,
                                                           Map<UUID, List<ProductSpec>> specsByProduct,
                                                           UserLevel level) {
        if (products == null || products.isEmpty()) return Map.of();
        Map<UUID, List<ProductLevelPriceOverride>> overridesByProduct = new HashMap<>();
        if (level != null && overrideRepository != null) {
            overridesByProduct = overrideRepository
                    .findByProductIdInAndUserLevelId(products.stream().map(Product::getId).toList(), level.getId())
                    .stream().collect(Collectors.groupingBy(ProductLevelPriceOverride::getProductId));
        }
        Map<PriceTarget, PriceResolution> result = new HashMap<>();
        for (Product product : products) {
            List<ProductLevelPriceOverride> overrides = overridesByProduct
                    .getOrDefault(product.getId(), List.of());
            List<ProductSpec> specs = specsByProduct.getOrDefault(product.getId(), List.of());
            if (product.isSpecEnabled() && !specs.isEmpty()) {
                for (ProductSpec spec : specs) {
                    result.put(new PriceTarget(product.getId(), spec.getId()),
                            resolve(product, spec, level, overrides));
                }
            } else {
                result.put(new PriceTarget(product.getId(), null),
                        resolve(product, null, level, overrides));
            }
        }
        return result;
    }
}
