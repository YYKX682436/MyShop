package com.myshop.service.impl;

import com.myshop.constant.CardKeyStatus;
import com.myshop.constant.ErrorCode;
import com.myshop.constant.StockMode;
import com.myshop.entity.CartItem;
import com.myshop.entity.Product;
import com.myshop.entity.ProductSpec;
import com.myshop.exception.BusinessException;
import com.myshop.model.CustomerInputValue;
import com.myshop.repository.CardKeyRepository;
import com.myshop.repository.CartItemRepository;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.ProductSpecRepository;
import com.myshop.repository.SiteConfigRepository;
import com.myshop.service.CartService;
import com.myshop.service.ManualStockService;
import com.myshop.service.ProductRules;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CartServiceImpl implements CartService {

    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;
    private final ProductSpecRepository productSpecRepository;
    private final CardKeyRepository cardKeyRepository;
    private final SiteConfigRepository siteConfigRepository;
    private final ManualStockService manualStockService;

    @Override
    @Transactional
    public Map<String, Object> getCart(UUID userId, String sessionToken) {
        if (userId != null && sessionToken != null) mergeGuestCart(userId, sessionToken);

        List<CartItem> items = ownerItems(userId, sessionToken);
        Map<StockPoolKey, Integer> poolTotals = poolTotals(items);
        BigDecimal totalAmount = BigDecimal.ZERO;
        List<Map<String, Object>> itemList = new ArrayList<>();
        for (CartItem item : items) {
            Map<String, Object> mapped = toCartItemMap(item, poolTotals);
            totalAmount = totalAmount.add((BigDecimal) mapped.get("subtotal"));
            itemList.add(mapped);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", itemList);
        result.put("total_amount", totalAmount);
        return result;
    }

    @Override
    @Transactional
    public String addItem(UUID userId, String sessionToken, Map<String, Object> req) {
        UUID productId = UUID.fromString(String.valueOf(req.get("product_id")));
        UUID specId = req.get("spec_id") == null ? null : UUID.fromString(String.valueOf(req.get("spec_id")));
        int quantity = number(req.get("quantity"), "购买数量不能为空");
        validateQuantity(quantity);

        Product product = activeProduct(productId);
        validateSpecConsistency(product, specId);
        List<CustomerInputValue> customerInputs = ProductRules.normalizeCustomerInputs(product, req);

        String resultSessionToken = sessionToken;
        if (userId == null && (resultSessionToken == null || resultSessionToken.isBlank())) {
            resultSessionToken = UUID.randomUUID().toString();
        }

        List<CartItem> allItems = ownerItems(userId, resultSessionToken);
        Optional<CartItem> existing = allItems.stream()
                .filter(item -> samePool(item, productId, specId))
                .filter(item -> ProductRules.sameCustomerInputs(item.getCustomerInputs(), customerInputs))
                .findFirst();
        int totalForLine = existing.map(CartItem::getQuantity).orElse(0) + quantity;
        int maxQuantity = getMaxPurchasePerUser();
        if (totalForLine > maxQuantity) {
            throw purchaseLimit(maxQuantity);
        }

        Map<StockPoolKey, Integer> totals = poolTotals(allItems);
        StockPoolKey pool = new StockPoolKey(productId, specId);
        int poolTotal = totals.getOrDefault(pool, 0) + quantity;
        checkStock(product, specId, poolTotal);

        if (existing.isPresent()) {
            CartItem item = existing.get();
            item.setQuantity(totalForLine);
            item.setCustomerInputs(customerInputs);
            cartItemRepository.save(item);
        } else {
            CartItem item = new CartItem();
            item.setUserId(userId);
            item.setSessionToken(userId == null ? resultSessionToken : null);
            item.setProductId(productId);
            item.setSpecId(specId);
            item.setCustomerInputs(customerInputs);
            item.setQuantity(quantity);
            cartItemRepository.save(item);
        }
        return resultSessionToken;
    }

    @Override
    @Transactional
    public void updateItem(UUID userId, String sessionToken, UUID itemId, int quantity) {
        updateItemInternal(userId, sessionToken, itemId, quantity, Map.of());
    }

    @Override
    @Transactional
    public void updateItem(UUID userId, String sessionToken, UUID itemId, int quantity,
                           Map<String, Object> request) {
        updateItemInternal(userId, sessionToken, itemId, quantity, request == null ? Map.of() : request);
    }

    private void updateItemInternal(UUID userId, String sessionToken, UUID itemId, int quantity,
                                    Map<String, Object> request) {
        validateQuantity(quantity);
        CartItem item = cartItemRepository.findById(itemId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "购物车项不存在"));
        verifyOwnership(item, userId, sessionToken);
        Product product = activeProduct(item.getProductId());
        validateSpecConsistency(product, item.getSpecId());

        List<CustomerInputValue> customerInputs = item.getCustomerInputs() == null
                ? List.of() : item.getCustomerInputs();
        if (request.containsKey("customer_inputs") || request.containsKey("customer_input_value")) {
            customerInputs = ProductRules.normalizeCustomerInputs(product, request);
        }

        List<CartItem> allItems = ownerItems(userId, sessionToken);
        Map<StockPoolKey, Integer> totals = poolTotals(allItems);
        StockPoolKey pool = new StockPoolKey(item.getProductId(), item.getSpecId());
        int poolTotal = totals.getOrDefault(pool, 0) - item.getQuantity() + quantity;
        checkStock(product, item.getSpecId(), poolTotal);

        int maxQuantity = getMaxPurchasePerUser();
        if (quantity > maxQuantity) throw purchaseLimit(maxQuantity);
        item.setQuantity(quantity);
        item.setCustomerInputs(customerInputs);
        cartItemRepository.save(item);
    }

    @Override
    @Transactional
    public void deleteItem(UUID userId, String sessionToken, UUID itemId) {
        CartItem item = cartItemRepository.findById(itemId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "购物车项不存在"));
        verifyOwnership(item, userId, sessionToken);
        cartItemRepository.delete(item);
    }

    private void mergeGuestCart(UUID userId, String sessionToken) {
        List<CartItem> guestItems = cartItemRepository.findBySessionToken(sessionToken);
        if (guestItems.isEmpty()) return;

        int max = getMaxPurchasePerUser();
        for (CartItem guestItem : guestItems) {
            List<CartItem> userItems = cartItemRepository.findByUserIdAndProductId(userId, guestItem.getProductId());
            Optional<CartItem> existing = userItems.stream()
                    .filter(item -> samePool(item, guestItem.getProductId(), guestItem.getSpecId()))
                    .filter(item -> ProductRules.sameCustomerInputs(item.getCustomerInputs(), guestItem.getCustomerInputs()))
                    .findFirst();
            if (existing.isPresent()) {
                CartItem target = existing.get();
                target.setQuantity(Math.min(max, target.getQuantity() + guestItem.getQuantity()));
                cartItemRepository.save(target);
                cartItemRepository.delete(guestItem);
            } else {
                guestItem.setUserId(userId);
                guestItem.setSessionToken(null);
                cartItemRepository.save(guestItem);
            }
        }
    }

    private Map<String, Object> toCartItemMap(CartItem item, Map<StockPoolKey, Integer> poolTotals) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", item.getId());
        map.put("product_id", item.getProductId());
        map.put("spec_id", item.getSpecId());

        Product product = productRepository.findById(item.getProductId()).orElse(null);
        String productTitle = product != null ? product.getTitle() : "未知商品";
        String coverUrl = product != null ? product.getCoverUrl() : null;
        BigDecimal unitPrice = product != null ? product.getBasePrice() : BigDecimal.ZERO;

        String specName = null;
        if (item.getSpecId() != null) {
            ProductSpec spec = productSpecRepository.findById(item.getSpecId()).orElse(null);
            if (spec != null) {
                specName = spec.getName();
                unitPrice = spec.getPrice();
            }
        }

        List<CustomerInputValue> knownInputs = ProductRules.knownCustomerInputs(product, item.getCustomerInputs());
        boolean complete = product == null || ProductRules.hasCompleteCustomerInputs(product, item.getCustomerInputs());
        map.put("customer_input_fields", ProductRules.currentFields(product));
        map.put("customer_inputs", knownInputs);
        map.put("customer_inputs_complete", complete);
        if (!complete) map.put("customer_input_error", "商品填写信息已更新，请补充后再结算");

        map.put("product_title", productTitle);
        map.put("spec_name", specName);
        map.put("cover_url", coverUrl);
        map.put("currency", product != null ? product.getCurrency() : "CNY");
        map.put("unit_price", unitPrice);
        map.put("quantity", item.getQuantity());
        map.put("subtotal", unitPrice.multiply(BigDecimal.valueOf(item.getQuantity())));

        StockMode stockMode = product == null || product.getStockMode() == null
                ? StockMode.CARD_KEY : product.getStockMode();
        StockView stock = product == null ? new StockView(0, false) : getStock(product, item.getSpecId());
        int otherInPool = poolTotals.getOrDefault(new StockPoolKey(item.getProductId(), item.getSpecId()), 0)
                - item.getQuantity();
        long rowAvailable = stock.unlimited() ? stock.available() : Math.max(0, stock.available() - otherInPool);
        map.put("stock_available", rowAvailable);
        map.put("stock_pool_available", stock.available());
        map.put("stock_cart_quantity", poolTotals.getOrDefault(
                new StockPoolKey(item.getProductId(), item.getSpecId()), item.getQuantity()));
        map.put("unlimited", stock.unlimited());
        map.put("stock_mode", stockMode);
        return map;
    }

    private List<CartItem> ownerItems(UUID userId, String sessionToken) {
        if (userId != null) return cartItemRepository.findByUserId(userId);
        if (sessionToken != null && !sessionToken.isBlank()) return cartItemRepository.findBySessionToken(sessionToken);
        return List.of();
    }

    private Map<StockPoolKey, Integer> poolTotals(List<CartItem> items) {
        Map<StockPoolKey, Integer> totals = new LinkedHashMap<>();
        for (CartItem item : items) {
            totals.merge(new StockPoolKey(item.getProductId(), item.getSpecId()), item.getQuantity(), Integer::sum);
        }
        return totals;
    }

    private boolean samePool(CartItem item, UUID productId, UUID specId) {
        return item.getProductId().equals(productId)
                && (item.getSpecId() == null ? specId == null : item.getSpecId().equals(specId));
    }

    private Product activeProduct(UUID productId) {
        return productRepository.findById(productId)
                .filter(p -> p.getIsDeleted() == 0 && p.isEnabled())
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "商品不存在或已下架"));
    }

    private void validateSpecConsistency(Product product, UUID specId) {
        List<ProductSpec> activeSpecs = productSpecRepository
                .findByProductIdAndIsDeletedOrderBySortOrderAsc(product.getId(), 0);
        if (product.isSpecEnabled() && !activeSpecs.isEmpty() && specId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该商品需要选择规格");
        }
        if (!product.isSpecEnabled() && specId != null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该商品不支持规格选择");
        }
        if (specId != null && activeSpecs.stream().noneMatch(spec -> spec.getId().equals(specId))) {
            throw new BusinessException(ErrorCode.SPEC_NOT_FOUND, "商品规格不存在或与商品不匹配");
        }
    }

    private void checkStock(Product product, UUID specId, int requestedPoolQuantity) {
        StockView stock = getStock(product, specId);
        if (!stock.unlimited() && requestedPoolQuantity > stock.available()) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK,
                    "该商品库存不足，最多可购买 " + stock.available() + " 件",
                    Map.of("available", stock.available()));
        }
    }

    private void validateQuantity(int quantity) {
        int max = getMaxPurchasePerUser();
        if (quantity < 1 || quantity > max) throw purchaseLimit(max);
    }

    private BusinessException purchaseLimit(int max) {
        return new BusinessException(ErrorCode.PURCHASE_LIMIT_EXCEEDED,
                "购买数量无效，允许范围 1~" + max, Map.of("max", max));
    }

    private int getMaxPurchasePerUser() {
        return siteConfigRepository.findByConfigKey("max_purchase_per_user")
                .map(c -> {
                    try {
                        int value = Integer.parseInt(c.getConfigValue());
                        return value > 0 && value <= 999 ? value : 999;
                    } catch (Exception ignored) {
                        return 999;
                    }
                }).orElse(999);
    }

    private StockView getStock(Product product, UUID specId) {
        StockMode stockMode = product.getStockMode() == null ? StockMode.CARD_KEY : product.getStockMode();
        if (stockMode == StockMode.UNLIMITED) return new StockView(Long.MAX_VALUE, true);
        if (stockMode == StockMode.MANUAL) {
            return new StockView(manualStockService.getAvailable(product.getId(), specId), false);
        }
        long available = specId != null
                ? cardKeyRepository.countByProductIdAndSpecIdAndStatus(product.getId(), specId, CardKeyStatus.AVAILABLE)
                : cardKeyRepository.countByProductIdAndSpecIdIsNullAndStatus(product.getId(), CardKeyStatus.AVAILABLE);
        return new StockView(available, false);
    }

    private int number(Object raw, String message) {
        if (!(raw instanceof Number number)) throw new BusinessException(ErrorCode.BAD_REQUEST, message);
        return number.intValue();
    }

    private void verifyOwnership(CartItem item, UUID userId, String sessionToken) {
        if (userId != null && userId.equals(item.getUserId())) return;
        if (sessionToken != null && sessionToken.equals(item.getSessionToken())) return;
        throw new BusinessException(ErrorCode.FORBIDDEN, "无权操作此购物车项");
    }

    private record StockPoolKey(UUID productId, UUID specId) {
    }

    private record StockView(long available, boolean unlimited) {
    }
}
