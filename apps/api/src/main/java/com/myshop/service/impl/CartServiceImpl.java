package com.myshop.service.impl;

import com.myshop.constant.CardKeyStatus;
import com.myshop.constant.ErrorCode;
import com.myshop.constant.StockMode;
import com.myshop.entity.CartItem;
import com.myshop.entity.Product;
import com.myshop.entity.ProductSpec;
import com.myshop.exception.BusinessException;
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
import java.util.*;

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
        // 登录用户且有游客 sessionToken → 合并游客购物车到用户账户
        if (userId != null && sessionToken != null) {
            mergeGuestCart(userId, sessionToken);
        }

        List<CartItem> items;
        if (userId != null) {
            items = cartItemRepository.findByUserId(userId);
        } else if (sessionToken != null) {
            items = cartItemRepository.findBySessionToken(sessionToken);
        } else {
            items = List.of();
        }

        BigDecimal totalAmount = BigDecimal.ZERO;
        List<Map<String, Object>> itemList = new ArrayList<>();
        for (CartItem item : items) {
            Map<String, Object> m = toCartItemMap(item);
            BigDecimal subtotal = (BigDecimal) m.get("subtotal");
            totalAmount = totalAmount.add(subtotal);
            itemList.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", itemList);
        result.put("total_amount", totalAmount);
        return result;
    }

    @Override
    @Transactional
    public String addItem(UUID userId, String sessionToken, Map<String, Object> req) {
        UUID productId = UUID.fromString((String) req.get("product_id"));
        UUID specId = req.get("spec_id") != null ? UUID.fromString((String) req.get("spec_id")) : null;
        int quantity = ((Number) req.get("quantity")).intValue();
        String requestedCustomerInput = req.get("customer_input_value") == null
                ? null : String.valueOf(req.get("customer_input_value"));

        int maxQuantity = getMaxPurchasePerUser();
        if (quantity < 1 || quantity > maxQuantity) {
            throw new BusinessException(ErrorCode.PURCHASE_LIMIT_EXCEEDED, "购买数量无效，允许范围 1~" + maxQuantity,
                    Map.of("max", maxQuantity));
        }

        Product product = productRepository.findById(productId)
                .filter(p -> p.getIsDeleted() == 0 && p.isEnabled())
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "商品不存在或已下架"));
        ProductRules.validateCustomerInput(product, requestedCustomerInput);
        String customerInputValue = product.getCustomerInputType() == null
                || product.getCustomerInputType().name().equals("NONE")
                ? null : requestedCustomerInput.strip();

        // Generate session token for guest
        String resultSessionToken = sessionToken;
        if (userId == null && resultSessionToken == null) {
            resultSessionToken = UUID.randomUUID().toString();
        }

        // Check existing cart item
        Optional<CartItem> existing;
        if (userId != null) {
            existing = cartItemRepository.findByUserIdAndProductIdAndSpecIdAndCustomerInputValue(
                    userId, productId, specId, customerInputValue);
        } else {
            existing = cartItemRepository.findBySessionTokenAndProductIdAndSpecIdAndCustomerInputValue(
                    resultSessionToken, productId, specId, customerInputValue);
        }

        // 合并后总量校验：已有数量 + 新增数量 ≤ 购买上限
        int existingQty = existing.map(CartItem::getQuantity).orElse(0);
        int totalQty = existingQty + quantity;
        if (totalQty > maxQuantity) {
            throw new BusinessException(ErrorCode.PURCHASE_LIMIT_EXCEEDED,
                    "购买数量无效，允许范围 1~" + maxQuantity,
                    Map.of("max", maxQuantity));
        }

        // Advisory stock check: 购物车已有数量 + 新增数量 ≤ 可用库存
        StockView stock = getStock(product, specId);
        if (!stock.unlimited() && totalQty > stock.available()) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK,
                    "该商品库存不足，最多可购买 " + stock.available() + " 件",
                    Map.of("available", stock.available()));
        }

        if (existing.isPresent()) {
            existing.get().setQuantity(totalQty);
            cartItemRepository.save(existing.get());
        } else {
            CartItem item = new CartItem();
            item.setUserId(userId);
            item.setSessionToken(userId == null ? resultSessionToken : null);
            item.setProductId(productId);
            item.setSpecId(specId);
            item.setCustomerInputValue(customerInputValue);
            item.setQuantity(quantity);
            cartItemRepository.save(item);
        }

        return resultSessionToken;
    }

    @Override
    @Transactional
    public void updateItem(UUID userId, String sessionToken, UUID itemId, int quantity) {
        updateItemInternal(userId, sessionToken, itemId, quantity, null, false);
    }

    @Override
    @Transactional
    public void updateItem(UUID userId, String sessionToken, UUID itemId, int quantity, String customerInputValue) {
        updateItemInternal(userId, sessionToken, itemId, quantity, customerInputValue, true);
    }

    @Override
    @Transactional
    public void updateCustomerInput(UUID userId, String sessionToken, UUID itemId, String customerInputValue) {
        CartItem item = cartItemRepository.findById(itemId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "购物车项不存在"));
        verifyOwnership(item, userId, sessionToken);
        Product product = productRepository.findById(item.getProductId())
                .filter(p -> p.getIsDeleted() == 0 && p.isEnabled())
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "商品不存在或已下架"));
        item.setCustomerInputValue(ProductRules.validateCustomerInput(product, customerInputValue));
        cartItemRepository.save(item);
    }

    private void updateItemInternal(UUID userId, String sessionToken, UUID itemId, int quantity,
                                    String customerInputValue, boolean updateCustomerInput) {
        int maxQuantity = getMaxPurchasePerUser();
        if (quantity < 1 || quantity > maxQuantity) {
            throw new BusinessException(ErrorCode.PURCHASE_LIMIT_EXCEEDED, "购买数量无效，允许范围 1~" + maxQuantity,
                    Map.of("max", maxQuantity));
        }

        CartItem item = cartItemRepository.findById(itemId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "购物车项不存在"));
        verifyOwnership(item, userId, sessionToken);

        // Advisory stock check
        Product product = productRepository.findById(item.getProductId()).orElse(null);
        if (product == null || product.getIsDeleted() != 0 || !product.isEnabled()) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "商品不存在或已下架");
        }
        if (updateCustomerInput) {
            item.setCustomerInputValue(ProductRules.validateCustomerInput(product, customerInputValue));
        }
        StockView stock = product == null
                ? new StockView(0, false) : getStock(product, item.getSpecId());
        if (!stock.unlimited() && quantity > stock.available()) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK,
                    "该商品库存不足，最多可购买 " + stock.available() + " 件",
                    Map.of("available", stock.available()));
        }

        item.setQuantity(quantity);
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

    private void verifyOwnership(CartItem item, UUID userId, String sessionToken) {
        if (userId != null && userId.equals(item.getUserId())) return;
        if (sessionToken != null && sessionToken.equals(item.getSessionToken())) return;
        throw new BusinessException(ErrorCode.FORBIDDEN, "无权操作此购物车项");
    }

    /**
     * 将游客购物车（sessionToken）合并到登录用户（userId）。
     * 相同商品+规格的项合并数量，其余直接迁移归属。
     */
    private void mergeGuestCart(UUID userId, String sessionToken) {
        List<CartItem> guestItems = cartItemRepository.findBySessionToken(sessionToken);
        if (guestItems.isEmpty()) return;

        for (CartItem guestItem : guestItems) {
            Optional<CartItem> existing = cartItemRepository.findByUserIdAndProductIdAndSpecIdAndCustomerInputValue(
                    userId, guestItem.getProductId(), guestItem.getSpecId(), guestItem.getCustomerInputValue());
            if (existing.isPresent()) {
                // 已存在同商品+规格 → 合并数量（截断到购买上限，避免超限）
                int merged = existing.get().getQuantity() + guestItem.getQuantity();
                int max = getMaxPurchasePerUser();
                existing.get().setQuantity(Math.min(merged, max));
                cartItemRepository.save(existing.get());
                cartItemRepository.delete(guestItem);
            } else {
                // 不存在 → 迁移归属
                guestItem.setUserId(userId);
                guestItem.setSessionToken(null);
                cartItemRepository.save(guestItem);
            }
        }
    }

    private Map<String, Object> toCartItemMap(CartItem item) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", item.getId());
        map.put("product_id", item.getProductId());
        map.put("spec_id", item.getSpecId());
        map.put("customer_input_value", item.getCustomerInputValue());

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
        map.put("stock_available", stock.available());
        map.put("unlimited", stock.unlimited());
        map.put("stock_mode", stockMode);
        map.put("customer_input_type", product == null ? null : product.getCustomerInputType());
        map.put("customer_input_label", product == null ? null
                : ProductRules.customerInputLabel(product.getCustomerInputType()));

        return map;
    }

    /** 每用户单次最大购买数量，后台可配，兜底 999 */
    private int getMaxPurchasePerUser() {
        return siteConfigRepository.findByConfigKey("max_purchase_per_user")
                .map(c -> {
                    try {
                        int val = Integer.parseInt(c.getConfigValue());
                        return (val > 0 && val <= 999) ? val : 999;
                    } catch (Exception e) { return 999; }
                }).orElse(999);
    }

    private StockView getStock(Product product, UUID specId) {
        StockMode stockMode = product.getStockMode() == null ? StockMode.CARD_KEY : product.getStockMode();
        if (stockMode == StockMode.UNLIMITED) return new StockView(0, true);
        if (stockMode == StockMode.MANUAL) {
            return new StockView(manualStockService.getAvailable(product.getId(), specId), false);
        }
        long available = specId != null
                ? cardKeyRepository.countByProductIdAndSpecIdAndStatus(product.getId(), specId, CardKeyStatus.AVAILABLE)
                : cardKeyRepository.countByProductIdAndSpecIdIsNullAndStatus(product.getId(), CardKeyStatus.AVAILABLE);
        return new StockView(available, false);
    }

    private record StockView(long available, boolean unlimited) {
    }
}
