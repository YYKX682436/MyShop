package com.myshop.service.impl;

import com.myshop.common.PageResult;
import com.myshop.constant.CardKeyStatus;
import com.myshop.constant.ErrorCode;
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
import com.myshop.model.request.AdminProductOverrideRequest;
import com.myshop.model.request.AdminProductSpecRequest;
import com.myshop.model.request.AdminProductUpsertRequest;
import com.myshop.model.request.AdminProductStatusRequest;
import com.myshop.model.response.AdminPriceOverrideResponse;
import com.myshop.model.response.AdminPricingMatrixResponse;
import com.myshop.model.response.AdminPricingPreviewResponse;
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
        Page<Product> productPage = keyword != null && !keyword.isBlank()
                ? productRepository.findAdminProductsByKeyword(categoryId, "%" + keyword.toLowerCase(Locale.ROOT) + "%", isEnabled, pageable)
                : productRepository.findAdminProducts(categoryId, isEnabled, pageable);
        List<AdminProductResponse> list = productPage.getContent().stream()
                .map(product -> buildResponse(product, false, null, null)).toList();
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
        product.setProductCode(nextProductCode());
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
    public AdminPricingPreviewResponse previewPricing(AdminProductUpsertRequest request) {
        Product product = new Product();
        PreparedAggregate aggregate = prepare(product, request, true);
        applyProduct(product, aggregate);
        List<ProductSpec> previewSpecs = aggregate.specs().stream()
                .map(spec -> spec.toEntity(product.getId())).toList();
        List<UserLevel> levels = userLevelRepository.findAllOrdered().stream()
                .filter(UserLevel::isEnabled).toList();
        List<ProductLevelPriceOverride> overrides = toPreviewOverrides(aggregate, request.overrides());
        return new AdminPricingPreviewResponse(buildMatrix(product, previewSpecs, levels, overrides),
                buildWarnings(aggregate, levels, overrides));
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
        Map<UUID, ProductSpec> savedSpecs = syncSpecs(saved, aggregate);
        saveOverrides(saved, aggregate, desiredOverrides, savedSpecs);
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

        if (request.title() == null || request.title().trim().isBlank() || request.title().trim().length() > 255) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "商品名称不能为空且不能超过 255 个字符");
        }
        if (request.categoryId() == null && (creating || product.getCategoryId() == null)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "商品分类不能为空");
        }
        boolean enabled = request.isEnabled() == null ? (creating || product.isEnabled()) : request.isEnabled();
        if (specEnabled) {
            validateVisibleSpecRequirement(specEnabled, enabled, preparedSpecs.stream().anyMatch(this::isVisibleSpec));
        }
        validateMoney(retailPrice, true, "零售价");
        if (!specEnabled) validateMoney(costPrice, false, "成本价");
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
        List<String> warnings = new ArrayList<>();
        return new PreparedAggregate(request.title().trim(), request.description(), request.detailMd(), request.coverUrl(),
                specEnabled, retailPrice, costPrice, deliveryType, stockMode,
                fields, categoryId, lowStockThreshold, initialSales, sortWeight, enabled,
                request.manualStock(), preparedSpecs, warnings);
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
        Set<String> names = new HashSet<>();
        List<PreparedSpec> result = new ArrayList<>();
        for (AdminProductSpecRequest request : effective) {
            if (request == null) throw new BusinessException(ErrorCode.BAD_REQUEST, "规格参数无效");
            UUID id = request.id() == null ? UUID.randomUUID() : request.id();
            ProductSpec existing = existingById.get(id);
            if (request.id() != null && existing == null) {
                throw new BusinessException(ErrorCode.SPEC_NOT_FOUND, "规格不存在或不属于当前商品");
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
            validateMoney(cost, false, "规格成本价");
            validateMoney(retail, true, "规格零售价");
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

    private void validateMoney(BigDecimal value, boolean positive, String label) {
        if (value == null) throw new BusinessException(ErrorCode.BAD_REQUEST, label + "不能为空");
        if (value.scale() > 2) throw new BusinessException(ErrorCode.BAD_REQUEST, label + "最多支持两位小数");
        if (positive ? value.signum() <= 0 : value.signum() < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, label + (positive ? "必须大于 0" : "不能小于 0"));
        }
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

    private Map<UUID, ProductSpec> syncSpecs(Product product, PreparedAggregate aggregate) {
        List<ProductSpec> existing = productSpecRepository
                .findByProductIdAndIsDeletedOrderBySortOrderAsc(product.getId(), 0);
        Set<UUID> keep = aggregate.specs().stream().map(PreparedSpec::id).collect(Collectors.toSet());
        for (ProductSpec old : existing) {
            if (!keep.contains(old.getId())) retireSpec(product, old);
        }
        Map<UUID, ProductSpec> saved = new LinkedHashMap<>();
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
            saved.put(prepared.id(), productSpecRepository.saveAndFlush(spec));
        }
        return saved;
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

    private List<ProductLevelPriceOverride> toPreviewOverrides(PreparedAggregate aggregate,
                                                               List<AdminProductOverrideRequest> requests) {
        if (requests == null) return List.of();
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

    private void saveOverrides(Product product, PreparedAggregate aggregate,
                               List<ProductLevelPriceOverride> overrides,
                               Map<UUID, ProductSpec> savedSpecs) {
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
        Map<UUID, ProductCategory> categories = categoryRepository.findAllById(List.of(product.getCategoryId())).stream()
                .collect(Collectors.toMap(ProductCategory::getId, category -> category));
        long sales = orderItemRepository.sumQuantityByProductId(product.getId());
        boolean hasSpecs = product.isSpecEnabled() && !specs.isEmpty();
        ProductInventoryBatchService.StockSnapshot rootStock = stock
                .getOrDefault(new com.myshop.model.pricing.PriceTarget(product.getId(), null),
                        new ProductInventoryBatchService.StockSnapshot(0L, false));
        return new AdminProductResponse(product.getId(), product.getProductCode(), product.getTitle(), product.getDescription(), product.getDetailMd(),
                product.getCoverUrl(), product.getCostPrice(), product.getRetailPrice(), product.getCategoryId(),
                categories.get(product.getCategoryId()) == null ? null : categories.get(product.getCategoryId()).getName(),
                rootStock.available(), rootStock.unlimited(), hasSpecs, product.getDeliveryType(),
                ProductRules.currentFields(product), product.getStockMode().name(), sales, product.getInitialSales(),
                product.isSpecEnabled(), product.isEnabled(), product.getSortWeight(), product.getLowStockThreshold(),
                buildSpecResponses(product, specs, stock), overrides.stream().map(this::toOverride).toList(), matrix,
                warnings, product.getCreatedAt(), product.getUpdatedAt());
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

    private List<String> buildWarnings(PreparedAggregate aggregate, List<UserLevel> levels,
                                       List<ProductLevelPriceOverride> overrides) {
        Product product = new Product();
        product.setId(UUID.randomUUID());
        product.setRetailPrice(aggregate.retailPrice());
        product.setCostPrice(aggregate.costPrice());
        product.setSpecEnabled(aggregate.specEnabled());
        List<ProductSpec> specs = aggregate.specs().stream().map(spec -> spec.toEntity(product.getId())).toList();
        return buildWarnings(product, specs, levels, overrides);
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

    private void validatePage(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分页参数无效");
        }
    }

    private String nextProductCode() {
        long value = productRepository.nextProductCodeValue();
        if (value < 1) {
            throw new IllegalStateException("product_code_sequence returned an invalid value");
        }
        return String.format(Locale.ROOT, "P%06d", value);
    }

    private record PreparedSpec(AdminProductSpecRequest request, UUID id, ProductSpec existing,
                                String name, BigDecimal costPrice, BigDecimal retailPrice) {
        ProductSpec toEntity(UUID productId) {
            ProductSpec spec = new ProductSpec();
            spec.setId(id);
            spec.setProductId(productId);
            spec.setName(name);
            spec.setCostPrice(costPrice);
            spec.setRetailPrice(retailPrice);
            spec.setVisible(request.visible() == null || request.visible());
            spec.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
            return spec;
        }
    }

    private record PreparedAggregate(String title, String description, String detailMd, String coverUrl,
                                     boolean specEnabled, BigDecimal retailPrice, BigDecimal costPrice,
                                      String deliveryType, StockMode stockMode,
                                      List<CustomerInputField> customerInputFields, UUID categoryId,
                                      int lowStockThreshold, int initialSales, int sortWeight, boolean enabled,
                                      Integer manualStock, List<PreparedSpec> specs, List<String> warnings) {
    }
}
