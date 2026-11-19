package com.myshop.service.impl;

import com.myshop.constant.ErrorCode;
import com.myshop.constant.UserRole;
import com.myshop.entity.Product;
import com.myshop.entity.ProductSpec;
import com.myshop.entity.User;
import com.myshop.entity.UserLevel;
import com.myshop.entity.UserSecretPrice;
import com.myshop.exception.BusinessException;
import com.myshop.model.pricing.PriceResolution;
import com.myshop.model.pricing.PriceTarget;
import com.myshop.model.request.UserSecretPriceRequest;
import com.myshop.model.response.UserSecretPriceResponse;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.ProductSpecRepository;
import com.myshop.repository.UserRepository;
import com.myshop.repository.UserLevelRepository;
import com.myshop.repository.UserSecretPriceRepository;
import com.myshop.service.PricingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserSecretPriceService {
    private final UserSecretPriceRepository secrets;
    private final UserRepository users;
    private final UserLevelRepository levels;
    private final ProductRepository products;
    private final ProductSpecRepository specs;
    private final PricingService pricing;

    @Transactional(readOnly = true)
    public List<UserSecretPriceResponse> list(UUID userId) {
        User user = requireUser(userId);
        List<UserSecretPrice> entries = secrets.findByUserIdOrderByCreatedAtDesc(userId);
        if (entries.isEmpty()) return List.of();
        Map<UUID, Product> productMap = products.findAllById(entries.stream().map(UserSecretPrice::getProductId).distinct().toList())
                .stream().collect(Collectors.toMap(Product::getId, p -> p));
        List<UUID> specIds = entries.stream().map(UserSecretPrice::getSpecId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<UUID, ProductSpec> specMap = specs.findAllById(specIds).stream().collect(Collectors.toMap(ProductSpec::getId, s -> s));
        Map<UUID, List<ProductSpec>> byProduct = specMap.values().stream().collect(Collectors.groupingBy(ProductSpec::getProductId));
        UserLevel level = levelFor(user);
        Map<PriceTarget, PriceResolution> prices = pricing.resolveBatch(productMap.values(), byProduct, level);
        return entries.stream().map(entry -> {
            Product product = productMap.get(entry.getProductId());
            ProductSpec spec = entry.getSpecId() == null ? null : specMap.get(entry.getSpecId());
            PriceResolution levelPrice = prices.get(new PriceTarget(entry.getProductId(), entry.getSpecId()));
            if (product == null || (entry.getSpecId() != null && spec == null) || levelPrice == null) {
                throw new IllegalStateException("Invalid secret price target");
            }
            return response(entry, product, spec, levelPrice);
        }).toList();
    }

    @Transactional
    public UserSecretPriceResponse create(UUID userId, UserSecretPriceRequest request) {
        User user = requireUser(userId);
        if (request == null || request.productId() == null) throw bad("请选择商品");
        Product product = lockProduct(request.productId());
        ProductSpec spec = validateTarget(product, request.specId());
        BigDecimal value = validatePrice(request.price(), spec == null ? product.getRetailPrice() : spec.getRetailPrice());
        if (secrets.findByUserIdAndProductIdAndSpecId(userId, product.getId(), request.specId()).isPresent()) {
            throw bad("该商品规格已有密价，请修改现有记录");
        }
        UserSecretPrice entry = new UserSecretPrice();
        entry.setUserId(userId);
        entry.setProductId(product.getId());
        entry.setSpecId(request.specId());
        entry.setPrice(value);
        secrets.saveAndFlush(entry);
        return response(entry, product, spec, pricing.resolve(product, spec, levelFor(user)));
    }

    @Transactional
    public UserSecretPriceResponse update(UUID userId, UUID id, UserSecretPriceRequest request) {
        User user = requireUser(userId);
        UserSecretPrice entry = requireEntry(userId, id);
        Product product = lockProduct(entry.getProductId());
        ProductSpec spec = validateTarget(product, entry.getSpecId());
        if (request == null || (request.productId() != null && !request.productId().equals(entry.getProductId()))
                || (request.specId() != null && !request.specId().equals(entry.getSpecId()))) throw bad("不能更改密价绑定的商品或规格");
        entry.setPrice(validatePrice(request.price(), spec == null ? product.getRetailPrice() : spec.getRetailPrice()));
        secrets.saveAndFlush(entry);
        return response(entry, product, spec, pricing.resolve(product, spec, levelFor(user)));
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        requireUser(userId);
        UserSecretPrice entry = requireEntry(userId, id);
        lockProduct(entry.getProductId());
        secrets.delete(entry);
        secrets.flush();
    }

    private User requireUser(UUID userId) {
        return users.findById(userId).filter(u -> u.getRole() == UserRole.USER)
                .orElseThrow(() -> bad("用户不存在"));
    }
    private UserLevel levelFor(User user) {
        return user.getUserLevelId() == null ? null : levels.findById(user.getUserLevelId())
                .filter(UserLevel::isEnabled).orElse(null);
    }
    private UserSecretPrice requireEntry(UUID userId, UUID id) {
        return secrets.findByIdAndUserId(id, userId).orElseThrow(() -> bad("密价不存在"));
    }
    private Product lockProduct(UUID id) {
        return products.findByIdForUpdate(id).filter(p -> p.getIsDeleted() == 0)
                .orElseThrow(() -> bad("商品不存在"));
    }
    private ProductSpec validateTarget(Product product, UUID specId) {
        if (!product.isSpecEnabled()) {
            if (specId != null) throw bad("无规格商品不能绑定规格");
            return null;
        }
        if (specId == null) throw bad("请选择具体规格");
        return specs.findById(specId).filter(s -> s.getIsDeleted() == 0 && s.getProductId().equals(product.getId()))
                .orElseThrow(() -> bad("规格不存在或不属于该商品"));
    }
    private BigDecimal validatePrice(BigDecimal price, BigDecimal retail) {
        if (price == null || price.scale() > 2 || price.signum() <= 0 || price.compareTo(retail) > 0) {
            throw bad("密价必须大于 0、最多两位小数且不能高于零售价");
        }
        return price.setScale(2, RoundingMode.UNNECESSARY);
    }
    private UserSecretPriceResponse response(UserSecretPrice entry, Product product, ProductSpec spec, PriceResolution level) {
        BigDecimal cost = spec == null ? product.getCostPrice() : spec.getCostPrice();
        return new UserSecretPriceResponse(entry.getId(), product.getId(), product.getTitle(), entry.getSpecId(),
                spec == null ? null : spec.getName(), entry.getPrice(), level.price(), level.retailPrice(), cost,
                cost != null && entry.getPrice().compareTo(cost) < 0);
    }
    private BusinessException bad(String message) { return new BusinessException(ErrorCode.BAD_REQUEST, message); }
}
