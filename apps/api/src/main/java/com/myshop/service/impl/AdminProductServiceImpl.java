package com.myshop.service.impl;

import com.myshop.common.PageResult;
import com.myshop.constant.CardKeyStatus;
import com.myshop.constant.ErrorCode;
import com.myshop.constant.PricingPreviewStatus;
import com.myshop.constant.PricingSource;
import com.myshop.constant.PricingType;
import com.myshop.constant.StockMode;
import com.myshop.entity.Product;
import com.myshop.entity.ProductCategory;
import com.myshop.entity.ProductLevelPriceOverride;
import com.myshop.entity.ProductSpec;
import com.myshop.entity.UserLevel;
import com.myshop.exception.BusinessException;
import com.myshop.model.CustomerInputField;
import com.myshop.model.ProductCategoryPath;
import com.myshop.model.request.AdminProductOverrideRequest;
import com.myshop.model.request.AdminProductCategoryUpdateRequest;
import com.myshop.model.request.AdminProductPricesUpdateRequest;
import com.myshop.model.request.AdminProductSpecPriceRequest;
import com.myshop.model.request.AdminProductSpecRequest;
import com.myshop.model.request.AdminProductTitleUpdateRequest;
import com.myshop.model.request.AdminProductUpsertRequest;
import com.myshop.model.request.AdminPricingPreviewOverrideRequest;
import com.myshop.model.request.AdminPricingPreviewRequest;
import com.myshop.model.request.AdminPricingPreviewSpecRequest;
import com.myshop.model.request.AdminProductStatusRequest;
import com.myshop.model.response.AdminPriceOverrideResponse;
import com.myshop.model.response.AdminPricingMatrixResponse;
import com.myshop.model.response.AdminPricingPreviewResponse;
import com.myshop.model.response.AdminPricingPreviewRowResponse;
import com.myshop.model.response.AdminProductResponse;
import com.myshop.model.response.AdminProductSpecResponse;
import com.myshop.repository.CardKeyRepository;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.ProductCategoryRepository;
import com.myshop.repository.ProductLevelPriceOverrideRepository;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.ProductSpecRepository;
import com.myshop.repository.ProductSalesProjection;
import com.myshop.repository.UserLevelRepository;
import com.myshop.service.AdminProductService;
import com.myshop.service.ProductInventoryBatchService;
import com.myshop.service.ProductListSort;
import com.myshop.service.ProductRules;
import com.myshop.service.PricingCalculator;
import com.myshop.service.PricingService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminProductServiceImpl implements AdminProductService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final String VISIBLE_SPEC_REQUIRED_MESSAGE = "启用规格的商品至少需要一个可见可售规格";
    private static final PricingCalculator PRICING_CALCULATOR = new PricingCalculator();

    private final ProductRepository productRepository;
    private final ProductSpecRepository productSpecRepository;
    private final ProductLevelPriceOverrideRepository overrideRepository;
    private final UserLevelRepository userLevelRepository;
    private final ProductCategoryRepository categoryRepository;
    private final CardKeyRepository cardKeyRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductInventoryBatchService inventoryBatchService;
    private final PricingService pricingService;
    private final com.myshop.service.ManualStockService manualStockService;

    @Override
    @Transactional(readOnly = true)
    public PageResult<AdminProductResponse> listProducts(UUID categoryId, String keyword, Boolean isEnabled,
                                                         String sortBy, String sortDirection, int page, int pageSize) {
        validatePage(page, pageSize);
        PageRequest pageable = PageRequest.of(page - 1, pageSize, ProductListSort.resolve(sortBy, sortDirection));
        String normalizedKeyword = keyword == null ? null : keyword.trim();
        Long productNo = parseProductNo(normalizedKeyword);
        Page<Product> productPage = normalizedKeyword != null && !normalizedKeyword.isBlank()
                ? productRepository.findAdminProductsByKeyword(categoryId, "%" + normalizedKeyword.toLowerCase(Locale.ROOT) + "%", productNo, isEnabled, pageable)
                : productRepository.findAdminProducts(categoryId, isEnabled, pageable);
        Map<UUID, ProductCategory> categories = loadActiveCategoryMap();
        List<AdminProductResponse> list = productPage.getContent().stream()
                .map(product -> buildResponse(product, false, null, null, categories)).toList();
        return PageResult.of(productPage, list);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminProductResponse getProduct(UUID id) {
        Product product = productRepository.findById(id)
                .filter(candidate -> candidate.getIsDeleted() == 0)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "商品不存在"));
        return buildResponse(product, true, List.of(), List.of());
    }

    @Override
    @Transactional
    public AdminProductResponse createProduct(AdminProductUpsertRequest request) {
        Product product = new Product();
        long productNo = productRepository.nextProductNoValue();
        if (productNo < 1) {
            throw new IllegalStateException("product_no_sequence returned an invalid value");
        }
        product.setProductNo(productNo);
        return saveAggregate(product, request, true);
    }

    @Override
    @Transactional
    public AdminProductResponse updateProduct(UUID id, AdminProductUpsertRequest request) {
        Product product = productRepository.findByIdForUpdate(id)
                .filter(candidate -> candidate.getIsDeleted() == 0)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "商品不存在"));
        return saveAggregate(product, request, false);
    }

    @Override
    @Transactional
    public AdminProductResponse setStatus(UUID id, AdminProductStatusRequest request) {
        if (request == null || request.enabled() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "商品状态不能为空");
        }
        Product product = productRepository.findByIdForUpdate(id)
                .filter(candidate -> candidate.getIsDeleted() == 0)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "商品不存在"));
        if (request.enabled() && !product.isEnabled() && product.isSpecEnabled()) {
            validateVisibleSpecRequirement(product.isSpecEnabled(), request.enabled(),
                    productSpecRepository.existsByProductIdAndIsDeletedAndVisible(product.getId(), 0, true));
        }
        product.setEnabled(request.enabled());
        Product saved = productRepository.saveAndFlush(product);
        return buildResponse(saved, true, null, null);
    }

    @Override
    @Transactional
    public AdminProductResponse updateTitle(UUID id, AdminProductTitleUpdateRequest request) {
        if (request == null) throw new BusinessException(ErrorCode.BAD_REQUEST, "商品名称参数不能为空");
        String title = ProductRules.normalizeProductTitle(request.title());
        Product product = productRepository.findByIdForUpdate(id)
                .filter(candidate -> candidate.getIsDeleted() == 0)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "商品不存在"));
        if (!Objects.equals(product.getTitle(), title)) {
            product.setTitle(title);
            productRepository.saveAndFlush(product);
        }
        return buildResponse(product, true, null, null);
    }

    @Override
    @Transactional
    public AdminProductResponse updateCategory(UUID id, AdminProductCategoryUpdateRequest request) {
        if (request == null || request.categoryId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "商品分类不能为空");
        }
        Product product = productRepository.findByIdForUpdate(id)
                .filter(candidate -> candidate.getIsDeleted() == 0)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "商品不存在"));
        categoryRepository.findById(request.categoryId())
                .filter(category -> category.getIsDeleted() == 0)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "商品分类不存在或已删除"));
        if (!Objects.equals(product.getCategoryId(), request.categoryId())) {
            product.setCategoryId(request.categoryId());
            productRepository.saveAndFlush(product);
        }
        return buildResponse(product, true, null, null);
    }

    @Override
    @Transactional
    public AdminProductResponse updatePrices(UUID id, AdminProductPricesUpdateRequest request) {
        if (request == null) throw new BusinessException(ErrorCode.BAD_REQUEST, "商品价格参数不能为空");
        Product product = productRepository.findByIdForUpdate(id)
                .filter(candidate -> candidate.getIsDeleted() == 0)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "商品不存在"));
        if (product.isSpecEnabled()) {
            return updateSpecificationPrices(product, request);
        }
        if (request.specs() != null && !request.specs().isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无规格商品不能提交规格价格");
        }

        BigDecimal costPrice = ProductRules.validateMoney(request.costPrice(), false, "成本价");
        BigDecimal retailPrice = ProductRules.validateMoney(request.retailPrice(), true, "零售价");
        validateExistingOverridePrices(product, Map.of(), retailPrice);

        product.setCostPrice(costPrice);
        product.setRetailPrice(retailPrice);
        Product saved = productRepository.saveAndFlush(product);
        return buildResponse(saved, true, null, null);
    }

    private AdminProductResponse updateSpecificationPrices(Product product,
                                                            AdminProductPricesUpdateRequest request) {
        if (request.costPrice() != null || request.retailPrice() != null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "规格商品必须逐项提交规格价格，不能修改根价格");
        }
        List<ProductSpec> existingSpecs = productSpecRepository
                .findByProductIdAndIsDeletedOrderBySortOrderAsc(product.getId(), 0);
        List<AdminProductSpecPriceRequest> requestedSpecs = request.specs();
        if (existingSpecs.isEmpty() || requestedSpecs == null || requestedSpecs.size() != existingSpecs.size()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "规格价格参数必须包含当前商品的全部规格");
        }

        Map<UUID, ProductSpec> existingById = existingSpecs.stream()
                .collect(Collectors.toMap(ProductSpec::getId, spec -> spec));
        Map<UUID, BigDecimal> nextCostBySpec = new LinkedHashMap<>();
        Map<UUID, BigDecimal> nextRetailBySpec = new LinkedHashMap<>();
        Set<UUID> requestedIds = new HashSet<>();
        for (AdminProductSpecPriceRequest requested : requestedSpecs) {
            if (requested == null || requested.specId() == null) {
                throw new BusinessException(ErrorCode.SPEC_NOT_FOUND, "规格不存在或不属于当前商品");
            }
            ProductSpec spec = existingById.get(requested.specId());
            if (spec == null) {
                throw new BusinessException(ErrorCode.SPEC_NOT_FOUND, "规格不存在或不属于当前商品");
            }
            if (!requestedIds.add(spec.getId())) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "规格价格参数不能重复");
            }
            BigDecimal costPrice = ProductRules.validateMoney(requested.costPrice(), false, "规格成本价");
            BigDecimal retailPrice = ProductRules.validateMoney(requested.retailPrice(), true, "规格零售价");
            nextCostBySpec.put(spec.getId(), costPrice);
            nextRetailBySpec.put(spec.getId(), retailPrice);
        }
        if (requestedIds.size() != existingById.size()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "规格价格参数必须包含当前商品的全部规格");
        }

        validateExistingOverridePrices(product, nextRetailBySpec, product.getRetailPrice());
        BigDecimal derivedRetailPrice = existingSpecs.stream()
                .filter(ProductSpec::isVisible)
                .map(spec -> nextRetailBySpec.get(spec.getId()))
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(product.getRetailPrice());

        // Only the two price columns on each existing spec and the derived
        // root retail price are changed. Names, visibility, order, inventory,
        // overrides, and every other product field remain untouched.
        for (ProductSpec spec : existingSpecs) {
            spec.setCostPrice(nextCostBySpec.get(spec.getId()));
            spec.setRetailPrice(nextRetailBySpec.get(spec.getId()));
        }
        productSpecRepository.saveAllAndFlush(existingSpecs);
        product.setRetailPrice(derivedRetailPrice);
        Product saved = productRepository.saveAndFlush(product);
        return buildResponse(saved, true, null, null);
    }

    private void validateExistingOverridePrices(Product product,
                                                Map<UUID, BigDecimal> retailBySpec,
                                                BigDecimal rootRetailPrice) {
        for (ProductLevelPriceOverride override : overrideRepository.findByProductId(product.getId())) {
            UUID specId = override.getSpecId();
            if (product.isSpecEnabled() == (specId == null)) {
                throw new BusinessException(ErrorCode.PRICE_OVERRIDE_CONFLICT,
                        "现有特殊价与商品规格模式不匹配");
            }
            BigDecimal retailPrice = specId == null ? rootRetailPrice : retailBySpec.get(specId);
            if (retailPrice == null || override.getPrice() == null
                    || override.getPrice().scale() > 2 || override.getPrice().signum() <= 0) {
                throw new BusinessException(ErrorCode.PRICE_OVERRIDE_CONFLICT,
                        "现有特殊价与商品价格覆盖目标不匹配");
            }
            if (override.getPrice().compareTo(retailPrice) > 0) {
                String target = specId == null ? "商品" : "规格 " + specId;
                throw new BusinessException(ErrorCode.PRICE_OVERRIDE_CONFLICT,
                        "新的" + target + "零售价不能低于已有特殊价");
            }
        }
    }

    @Override
    @Transactional
    public void updateSortWeight(UUID id, Integer sortWeight) {
        if (sortWeight == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "排序权重不能为空");
        }
        Product product = productRepository.findByIdForUpdate(id)
                .filter(candidate -> candidate.getIsDeleted() == 0)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "商品不存在"));
        product.setSortWeight(sortWeight);
        productRepository.saveAndFlush(product);
    }

    @Override
    @Transactional
    public void deleteProduct(UUID id) {
        Product product = productRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "商品不存在"));
        product.setIsDeleted(1);
        productRepository.save(product);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminProductSpecResponse> listSpecs(UUID productId) {
        Product product = productRepository.findById(productId)
                .filter(candidate -> candidate.getIsDeleted() == 0)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "商品不存在"));
        return buildSpecResponses(product, productSpecRepository
                .findByProductIdAndIsDeletedOrderBySortOrderAsc(productId, 0), null);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminPricingPreviewResponse previewPricing(AdminPricingPreviewRequest request) {
        if (request == null) throw new BusinessException(ErrorCode.BAD_REQUEST, "价格预览参数不能为空");

        boolean specEnabled = Boolean.TRUE.equals(request.specEnabled());
        List<AdminPricingPreviewSpecRequest> requestedSpecs = request.specs() == null
                ? List.of() : request.specs();
        List<PreviewTarget> targets = specEnabled
                ? preparePreviewSpecs(requestedSpecs)
                : List.of(new PreviewTarget(null,
                optionalMoney(request.costPrice(), false, "成本价"),
                optionalMoney(request.retailPrice(), true, "零售价")));
        if (specEnabled) {
            // Root prices are not used for a specification product, but a
            // supplied value is still checked when it is part of the draft.
            optionalMoney(request.costPrice(), false, "成本价");
            optionalMoney(request.retailPrice(), true, "零售价");
        }

        List<UserLevel> levels = userLevelRepository.findAllOrdered().stream()
                .filter(UserLevel::isEnabled).toList();
        List<AdminPricingPreviewOverrideRequest> requestedOverrides = request.overrides() == null
                ? List.of() : request.overrides();
        Map<PreviewOverrideKey, BigDecimal> overrides = preparePreviewOverrides(
                requestedOverrides, targets, levels, specEnabled);
        List<AdminPricingPreviewRowResponse> rows = new ArrayList<>();
        for (UserLevel level : levels) {
            for (PreviewTarget target : targets) {
                BigDecimal override = overrides.get(new PreviewOverrideKey(level.getId(), target.specId()));
                rows.add(buildPreviewRow(level, target, override));
            }
        }
        return new AdminPricingPreviewResponse(rows, buildPreviewWarnings(rows));
    }

    private List<PreviewTarget> preparePreviewSpecs(List<AdminPricingPreviewSpecRequest> requests) {
        Set<UUID> ids = new HashSet<>();
        List<PreviewTarget> result = new ArrayList<>();
        for (AdminPricingPreviewSpecRequest request : requests) {
            if (request == null) throw new BusinessException(ErrorCode.BAD_REQUEST, "规格价格参数无效");
            if (request.id() != null && !ids.add(request.id())) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "规格价格参数不能重复");
            }
            result.add(new PreviewTarget(request.id(),
                    optionalMoney(request.costPrice(), false, "规格成本价"),
                    optionalMoney(request.retailPrice(), true, "规格零售价")));
        }
        return result;
    }

    private Map<PreviewOverrideKey, BigDecimal> preparePreviewOverrides(
            List<AdminPricingPreviewOverrideRequest> requests,
            List<PreviewTarget> targets,
            List<UserLevel> levels,
            boolean specEnabled) {
        Map<UUID, UserLevel> levelsById = levels.stream()
                .collect(Collectors.toMap(UserLevel::getId, level -> level));
        Map<UUID, PreviewTarget> targetsById = targets.stream()
                .filter(target -> target.specId() != null)
                .collect(Collectors.toMap(PreviewTarget::specId, target -> target));
        Set<PreviewOverrideKey> keys = new HashSet<>();
        Map<PreviewOverrideKey, BigDecimal> result = new LinkedHashMap<>();
        for (AdminPricingPreviewOverrideRequest request : requests) {
            if (request == null || request.userLevelId() == null || request.price() == null) {
                throw new BusinessException(ErrorCode.PRICE_OVERRIDE_CONFLICT, "特殊价参数不完整");
            }
            if (!levelsById.containsKey(request.userLevelId())) {
                throw new BusinessException(ErrorCode.USER_LEVEL_INVALID, "用户等级不存在");
            }
            UUID specId = request.specId();
            PreviewTarget target;
            if (specEnabled) {
                target = targetsById.get(specId);
                if (target == null) {
                    throw new BusinessException(ErrorCode.PRICE_OVERRIDE_CONFLICT, "规格商品特殊价必须绑定有效规格");
                }
            } else {
                if (specId != null) {
                    throw new BusinessException(ErrorCode.PRICE_OVERRIDE_CONFLICT, "无规格商品不能设置规格特殊价");
                }
                target = targets.get(0);
            }
            BigDecimal price = request.price();
            if (price.scale() > 2 || price.signum() <= 0
                    || target.retailPrice() != null && price.compareTo(target.retailPrice()) > 0) {
                throw new BusinessException(ErrorCode.PRICE_OVERRIDE_CONFLICT,
                        "特殊价必须大于 0 且不能高于零售价");
            }
            PreviewOverrideKey key = new PreviewOverrideKey(request.userLevelId(), specId);
            if (!keys.add(key)) {
                throw new BusinessException(ErrorCode.PRICE_OVERRIDE_CONFLICT, "同一等级和目标不能重复设置特殊价");
            }
            result.put(key, price.setScale(2, RoundingMode.UNNECESSARY));
        }
        return result;
    }

    private BigDecimal optionalMoney(BigDecimal value, boolean positive, String label) {
        if (value == null) return null;
        if (value.scale() > 2) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, label + "最多支持两位小数");
        }
        if (positive ? value.signum() <= 0 : value.signum() < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, label + (positive ? "必须大于 0" : "不能小于 0"));
        }
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    private AdminPricingPreviewRowResponse buildPreviewRow(UserLevel level,
                                                            PreviewTarget target,
                                                            BigDecimal overridePrice) {
        BigDecimal rulePrice = null;
        BigDecimal finalPrice = null;
        boolean retailCapped = false;
        PricingPreviewStatus status;

        if (level.getPricingType() == PricingType.RETAIL) {
            if (target.retailPrice() == null) {
                status = PricingPreviewStatus.WAITING_RETAIL;
            } else {
                rulePrice = target.retailPrice();
                com.myshop.model.pricing.PriceResolution resolution = PRICING_CALCULATOR.calculate(
                        target.costPrice(), target.retailPrice(), level, overridePrice);
                finalPrice = resolution.price();
                retailCapped = resolution.retailCapped();
                status = PricingPreviewStatus.READY;
            }
        } else {
            if (target.costPrice() != null) {
                rulePrice = PRICING_CALCULATOR.calculateCostMarkup(target.costPrice(), level);
            }
            if (target.retailPrice() == null) {
                status = PricingPreviewStatus.WAITING_RETAIL;
            } else if (target.costPrice() == null) {
                if (overridePrice != null) {
                    finalPrice = PRICING_CALCULATOR.calculate(null, target.retailPrice(), level,
                            overridePrice).price();
                }
                status = PricingPreviewStatus.WAITING_COST;
            } else {
                com.myshop.model.pricing.PriceResolution resolution = PRICING_CALCULATOR.calculate(
                        target.costPrice(), target.retailPrice(), level, overridePrice);
                finalPrice = resolution.price();
                retailCapped = resolution.retailCapped();
                status = PricingPreviewStatus.READY;
            }
        }

        boolean belowCost = finalPrice != null && target.costPrice() != null
                && finalPrice.compareTo(target.costPrice()) < 0;
        return new AdminPricingPreviewRowResponse(level.getId(), level.getName(), level.getPricingType(),
                level.getMarkupRate(), target.specId(), rulePrice, overridePrice, finalPrice,
                status, retailCapped, belowCost);
    }

    private List<String> buildPreviewWarnings(List<AdminPricingPreviewRowResponse> rows) {
        return rows.stream().filter(AdminPricingPreviewRowResponse::belowCost)
                .map(row -> "等级「" + row.userLevelName() + "」的"
                        + (row.specId() == null ? "商品" : "规格特殊价") + "低于成本价")
                .distinct().toList();
    }

    private AdminProductResponse saveAggregate(Product product, AdminProductUpsertRequest request, boolean creating) {
        PreparedAggregate aggregate = prepare(product, request, creating);
        List<ProductLevelPriceOverride> existingOverrides = creating
                ? List.of() : overrideRepository.findByProductId(product.getId());
        List<ProductLevelPriceOverride> desiredOverrides = prepareOverrides(aggregate, request.overrides(), existingOverrides);

        // Remove every old override before changing product/spec retail or mode.
        // This makes the whole aggregate one rollback unit and lets the DB
        // retail-cap trigger validate only the final rows.
        if (!existingOverrides.isEmpty()) overrideRepository.deleteAllInBatch(existingOverrides);

        applyProduct(product, aggregate);
        Product saved = productRepository.saveAndFlush(product);
        syncSpecs(saved, aggregate);
        saveOverrides(saved, desiredOverrides);
        updateManualStock(saved, aggregate);
        return buildResponse(saved, true, desiredOverrides, aggregate.warnings());
    }

    private PreparedAggregate prepare(Product product, AdminProductUpsertRequest request, boolean creating) {
        if (request == null) throw new BusinessException(ErrorCode.BAD_REQUEST, "商品参数不能为空");
        List<ProductSpec> existingSpecs = creating ? List.of()
                : productSpecRepository.findByProductIdAndIsDeletedOrderBySortOrderAsc(product.getId(), 0);
        boolean specEnabled = request.specEnabled() != null
                ? request.specEnabled() : (!creating && product.isSpecEnabled());
        List<PreparedSpec> preparedSpecs = prepareSpecs(request.specs(), existingSpecs, specEnabled, creating);
        BigDecimal retailPrice = resolveRetailPrice(product, request, preparedSpecs, specEnabled, creating);
        BigDecimal costPrice = resolveCostPrice(product, request, specEnabled, creating);
        if (specEnabled) costPrice = null;

        String title = ProductRules.normalizeProductTitle(request.title());
        if (request.categoryId() == null && (creating || product.getCategoryId() == null)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "商品分类不能为空");
        }
        boolean enabled = request.isEnabled() == null ? (creating || product.isEnabled()) : request.isEnabled();
        if (specEnabled) {
            validateVisibleSpecRequirement(specEnabled, enabled, preparedSpecs.stream().anyMatch(this::isVisibleSpec));
        }
        retailPrice = ProductRules.validateMoney(retailPrice, true, "零售价");
        if (!specEnabled) costPrice = ProductRules.validateMoney(costPrice, false, "成本价");
        List<CustomerInputField> fields = ProductRules.normalizeCustomerInputFields(
                request.customerInputFields() != null ? request.customerInputFields()
                        : (!creating ? product.getCustomerInputFields() : List.of()));
        String deliveryType = ProductRules.normalizeDeliveryType(request.deliveryType(),
                creating ? "AUTO" : product.getDeliveryType());
        StockMode stockMode = ProductRules.parseStockMode(request.stockMode(),
                creating ? ("AUTO".equals(deliveryType) ? StockMode.CARD_KEY : StockMode.MANUAL)
                        : product.getStockMode());
        ProductRules.validateStockMode(deliveryType, stockMode);
        int lowStockThreshold = request.lowStockThreshold() == null
                ? (creating ? 10 : product.getLowStockThreshold()) : request.lowStockThreshold();
        if (lowStockThreshold < 0) throw new BusinessException(ErrorCode.BAD_REQUEST, "低库存阈值不能小于 0");
        int initialSales = request.initialSales() == null
                ? (creating ? 0 : product.getInitialSales()) : request.initialSales();
        if (initialSales < 0) throw new BusinessException(ErrorCode.BAD_REQUEST, "初始销量不能小于 0");
        int sortWeight = request.sortWeight() == null ? (creating ? 0 : product.getSortWeight()) : request.sortWeight();
        UUID categoryId = request.categoryId() == null ? product.getCategoryId() : request.categoryId();
        validateCategory(categoryId);
        List<String> warnings = new ArrayList<>();
        return new PreparedAggregate(title, request.description(), request.detailMd(), request.coverUrl(),
                specEnabled, retailPrice, costPrice, deliveryType, stockMode,
                fields, categoryId, lowStockThreshold, initialSales, sortWeight, enabled,
                request.manualStock(), preparedSpecs, warnings);
    }

    private void validateCategory(UUID categoryId) {
        if (categoryId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "商品分类不能为空");
        }
        categoryRepository.findById(categoryId)
                .filter(category -> category.getIsDeleted() == 0)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "商品分类不存在或已删除"));
    }

    private void validateVisibleSpecRequirement(boolean specEnabled, boolean enabled, boolean hasVisibleSpec) {
        if (specEnabled && enabled && !hasVisibleSpec) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, VISIBLE_SPEC_REQUIRED_MESSAGE);
        }
    }

    private boolean isVisibleSpec(PreparedSpec spec) {
        return spec.request().visible() == null || spec.request().visible();
    }

    private List<PreparedSpec> prepareSpecs(List<AdminProductSpecRequest> requests,
                                            List<ProductSpec> existingSpecs,
                                            boolean specEnabled, boolean creating) {
        List<AdminProductSpecRequest> effective = requests;
        if (effective == null) {
            effective = existingSpecs.stream().map(spec -> new AdminProductSpecRequest(spec.getId(), spec.getName(),
                    spec.getCostPrice(), spec.getRetailPrice(), spec.isVisible(), spec.getSortOrder(), null)).toList();
        }
        Map<UUID, ProductSpec> existingById = existingSpecs.stream()
                .collect(Collectors.toMap(ProductSpec::getId, spec -> spec));
        Set<UUID> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        List<PreparedSpec> result = new ArrayList<>();
        for (AdminProductSpecRequest request : effective) {
            if (request == null) throw new BusinessException(ErrorCode.BAD_REQUEST, "规格参数无效");
            UUID id = request.id() == null ? UUID.randomUUID() : request.id();
            if (!ids.add(id)) throw new BusinessException(ErrorCode.BAD_REQUEST, "规格参数不能重复");
            ProductSpec existing = existingById.get(id);
            if (request.id() != null && existing == null) {
                if (productSpecRepository.findById(id).isPresent()) {
                    throw new BusinessException(ErrorCode.SPEC_NOT_FOUND, "规格不存在或不属于当前商品");
                }
            }
            String name = request.name() == null ? "" : request.name().trim();
            if (name.isBlank() || name.length() > 255) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "规格名称不能为空且不能超过 255 个字符");
            }
            if (!names.add(name.toLowerCase(java.util.Locale.ROOT))) {
                throw new BusinessException(ErrorCode.SPEC_NAME_DUPLICATE, "同一商品下规格名称不能重复");
            }
            BigDecimal cost = request.costPrice() != null ? request.costPrice()
                    : existing == null ? null : existing.getCostPrice();
            BigDecimal retail = request.retailPrice() != null ? request.retailPrice()
                    : existing == null ? null : existing.getRetailPrice();
            cost = ProductRules.validateMoney(cost, false, "规格成本价");
            retail = ProductRules.validateMoney(retail, true, "规格零售价");
            result.add(new PreparedSpec(request, id, existing, name, cost, retail));
        }
        if (creating && !specEnabled && !result.isEmpty()) {
            // Specs can be preconfigured while the switch is off, but they are
            // never sold until the aggregate enables the mode.
        }
        return result;
    }

    private BigDecimal resolveRetailPrice(Product product, AdminProductUpsertRequest request,
                                           List<PreparedSpec> specs, boolean specEnabled, boolean creating) {
        if (specEnabled) {
            BigDecimal visibleRetail = specs.stream()
                    .filter(this::isVisibleSpec)
                    .map(PreparedSpec::retailPrice)
                    .min(Comparator.naturalOrder())
                    .orElse(null);
            if (visibleRetail != null) return visibleRetail;
            // A disabled spec product may temporarily have no sellable spec. Keep
            // the persisted root value (or an explicit positive draft value) so
            // admins can edit hidden specs without making the required DB column
            // invalid. The value is never used as a public starting price while
            // the product remains disabled.
            if (request.retailPrice() != null && request.retailPrice().signum() > 0) {
                return request.retailPrice();
            }
            return creating ? null : product.getRetailPrice();
        }
        return request.retailPrice() != null ? request.retailPrice() : (creating ? null : product.getRetailPrice());
    }

    private BigDecimal resolveCostPrice(Product product, AdminProductUpsertRequest request,
                                        boolean specEnabled, boolean creating) {
        if (specEnabled) return null;
        BigDecimal cost = request.costPrice() != null ? request.costPrice() : (creating ? null : product.getCostPrice());
        if (!creating && product.isSpecEnabled() && request.costPrice() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "从规格商品切换为无规格时必须填写新的成本价");
        }
        if (!creating && product.isSpecEnabled() && request.retailPrice() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "从规格商品切换为无规格时必须填写新的零售价");
        }
        return cost;
    }

    private void applyProduct(Product product, PreparedAggregate aggregate) {
        product.setTitle(aggregate.title() == null ? product.getTitle() : aggregate.title());
        product.setDescription(aggregate.description());
        product.setDetailMd(aggregate.detailMd());
        product.setCoverUrl(aggregate.coverUrl());
        product.setRetailPrice(aggregate.retailPrice());
        product.setCostPrice(aggregate.costPrice());
        product.setCategoryId(aggregate.categoryId());
        product.setDeliveryType(aggregate.deliveryType());
        product.setCustomerInputFields(aggregate.customerInputFields());
        product.setStockMode(aggregate.stockMode());
        product.setLowStockThreshold(aggregate.lowStockThreshold());
        product.setSpecEnabled(aggregate.specEnabled());
        product.setEnabled(aggregate.enabled());
        product.setInitialSales(aggregate.initialSales());
        product.setSortWeight(aggregate.sortWeight());
    }

    private void syncSpecs(Product product, PreparedAggregate aggregate) {
        List<ProductSpec> existing = productSpecRepository
                .findByProductIdAndIsDeletedOrderBySortOrderAsc(product.getId(), 0);
        Set<UUID> keep = aggregate.specs().stream().map(PreparedSpec::id).collect(Collectors.toSet());
        for (ProductSpec old : existing) {
            if (!keep.contains(old.getId())) retireSpec(product, old);
        }
        for (PreparedSpec prepared : aggregate.specs()) {
            ProductSpec spec = prepared.existing() == null ? new ProductSpec() : prepared.existing();
            if (prepared.existing() == null) spec.setId(prepared.id());
            spec.setProductId(product.getId());
            spec.setName(prepared.name());
            spec.setCostPrice(prepared.costPrice());
            spec.setRetailPrice(prepared.retailPrice());
            spec.setVisible(prepared.request().visible() == null || prepared.request().visible());
            spec.setSortOrder(prepared.request().sortOrder() == null ? 0 : prepared.request().sortOrder());
            spec.setIsDeleted(0);
            productSpecRepository.saveAndFlush(spec);
        }
    }

    private void retireSpec(Product product, ProductSpec spec) {
        cardKeyRepository.updateStatusByProductIdAndSpecId(product.getId(), spec.getId(),
                CardKeyStatus.AVAILABLE, CardKeyStatus.INVALID);
        overrideRepository.deleteBySpecId(spec.getId());
        spec.setIsDeleted(1);
        productSpecRepository.save(spec);
    }

    private List<ProductLevelPriceOverride> prepareOverrides(PreparedAggregate aggregate,
                                                              List<AdminProductOverrideRequest> requests,
                                                              List<ProductLevelPriceOverride> existing) {
        if (requests == null) {
            Set<UUID> specIds = aggregate.specs().stream().map(PreparedSpec::id).collect(Collectors.toSet());
            return existing.stream()
                    .filter(old -> aggregate.specEnabled()
                            ? old.getSpecId() != null && specIds.contains(old.getSpecId())
                            : old.getSpecId() == null)
                    .map(this::copyOverride).toList();
        }
        return buildValidatedOverrides(aggregate, requests);
    }

    private List<ProductLevelPriceOverride> buildValidatedOverrides(PreparedAggregate aggregate,
                                                                     List<AdminProductOverrideRequest> requests) {
        Map<UUID, UserLevel> levels = userLevelRepository.findAllById(requests.stream()
                        .map(AdminProductOverrideRequest::userLevelId).filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(UserLevel::getId, level -> level));
        Map<UUID, PreparedSpec> specs = aggregate.specs().stream()
                .collect(Collectors.toMap(PreparedSpec::id, spec -> spec));
        Set<String> keys = new HashSet<>();
        List<ProductLevelPriceOverride> result = new ArrayList<>();
        for (AdminProductOverrideRequest request : requests) {
            if (request == null || request.userLevelId() == null || request.price() == null) {
                throw new BusinessException(ErrorCode.PRICE_OVERRIDE_CONFLICT, "特殊价参数不完整");
            }
            UserLevel level = levels.get(request.userLevelId());
            if (level == null) throw new BusinessException(ErrorCode.USER_LEVEL_INVALID, "用户等级不存在");
            if (!level.isEnabled()) throw new BusinessException(ErrorCode.USER_LEVEL_INVALID, "停用等级不能设置特殊价");
            UUID specId = request.specId();
            if (aggregate.specEnabled()) {
                if (specId == null || !specs.containsKey(specId)) {
                    throw new BusinessException(ErrorCode.PRICE_OVERRIDE_CONFLICT, "规格商品特殊价必须绑定有效规格");
                }
            } else if (specId != null) {
                throw new BusinessException(ErrorCode.PRICE_OVERRIDE_CONFLICT, "无规格商品不能设置规格特殊价");
            }
            String key = request.userLevelId() + ":" + String.valueOf(specId);
            if (!keys.add(key)) throw new BusinessException(ErrorCode.PRICE_OVERRIDE_CONFLICT, "同一等级和目标不能重复设置特殊价");
            BigDecimal retail = aggregate.specEnabled() ? specs.get(specId).retailPrice() : aggregate.retailPrice();
            validateOverridePrice(request.price(), retail, level.getName(), specId);
            ProductLevelPriceOverride override = new ProductLevelPriceOverride();
            override.setProductId(null); // filled when persisted
            override.setSpecId(specId);
            override.setUserLevelId(level.getId());
            override.setPrice(request.price().setScale(2, RoundingMode.UNNECESSARY));
            result.add(override);
        }
        return result;
    }

    private void validateOverridePrice(BigDecimal price, BigDecimal retail, String levelName, UUID specId) {
        if (price.scale() > 2 || price.signum() <= 0 || price.compareTo(retail) > 0) {
            String target = specId == null ? "商品" : "规格 " + specId;
            throw new BusinessException(ErrorCode.PRICE_OVERRIDE_CONFLICT,
                    "等级「" + levelName + "」的" + target + "特殊价不能高于零售价且必须大于 0");
        }
    }

    private void saveOverrides(Product product, List<ProductLevelPriceOverride> overrides) {
        for (ProductLevelPriceOverride override : overrides) {
            override.setProductId(product.getId());
        }
        if (!overrides.isEmpty()) overrideRepository.saveAllAndFlush(overrides);
    }

    private void updateManualStock(Product product, PreparedAggregate aggregate) {
        if (aggregate.stockMode() != StockMode.MANUAL) return;
        if (!aggregate.specEnabled() && aggregate.manualStock() != null) {
            manualStockService.setAvailable(product.getId(), null, aggregate.manualStock());
        }
        for (PreparedSpec spec : aggregate.specs()) {
            if (spec.request().manualStock() != null) {
                manualStockService.setAvailable(product.getId(), spec.id(), spec.request().manualStock());
            }
        }
    }

    private AdminProductResponse buildResponse(Product product, boolean detail,
                                               List<ProductLevelPriceOverride> suppliedOverrides,
                                               List<String> suppliedWarnings) {
        return buildResponse(product, detail, suppliedOverrides, suppliedWarnings, loadActiveCategoryMap());
    }

    private AdminProductResponse buildResponse(Product product, boolean detail,
                                               List<ProductLevelPriceOverride> suppliedOverrides,
                                               List<String> suppliedWarnings,
                                               Map<UUID, ProductCategory> categories) {
        List<ProductSpec> specs = productSpecRepository.findByProductIdAndIsDeletedOrderBySortOrderAsc(product.getId(), 0);
        Map<UUID, List<ProductSpec>> byProduct = Map.of(product.getId(), specs);
        Map<com.myshop.model.pricing.PriceTarget, ProductInventoryBatchService.StockSnapshot> stock =
                inventoryBatchService.load(List.of(product), byProduct);
        List<ProductLevelPriceOverride> overrides = suppliedOverrides == null || suppliedOverrides.isEmpty()
                ? overrideRepository.findByProductId(product.getId()) : suppliedOverrides;
        List<UserLevel> levels = detail ? userLevelRepository.findAllOrdered().stream().filter(UserLevel::isEnabled).toList() : List.of();
        List<AdminPricingMatrixResponse> matrix = detail ? buildMatrix(product, specs, levels, overrides) : List.of();
        List<String> warnings = detail ? buildWarnings(product, specs, levels, overrides) : List.of();
        if (suppliedWarnings != null && !suppliedWarnings.isEmpty()) warnings = suppliedWarnings;
        ProductCategory category = categories.get(product.getCategoryId());
        ProductCategoryPath categoryPath = ProductCategoryPath.from(category, categories);
        long sales = orderItemRepository.sumQuantityByProductId(product.getId());
        boolean hasSpecs = product.isSpecEnabled() && !specs.isEmpty();
        ProductInventoryBatchService.StockSnapshot rootStock = stock
                .getOrDefault(new com.myshop.model.pricing.PriceTarget(product.getId(), null),
                        new ProductInventoryBatchService.StockSnapshot(0L, false));
        return new AdminProductResponse(product.getId(), product.getProductNo(), product.getTitle(), product.getDescription(), product.getDetailMd(),
                product.getCoverUrl(), product.getCostPrice(), product.getRetailPrice(), product.getCategoryId(),
                category == null ? null : category.getName(), categoryPath.primaryCategoryId(),
                categoryPath.primaryCategoryName(), categoryPath.secondaryCategoryId(),
                categoryPath.secondaryCategoryName(),
                rootStock.available(), rootStock.unlimited(), hasSpecs, product.getDeliveryType(),
                ProductRules.currentFields(product), product.getStockMode().name(), sales, product.getInitialSales(),
                product.isSpecEnabled(), product.isEnabled(), product.getSortWeight(), product.getLowStockThreshold(),
                buildSpecResponses(product, specs, stock), overrides.stream().map(this::toOverride).toList(), matrix,
                warnings, product.getCreatedAt(), product.getUpdatedAt());
    }

    private Map<UUID, ProductCategory> loadActiveCategoryMap() {
        return categoryRepository.findByIsDeletedOrderBySortOrderAscIdAsc(0).stream()
                .collect(Collectors.toMap(ProductCategory::getId, category -> category));
    }

    private List<AdminProductSpecResponse> buildSpecResponses(Product product, List<ProductSpec> specs,
                                                              Map<com.myshop.model.pricing.PriceTarget, ProductInventoryBatchService.StockSnapshot> stock) {
        return specs.stream().map(spec -> {
            BigDecimal margin = spec.getRetailPrice().subtract(spec.getCostPrice()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal marginRate = margin.multiply(ONE_HUNDRED)
                    .divide(spec.getRetailPrice(), 2, RoundingMode.HALF_UP);
            ProductInventoryBatchService.StockSnapshot snapshot = stock == null ? null : stock.get(
                    new com.myshop.model.pricing.PriceTarget(product.getId(), spec.getId()));
            if (snapshot == null) snapshot = new ProductInventoryBatchService.StockSnapshot(
                    product.getStockMode() == StockMode.UNLIMITED ? null : 0L, product.getStockMode() == StockMode.UNLIMITED);
            Integer manual = product.getStockMode() == StockMode.MANUAL && snapshot.available() != null
                    ? snapshot.available().intValue() : null;
            long cardKeys = cardKeyRepository.countByProductIdAndSpecIdExcludingStatus(
                    product.getId(), spec.getId(), CardKeyStatus.INVALID);
            return new AdminProductSpecResponse(spec.getId(), spec.getName(), spec.getCostPrice(), spec.getRetailPrice(),
                    margin, marginRate, spec.isVisible(), spec.getSortOrder(), spec.getIsDeleted(), snapshot.available(),
                    snapshot.unlimited(), manual, cardKeys);
        }).toList();
    }

    private List<AdminPricingMatrixResponse> buildMatrix(Product product, List<ProductSpec> specs,
                                                         List<UserLevel> levels,
                                                         List<ProductLevelPriceOverride> overrides) {
        List<AdminPricingMatrixResponse> result = new ArrayList<>();
        List<ProductSpec> targets = product.isSpecEnabled() && !specs.isEmpty()
                ? specs : java.util.Collections.singletonList(null);
        for (UserLevel level : levels) {
            List<ProductLevelPriceOverride> levelOverrides = overrides.stream()
                    .filter(override -> level.getId().equals(override.getUserLevelId())).toList();
            for (ProductSpec spec : targets) {
                BigDecimal overridePrice = levelOverrides.stream()
                        .filter(override -> Objects.equals(override.getSpecId(), spec == null ? null : spec.getId()))
                        .map(ProductLevelPriceOverride::getPrice).findFirst().orElse(null);
                com.myshop.model.pricing.PriceResolution defaultResolution = pricingService.resolve(product, spec, level, List.of());
                com.myshop.model.pricing.PriceResolution finalResolution = pricingService.resolve(product, spec, level,
                        overridePrice == null ? List.of() : levelOverrides);
                BigDecimal cost = spec == null ? product.getCostPrice() : spec.getCostPrice();
                result.add(new AdminPricingMatrixResponse(level.getId(), level.getName(), level.getPricingType(),
                        level.getMarkupRate(), spec == null ? null : spec.getId(), defaultResolution.price(), overridePrice,
                        finalResolution.price(), finalResolution.pricingSource(), finalResolution.retailCapped(),
                        cost != null && finalResolution.price().compareTo(cost) < 0));
            }
        }
        return result;
    }

    private List<String> buildWarnings(Product product, List<ProductSpec> specs, List<UserLevel> levels,
                                       List<ProductLevelPriceOverride> overrides) {
        return buildMatrix(product, specs, levels, overrides).stream()
                .filter(AdminPricingMatrixResponse::belowCost)
                .map(row -> "等级「" + row.userLevelName() + "」的" +
                        (row.specId() == null ? "商品" : "规格特殊价") + "低于成本价")
                .distinct().toList();
    }

    private AdminPriceOverrideResponse toOverride(ProductLevelPriceOverride override) {
        return new AdminPriceOverrideResponse(override.getId(), override.getUserLevelId(), override.getSpecId(), override.getPrice());
    }

    private ProductLevelPriceOverride copyOverride(ProductLevelPriceOverride old) {
        ProductLevelPriceOverride copy = new ProductLevelPriceOverride();
        copy.setSpecId(old.getSpecId());
        copy.setUserLevelId(old.getUserLevelId());
        copy.setPrice(old.getPrice());
        return copy;
    }

    private record PreviewTarget(UUID specId, BigDecimal costPrice, BigDecimal retailPrice) {
    }

    private record PreviewOverrideKey(UUID userLevelId, UUID specId) {
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

    private record PreparedSpec(AdminProductSpecRequest request, UUID id, ProductSpec existing,
                                String name, BigDecimal costPrice, BigDecimal retailPrice) {
    }

    private record PreparedAggregate(String title, String description, String detailMd, String coverUrl,
                                     boolean specEnabled, BigDecimal retailPrice, BigDecimal costPrice,
                                      String deliveryType, StockMode stockMode,
                                      List<CustomerInputField> customerInputFields, UUID categoryId,
                                      int lowStockThreshold, int initialSales, int sortWeight, boolean enabled,
                                      Integer manualStock, List<PreparedSpec> specs, List<String> warnings) {
    }
}
