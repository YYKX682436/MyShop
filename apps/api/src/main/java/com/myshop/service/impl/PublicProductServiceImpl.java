package com.myshop.service.impl;

import com.myshop.common.PageResult;
import com.myshop.constant.ErrorCode;
import com.myshop.entity.Product;
import com.myshop.entity.ProductCategory;
import com.myshop.entity.ProductSpec;
import com.myshop.entity.UserLevel;
import com.myshop.exception.BusinessException;
import com.myshop.model.CustomerInputField;
import com.myshop.model.ProductCategoryPath;
import com.myshop.model.pricing.PriceResolution;
import com.myshop.model.pricing.PriceTarget;
import com.myshop.model.response.PublicProductResponse;
import com.myshop.model.response.PublicProductSpecResponse;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.ProductCategoryRepository;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.ProductSalesProjection;
import com.myshop.repository.ProductSpecRepository;
import com.myshop.service.ProductInventoryBatchService;
import com.myshop.service.ProductListSort;
import com.myshop.service.PricingService;
import com.myshop.service.ProductRules;
import com.myshop.service.PublicProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PublicProductServiceImpl implements PublicProductService {

    private static final int MAX_PAGE_SIZE = 100;

    private final ProductRepository productRepository;
    private final ProductSpecRepository productSpecRepository;
    private final ProductCategoryRepository categoryRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductInventoryBatchService inventoryBatchService;
    private final PricingService pricingService;

    @Override
    @Transactional(readOnly = true)
    public PageResult<PublicProductResponse> listProducts(UUID categoryId, String keyword,
                                                          int page, int pageSize, UUID requestUserId) {
        validatePage(page, pageSize);
        PageRequest pageable = PageRequest.of(page - 1, pageSize,
                ProductListSort.resolve("sort_weight", "desc"));
        String normalizedKeyword = keyword == null ? null : keyword.trim();
        Long productNo = parseProductNo(normalizedKeyword);
        Page<Product> productPage = normalizedKeyword != null && !normalizedKeyword.isBlank()
                ? productRepository.findPublicProductsByKeyword(categoryId, "%" + normalizedKeyword.toLowerCase(Locale.ROOT) + "%", productNo, pageable)
                : productRepository.findPublicProducts(categoryId, pageable);
        return PageResult.of(productPage, buildResponses(productPage.getContent(), requestUserId, false));
    }

    @Override
    @Transactional(readOnly = true)
    public PublicProductResponse getProduct(UUID id, UUID requestUserId) {
        Product product = productRepository.findById(id)
                .filter(candidate -> candidate.getIsDeleted() == 0 && candidate.isEnabled())
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "商品不存在或已下架"));
        return buildResponses(List.of(product), requestUserId, true).get(0);
    }

    private List<PublicProductResponse> buildResponses(Collection<Product> products,
                                                       UUID requestUserId, boolean detail) {
        if (products.isEmpty()) return List.of();
        List<UUID> productIds = products.stream().map(Product::getId).toList();
        List<ProductSpec> specs = productSpecRepository
                .findByProductIdInAndIsDeletedAndVisibleOrderByProductIdAscSortOrderAsc(productIds, 0, true);
        Map<UUID, List<ProductSpec>> specsByProduct = specs.stream()
                .collect(java.util.stream.Collectors.groupingBy(ProductSpec::getProductId,
                        LinkedHashMap::new, java.util.stream.Collectors.toList()));
        UserLevel level = pricingService.resolvePublicLevel(requestUserId);
        Map<PriceTarget, PriceResolution> prices = pricingService.resolveBatch(products, specsByProduct, level);
        Map<PriceTarget, ProductInventoryBatchService.StockSnapshot> stock =
                inventoryBatchService.load(products, specsByProduct);
        Map<UUID, Long> sales = loadSales(productIds);
        Map<UUID, ProductCategory> categories = categoryRepository
                .findByIsDeletedOrderBySortOrderAscIdAsc(0).stream()
                .collect(java.util.stream.Collectors.toMap(ProductCategory::getId, category -> category));

        return products.stream().map(product -> {
                    ProductCategory category = categories.get(product.getCategoryId());
                    return toResponse(product, specsByProduct.getOrDefault(product.getId(), List.of()),
                            prices, stock, sales.getOrDefault(product.getId(), 0L), category,
                            ProductCategoryPath.from(category, categories), detail);
                })
                .toList();
    }

    private PublicProductResponse toResponse(Product product, List<ProductSpec> specs,
                                              Map<PriceTarget, PriceResolution> prices,
                                              Map<PriceTarget, ProductInventoryBatchService.StockSnapshot> stock,
                                             long salesCount, ProductCategory category,
                                             ProductCategoryPath categoryPath, boolean detail) {
        boolean hasSpecs = product.isSpecEnabled() && !specs.isEmpty();
        PriceResolution rootPrice = prices.get(new PriceTarget(product.getId(), null));
        if (rootPrice == null) {
            rootPrice = pricingService.resolve(product, null, null);
        }
        PriceResolution effectivePrice = rootPrice;
        if (hasSpecs) {
            effectivePrice = specs.stream()
                    .map(spec -> prices.get(new PriceTarget(product.getId(), spec.getId())))
                    .min(java.util.Comparator.comparing(PriceResolution::price))
                    .orElse(rootPrice);
        }
        BigDecimal retailStartingPrice = hasSpecs
                ? specs.stream().map(ProductSpec::getRetailPrice).min(java.util.Comparator.naturalOrder()).orElse(product.getRetailPrice())
                : product.getRetailPrice();
        ProductInventoryBatchService.StockSnapshot rootStock = stock.getOrDefault(
                new PriceTarget(product.getId(), null), new ProductInventoryBatchService.StockSnapshot(0L, false));
        List<PublicProductSpecResponse> specResponses = detail && hasSpecs
                ? specs.stream().map(spec -> toSpecResponse(product, spec, prices, stock)).toList()
                : List.of();
        return new PublicProductResponse(
                product.getId(), product.getTitle(), product.getDescription(),
                detail ? product.getDetailMd() : null, product.getCoverUrl(),
                effectivePrice.price(), retailStartingPrice, product.getCategoryId(),
                category == null ? null : category.getName(), categoryPath.primaryCategoryId(),
                categoryPath.primaryCategoryName(), categoryPath.secondaryCategoryId(),
                categoryPath.secondaryCategoryName(),
                rootStock.available(), rootStock.unlimited(), hasSpecs, product.getDeliveryType(),
                ProductRules.currentFields(product), product.getStockMode().name(), salesCount,
                product.getInitialSales(), product.isSpecEnabled(), specResponses,
                product.getCreatedAt(), detail ? product.getUpdatedAt() : null);
    }

    private PublicProductSpecResponse toSpecResponse(Product product, ProductSpec spec,
                                                     Map<PriceTarget, PriceResolution> prices,
                                                     Map<PriceTarget, ProductInventoryBatchService.StockSnapshot> stock) {
        PriceResolution price = prices.get(new PriceTarget(product.getId(), spec.getId()));
        ProductInventoryBatchService.StockSnapshot stockSnapshot = stock.getOrDefault(
                new PriceTarget(product.getId(), spec.getId()), new ProductInventoryBatchService.StockSnapshot(0L, false));
        return new PublicProductSpecResponse(spec.getId(), spec.getName(), price.price(), spec.getRetailPrice(),
                stockSnapshot.available(), stockSnapshot.unlimited(), product.getStockMode().name());
    }

    private Map<UUID, Long> loadSales(List<UUID> productIds) {
        Map<UUID, Long> result = new HashMap<>();
        for (ProductSalesProjection projection : orderItemRepository.sumQuantityByProductIds(productIds)) {
            result.put(projection.getProductId(), projection.getQuantity() == null ? 0L : projection.getQuantity());
        }
        return result;
    }

    private void validatePage(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分页参数无效");
        }
    }

    private Long parseProductNo(String keyword) {
        if (keyword == null || !keyword.matches("\\d+")) return null;
        try {
            return Long.valueOf(keyword);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
