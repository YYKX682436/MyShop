package com.myshop.service.impl;

import com.myshop.common.PageResult;
import com.myshop.constant.CardKeyStatus;
import com.myshop.constant.ErrorCode;
import com.myshop.constant.StockMode;
import com.myshop.entity.Product;
import com.myshop.entity.ProductSpec;
import com.myshop.exception.BusinessException;
import com.myshop.repository.CardKeyRepository;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.ProductSpecRepository;
import com.myshop.service.ProductService;
import com.myshop.service.ManualStockService;
import com.myshop.service.ProductRules;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final ProductSpecRepository productSpecRepository;
    private final CardKeyRepository cardKeyRepository;
    private final OrderItemRepository orderItemRepository;
    private final ManualStockService manualStockService;

    @Override
    public PageResult<?> listPublicProducts(UUID categoryId, String keyword, int page, int pageSize) {
        var pageable = PageRequest.of(page - 1, pageSize,
                Sort.by("sortOrder").ascending().and(Sort.by("createdAt").descending()));
        Page<Product> productPage;
        if (keyword != null && !keyword.isBlank()) {
            productPage = productRepository.findPublicProductsByKeyword(categoryId, "%" + keyword.toLowerCase() + "%", pageable);
        } else {
            productPage = productRepository.findPublicProducts(categoryId, pageable);
        }
        var list = productPage.getContent().stream().map(this::toProductCard).toList();
        return PageResult.of(productPage, list);
    }

    @Override
    public Map<String, Object> getProductDetail(UUID id) {
        Product product = productRepository.findById(id)
                .filter(p -> p.getIsDeleted() == 0 && p.isEnabled())
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "商品不存在或已下架"));
        Map<String, Object> detail = toProductDetail(product);
        // 前台公开接口：多规格未启用时隐藏规格信息，防止泄露未启用的规格数据
        if (!product.isSpecEnabled()) {
            detail.put("specs", List.of());
        }
        return detail;
    }

    @Override
    public Map<String, Object> getAdminProductDetail(UUID id) {
        Product product = productRepository.findById(id)
                .filter(p -> p.getIsDeleted() == 0)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "商品不存在"));
        Map<String, Object> detail = toProductDetail(product);
        detail.put("is_enabled", product.isEnabled());
        detail.put("sort_order", product.getSortOrder());
        detail.put("low_stock_threshold", product.getLowStockThreshold());
        detail.put("created_at", product.getCreatedAt());
        detail.put("updated_at", product.getUpdatedAt());
        return detail;
    }

    @Override
    public PageResult<?> listAdminProducts(UUID categoryId, String keyword, Boolean isEnabled, int page, int pageSize) {
        var pageable = PageRequest.of(page - 1, pageSize, Sort.by("sortOrder").ascending());
        Page<Product> productPage;
        if (keyword != null && !keyword.isBlank()) {
            productPage = productRepository.findAdminProductsByKeyword(categoryId, "%" + keyword.toLowerCase() + "%", isEnabled, pageable);
        } else {
            productPage = productRepository.findAdminProducts(categoryId, isEnabled, pageable);
        }
        var list = productPage.getContent().stream().map(p -> {
            Map<String, Object> detail = toProductDetail(p);
            detail.put("is_enabled", p.isEnabled());
            detail.put("sort_order", p.getSortOrder());
            detail.put("low_stock_threshold", p.getLowStockThreshold());
            detail.put("sales_count", orderItemRepository.sumQuantityByProductId(p.getId()));
            detail.put("created_at", p.getCreatedAt());
            detail.put("updated_at", p.getUpdatedAt());
            return detail;
        }).toList();
        return PageResult.of(productPage, list);
    }

    @Override
    @Transactional
    public Map<String, Object> createProduct(Map<String, Object> req) {
        Product product = new Product();
        product.setTitle((String) req.get("title"));
        product.setDescription((String) req.get("description"));
        product.setDetailMd((String) req.get("detail_md"));
        product.setCoverUrl((String) req.get("cover_url"));
        BigDecimal retailPrice = new BigDecimal(req.get("base_price").toString());
        product.setRetailPrice(retailPrice);
        product.setCostPrice(retailPrice);
        String deliveryType = ProductRules.normalizeDeliveryType(req.get("delivery_type"), "AUTO");
        StockMode stockMode = ProductRules.parseStockMode(req.get("stock_mode"),
                "AUTO".equals(deliveryType) ? StockMode.CARD_KEY : StockMode.MANUAL);
        ProductRules.validateStockMode(deliveryType, stockMode);
        product.setDeliveryType(deliveryType);
        product.setCustomerInputFields(ProductRules.normalizeCustomerInputFields(
                req.getOrDefault("customer_input_fields", List.of())));
        product.setStockMode(stockMode);
        product.setCategoryId(UUID.fromString((String) req.get("category_id")));
        if (req.containsKey("low_stock_threshold")) product.setLowStockThreshold(((Number) req.get("low_stock_threshold")).intValue());
        if (req.containsKey("spec_enabled")) product.setSpecEnabled((boolean) req.get("spec_enabled"));
        if (req.containsKey("is_enabled")) product.setEnabled((boolean) req.get("is_enabled"));
        if (req.containsKey("initial_sales")) product.setInitialSales(((Number) req.get("initial_sales")).intValue());
        if (req.containsKey("sort_order")) product.setSortOrder(((Number) req.get("sort_order")).intValue());
        productRepository.save(product);
        if (stockMode == StockMode.MANUAL) {
            manualStockService.setAvailable(product.getId(), null, integerValue(req.get("manual_stock"), 0));
        }
        return toProductDetail(product);
    }

    @Override
    @Transactional
    public void updateProduct(UUID id, Map<String, Object> req) {
        Product product = productRepository.findById(id)
                .filter(p -> p.getIsDeleted() == 0)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "商品不存在"));
        if (req.containsKey("title")) product.setTitle((String) req.get("title"));
        if (req.containsKey("description")) product.setDescription((String) req.get("description"));
        if (req.containsKey("detail_md")) product.setDetailMd((String) req.get("detail_md"));
        if (req.containsKey("cover_url")) product.setCoverUrl((String) req.get("cover_url"));
        if (req.containsKey("base_price")) product.setRetailPrice(new BigDecimal(req.get("base_price").toString()));
        String deliveryType = ProductRules.normalizeDeliveryType(req.get("delivery_type"), product.getDeliveryType());
        StockMode previousStockMode = product.getStockMode();
        StockMode stockMode = ProductRules.parseStockMode(req.get("stock_mode"),
                req.containsKey("delivery_type") && !req.containsKey("stock_mode")
                        ? ("AUTO".equals(deliveryType) ? StockMode.CARD_KEY : StockMode.MANUAL)
                        : previousStockMode);
        ProductRules.validateStockMode(deliveryType, stockMode);
        product.setDeliveryType(deliveryType);
        if (req.containsKey("customer_input_fields")) {
            product.setCustomerInputFields(ProductRules.normalizeCustomerInputFields(req.get("customer_input_fields")));
        }
        product.setStockMode(stockMode);
        if (req.containsKey("category_id")) product.setCategoryId(UUID.fromString((String) req.get("category_id")));
        if (req.containsKey("low_stock_threshold")) product.setLowStockThreshold(((Number) req.get("low_stock_threshold")).intValue());
        if (req.containsKey("spec_enabled")) product.setSpecEnabled((boolean) req.get("spec_enabled"));
        if (req.containsKey("is_enabled")) product.setEnabled((boolean) req.get("is_enabled"));
        if (req.containsKey("initial_sales")) product.setInitialSales(((Number) req.get("initial_sales")).intValue());
        if (req.containsKey("sort_order")) product.setSortOrder(((Number) req.get("sort_order")).intValue());
        productRepository.save(product);
        if (stockMode == StockMode.MANUAL) {
            if (req.containsKey("manual_stock")) {
                manualStockService.setAvailable(product.getId(), null, integerValue(req.get("manual_stock"), 0));
            } else {
                manualStockService.ensurePool(product.getId(), null);
            }
        }
    }

    @Override
    @Transactional
    public void deleteProduct(UUID id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "商品不存在"));
        product.setIsDeleted(1);
        productRepository.save(product);
    }

    @Override
    public Object listSpecs(UUID productId) {
        Product product = productRepository.findById(productId)
                .filter(p -> p.getIsDeleted() == 0)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "商品不存在"));
        return productSpecRepository.findByProductIdAndIsDeletedOrderBySortOrderAsc(productId, 0).stream()
                .map(spec -> toSpecMap(product, spec)).toList();
    }

    @Override
    @Transactional
    public void createSpec(UUID productId, Map<String, Object> req) {
        productRepository.findById(productId)
                .filter(p -> p.getIsDeleted() == 0)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "商品不存在"));

        String name = (String) req.get("name");
        if (productSpecRepository.existsByProductIdAndNameAndIsDeleted(productId, name, 0)) {
            throw new BusinessException(ErrorCode.SPEC_NAME_DUPLICATE, "同一商品下规格名称不能重复");
        }

        ProductSpec spec = new ProductSpec();
        spec.setProductId(productId);
        spec.setName(name);
        BigDecimal retailPrice = new BigDecimal(req.get("price").toString());
        spec.setRetailPrice(retailPrice);
        spec.setCostPrice(retailPrice);
        if (req.containsKey("is_visible")) spec.setVisible((boolean) req.get("is_visible"));
        if (req.containsKey("sort_order")) spec.setSortOrder(((Number) req.get("sort_order")).intValue());
        productSpecRepository.save(spec);
        Product product = productRepository.findById(productId).orElseThrow();
        if (product.getStockMode() == StockMode.MANUAL) {
            manualStockService.setAvailable(spec.getProductId(), spec.getId(), integerValue(req.get("manual_stock"), 0));
        }
    }

    @Override
    @Transactional
    public void updateSpec(UUID productId, UUID specId, Map<String, Object> req) {
        ProductSpec spec = productSpecRepository.findById(specId)
                .filter(s -> s.getProductId().equals(productId) && s.getIsDeleted() == 0)
                .orElseThrow(() -> new BusinessException(ErrorCode.SPEC_NOT_FOUND, "规格不存在"));

        if (req.containsKey("name")) {
            String newName = (String) req.get("name");
            if (!newName.equals(spec.getName())
                    && productSpecRepository.existsByProductIdAndNameAndIsDeleted(productId, newName, 0)) {
                throw new BusinessException(ErrorCode.SPEC_NAME_DUPLICATE, "同一商品下规格名称不能重复");
            }
            spec.setName(newName);
        }
        if (req.containsKey("price")) spec.setRetailPrice(new BigDecimal(req.get("price").toString()));
        if (req.containsKey("is_visible")) spec.setVisible((boolean) req.get("is_visible"));
        if (req.containsKey("sort_order")) spec.setSortOrder(((Number) req.get("sort_order")).intValue());
        productSpecRepository.save(spec);
        if (req.containsKey("manual_stock")) {
            Product product = productRepository.findById(productId).orElseThrow();
            if (product.getStockMode() == StockMode.MANUAL) {
                manualStockService.setAvailable(productId, specId, integerValue(req.get("manual_stock"), 0));
            }
        }
    }

    @Override
    @Transactional
    public void deleteSpec(UUID productId, UUID specId) {
        ProductSpec spec = productSpecRepository.findById(specId)
                .filter(s -> s.getProductId().equals(productId))
                .orElseThrow(() -> new BusinessException(ErrorCode.SPEC_NOT_FOUND, "规格不存在"));

        // 自动作废该规格下的可用卡密（AVAILABLE → INVALID），避免留下永远无法售出的幽灵库存
        cardKeyRepository.updateStatusByProductIdAndSpecId(
                productId, specId, CardKeyStatus.AVAILABLE, CardKeyStatus.INVALID);

        spec.setIsDeleted(1);
        productSpecRepository.save(spec);
    }

    private StockView getStock(Product product, UUID specId) {
        StockMode stockMode = product.getStockMode();
        if (stockMode == StockMode.UNLIMITED) return new StockView(null, true);
        if (stockMode == StockMode.MANUAL) {
            return new StockView((long) manualStockService.getAvailable(product.getId(), specId), false);
        }
        long available = specId != null
                ? cardKeyRepository.countByProductIdAndSpecIdAndStatus(product.getId(), specId, CardKeyStatus.AVAILABLE)
                : cardKeyRepository.countByProductIdAndSpecIdIsNullAndStatus(product.getId(), CardKeyStatus.AVAILABLE);
        return new StockView(available, false);
    }

    private Map<String, Object> toProductCard(Product p) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", p.getId());
        map.put("title", p.getTitle());
        map.put("description", p.getDescription());
        map.put("cover_url", p.getCoverUrl());
        map.put("base_price", p.getRetailPrice());
        map.put("category_id", p.getCategoryId());
        List<ProductSpec> specs = productSpecRepository.findByProductIdAndIsDeletedOrderBySortOrderAsc(p.getId(), 0);
        boolean hasSpecs = p.isSpecEnabled() && !specs.isEmpty();
        StockMode stockMode = p.getStockMode();
        StockView stock;
        if (hasSpecs) {
            if (stockMode == StockMode.UNLIMITED) {
                stock = new StockView(null, true);
            } else {
                long available = specs.stream()
                        .map(s -> getStock(p, s.getId()))
                        .mapToLong(view -> view.available() == null ? 0 : view.available())
                        .sum();
                stock = new StockView(available, false);
            }
        } else {
            stock = getStock(p, null);
        }
        map.put("stock_available", stock.available());
        map.put("unlimited", stock.unlimited());
        map.put("has_specs", hasSpecs);
        map.put("delivery_type", p.getDeliveryType());
        map.put("customer_input_fields", ProductRules.currentFields(p));
        map.put("stock_mode", stockMode);
        map.put("sales_count", orderItemRepository.sumQuantityByProductId(p.getId()));
        map.put("initial_sales", p.getInitialSales());
        map.put("created_at", p.getCreatedAt());
        return map;
    }

    private Map<String, Object> toProductDetail(Product p) {
        Map<String, Object> map = new LinkedHashMap<>(toProductCard(p));
        map.put("detail_md", p.getDetailMd());
        List<ProductSpec> specs = productSpecRepository.findByProductIdAndIsDeletedOrderBySortOrderAsc(p.getId(), 0);
        map.put("specs", specs.stream().map(spec -> toSpecMap(p, spec)).toList());
        StockMode stockMode = p.getStockMode();
        map.put("spec_enabled", p.isSpecEnabled());
        map.put("customer_input_fields", ProductRules.currentFields(p));
        map.put("stock_mode", stockMode);
        return map;
    }

    private Map<String, Object> toSpecMap(Product product, ProductSpec s) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", s.getId());
        map.put("name", s.getName());
        map.put("price", s.getRetailPrice());
        StockMode stockMode = product.getStockMode();
        StockView stock = getStock(product, s.getId());
        map.put("stock_available", stock.available());
        map.put("unlimited", stock.unlimited());
        map.put("stock_mode", stockMode);
        map.put("manual_stock", stockMode == StockMode.MANUAL ? stock.available() : null);
        // 有效卡密数量（AVAILABLE/SOLD/LOCKED），用于前端判断删除规格时是否需要确认
        map.put("card_key_count", cardKeyRepository.countByProductIdAndSpecIdExcludingStatus(
                s.getProductId(), s.getId(), CardKeyStatus.INVALID));
        return map;
    }

    private int integerValue(Object raw, int fallback) {
        if (raw == null) return fallback;
        if (raw instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(raw));
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "手动库存数量无效");
        }
    }

    private record StockView(Long available, boolean unlimited) {
    }
}
