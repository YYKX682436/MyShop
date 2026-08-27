package com.myshop.service.impl;

import com.myshop.common.PageResult;
import com.myshop.constant.CardKeyStatus;
import com.myshop.constant.ErrorCode;
import com.myshop.constant.StockMode;
import com.myshop.entity.CardImportBatch;
import com.myshop.entity.CardKey;
import com.myshop.entity.OrderItem;
import com.myshop.entity.Product;
import com.myshop.exception.BusinessException;
import com.myshop.repository.*;
import com.myshop.service.AdminCardKeyService;
import com.myshop.service.ManualStockService;
import com.myshop.utils.CardContentParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminCardKeyServiceImpl implements AdminCardKeyService {

    private final CardKeyRepository cardKeyRepository;
    private final CardImportBatchRepository cardImportBatchRepository;
    private final ProductRepository productRepository;
    private final ProductSpecRepository productSpecRepository;
    private final OrderItemRepository orderItemRepository;
    private final ManualStockService manualStockService;

    @Override
    public List<?> getStockSummary(UUID productId, UUID specId) {
        // Get all products or specific product
        List<Product> products;
        if (productId != null) {
            products = productRepository.findById(productId).map(List::of).orElse(List.of());
        } else {
            products = productRepository.findAll();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Product p : products) {
            if (p.getIsDeleted() != 0) continue;
            var specs = productSpecRepository.findByProductIdAndIsDeletedOrderBySortOrderAsc(p.getId(), 0);

            if (specId == null) {
                // 无规格筛选：展示默认库存池（spec_id=null）+ 所有规格库存
                Map<String, Object> defaultEntry = buildStockEntry(p, null, null);
                Object defaultTotalValue = defaultEntry.get("total");
                long defaultTotal = defaultTotalValue instanceof Number number ? number.longValue() : 0;
                // 如果默认池有卡密，或者商品没有任何规格，则显示默认池条目
                if (defaultTotal > 0 || specs.isEmpty() || effectiveStockMode(p) != StockMode.CARD_KEY) {
                    defaultEntry.put("spec_enabled", p.isSpecEnabled());
                    result.add(defaultEntry);
                }
                for (var spec : specs) {
                    Map<String, Object> entry = buildStockEntry(p, spec.getId(), spec.getName());
                    entry.put("spec_enabled", p.isSpecEnabled());
                    result.add(entry);
                }
            } else {
                // 按指定规格筛选
                for (var spec : specs) {
                    if (spec.getId().equals(specId)) {
                        Map<String, Object> entry = buildStockEntry(p, spec.getId(), spec.getName());
                        entry.put("spec_enabled", p.isSpecEnabled());
                        result.add(entry);
                    }
                }
            }
        }
        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> importCardKeys(Map<String, Object> req, UUID importedBy) {
        UUID productId = UUID.fromString((String) req.get("product_id"));
        UUID specId = req.get("spec_id") != null ? UUID.fromString((String) req.get("spec_id")) : null;

        Product product = productRepository.findById(productId)
                .filter(p -> p.getIsDeleted() == 0)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "商品不存在"));

        if (effectiveStockMode(product) != StockMode.CARD_KEY
                || !"AUTO".equalsIgnoreCase(product.getDeliveryType() == null ? "AUTO" : product.getDeliveryType())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "只有自动发货的卡券库存商品可以导入卡密");
        }

        // 校验 specId 归属：防止传入不属于该商品的规格 ID
        if (specId != null) {
            productSpecRepository.findById(specId)
                    .filter(s -> s.getProductId().equals(productId) && s.getIsDeleted() == 0)
                    .orElseThrow(() -> new BusinessException(ErrorCode.SPEC_NOT_FOUND, "规格不存在或不属于该商品"));
        }

        CardContentParser.ParsedColumns parsed;
        try {
            parsed = CardContentParser.parse(extractColumns(req));
        } catch (CardContentParser.InvalidContentException exception) {
            throw new BusinessException(ErrorCode.CARD_KEY_FORMAT_ERROR, exception.getMessage());
        }

        boolean checkDuplicate = booleanValue(req.get("check_duplicate"), true);
        List<List<String>> rows = parsed.rows();
        List<String> fingerprints = rows.stream().map(CardContentParser::fingerprint).toList();
        Set<String> historical = checkDuplicate && !fingerprints.isEmpty()
                ? new HashSet<>(cardKeyRepository.findExistingFingerprints(productId, specId, fingerprints))
                : Set.of();
        Set<String> currentBatch = new HashSet<>();
        List<CardKey> importedCardKeys = new ArrayList<>();
        List<String> failDetails = new ArrayList<>();
        int duplicateCount = 0;

        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            List<String> parts = rows.get(rowIndex);
            String fingerprint = fingerprints.get(rowIndex);
            boolean duplicate = !currentBatch.add(fingerprint)
                    || (checkDuplicate && historical.contains(fingerprint));
            if (duplicate) {
                duplicateCount++;
                // Never put card content into batch details or logs.
                failDetails.add("第" + (rowIndex + 1) + "行重复");
                continue;
            }

            CardKey key = new CardKey();
            key.setProductId(productId);
            key.setSpecId(specId);
            key.setContent(parts.get(0));
            key.setContentParts(List.copyOf(parts));
            key.setContentFingerprint(fingerprint);
            key.setStatus(CardKeyStatus.AVAILABLE);
            importedCardKeys.add(key);
        }

        CardImportBatch batch = new CardImportBatch();
        batch.setProductId(productId);
        batch.setSpecId(specId);
        batch.setImportedBy(importedBy);
        batch.setColumnCount(parsed.columns().size());
        batch.setCheckDuplicate(checkDuplicate);
        batch.setTotalCount(rows.size());
        batch.setSuccessCount(importedCardKeys.size());
        batch.setDuplicateCount(duplicateCount);
        // Duplicates are reported in their own bucket. Structural validation
        // errors abort before a batch is persisted, so this batch has no
        // format/validation failures.
        batch.setFailCount(0);
        batch.setFailDetail(failDetails.isEmpty() ? null : String.join("\n", failDetails));
        cardImportBatchRepository.save(batch);

        for (CardKey key : importedCardKeys) {
            key.setImportBatchId(batch.getId());
        }
        if (!importedCardKeys.isEmpty()) cardKeyRepository.saveAll(importedCardKeys);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", batch.getId());
        result.put("product_id", batch.getProductId());
        result.put("spec_id", batch.getSpecId());
        result.put("imported_by", batch.getImportedBy());
        result.put("total_count", batch.getTotalCount());
        result.put("success_count", batch.getSuccessCount());
        result.put("duplicate_count", batch.getDuplicateCount());
        result.put("fail_count", batch.getFailCount());
        result.put("column_count", batch.getColumnCount());
        result.put("check_duplicate", batch.isCheckDuplicate());
        result.put("fail_detail", batch.getFailDetail());
        result.put("created_at", batch.getCreatedAt());
        return result;
    }

    @Override
    public PageResult<?> getImportBatches(UUID productId, int page, int pageSize) {
        var pageable = PageRequest.of(page - 1, pageSize);
        Page<CardImportBatch> batchPage;
        if (productId != null) {
            batchPage = cardImportBatchRepository.findByProductIdOrderByCreatedAtDesc(productId, pageable);
        } else {
            batchPage = cardImportBatchRepository.findAllByOrderByCreatedAtDesc(pageable);
        }
        return PageResult.of(batchPage, batchPage.getContent());
    }

    @Override
    @Transactional
    public void invalidateCardKey(UUID id) {
        CardKey key = cardKeyRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "卡密不存在"));
        if (key.getStatus() == CardKeyStatus.SOLD) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "已售出的卡密不可作废");
        }
        key.setStatus(CardKeyStatus.INVALID);
        cardKeyRepository.save(key);
    }

    @Override
    @Transactional
    public int batchInvalidateCardKeys(UUID productId, UUID specId) {
        return cardKeyRepository.updateStatusByProductIdAndSpecId(
                productId, specId, CardKeyStatus.AVAILABLE, CardKeyStatus.INVALID);
    }

    @Override
    public List<?> getCardKeysByOrder(UUID orderId) {
        List<CardKey> keys = cardKeyRepository.findByOrderIdOrderByCreatedAtAsc(orderId);
        List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
        Map<UUID, OrderItem> itemMap = new HashMap<>();
        for (OrderItem item : items) {
            itemMap.put(item.getId(), item);
        }

        return keys.stream().map(k -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("card_key_id", k.getId());
            map.put("order_item_id", k.getOrderItemId());
            List<String> parts = k.resolvedContentParts();
            map.put("content", parts.isEmpty() ? null : parts.get(0));
            map.put("content_parts", parts);
            OrderItem item = k.getOrderItemId() != null ? itemMap.get(k.getOrderItemId()) : null;
            map.put("product_title", item != null ? item.getProductTitle() : null);
            map.put("spec_name", item != null ? item.getSpecName() : null);
            map.put("status", k.getStatus().name());
            return map;
        }).toList();
    }

    @Override
    public PageResult<?> listCardKeys(UUID productId, UUID specId, int page, int pageSize) {
        var pageable = PageRequest.of(page - 1, pageSize);
        var keyPage = cardKeyRepository.findByProductIdAndOptionalSpecId(productId, specId, pageable);
        var list = keyPage.getContent().stream().map(k -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", k.getId());
            List<String> parts = k.resolvedContentParts();
            map.put("content", parts.isEmpty() ? null : parts.get(0));
            map.put("content_parts", parts);
            map.put("status", k.getStatus().name());
            map.put("order_id", k.getOrderId());
            map.put("created_at", k.getCreatedAt());
            map.put("sold_at", k.getSoldAt());
            return map;
        }).toList();
        return PageResult.of(keyPage, list);
    }

    private Map<String, Object> buildStockEntry(Product product, UUID specId, String specName) {
        UUID productId = product.getId();
        String productTitle = product.getTitle();
        StockMode stockMode = effectiveStockMode(product);
        if (stockMode == StockMode.UNLIMITED) {
            Map<String, Object> unlimited = new LinkedHashMap<>();
            unlimited.put("product_id", productId);
            unlimited.put("product_title", productTitle);
            unlimited.put("spec_id", specId);
            unlimited.put("spec_name", specName);
            unlimited.put("stock_mode", stockMode);
            unlimited.put("unlimited", true);
            unlimited.put("total", null);
            unlimited.put("available", null);
            unlimited.put("reserved", 0);
            unlimited.put("sold", 0);
            unlimited.put("locked", 0);
            unlimited.put("invalid", 0);
            return unlimited;
        }
        if (stockMode == StockMode.MANUAL) {
            int available = manualStockService.getAvailable(productId, specId);
            int reserved = manualStockService.getReserved(productId, specId);
            Map<String, Object> manual = new LinkedHashMap<>();
            manual.put("product_id", productId);
            manual.put("product_title", productTitle);
            manual.put("spec_id", specId);
            manual.put("spec_name", specName);
            manual.put("stock_mode", stockMode);
            manual.put("unlimited", false);
            manual.put("total", available + reserved);
            manual.put("available", available);
            manual.put("reserved", reserved);
            manual.put("sold", 0);
            manual.put("locked", 0);
            manual.put("invalid", 0);
            return manual;
        }
        List<Object[]> counts = cardKeyRepository.countByProductIdAndSpecIdGroupByStatus(productId, specId);
        long total = 0, available = 0, sold = 0, locked = 0, invalid = 0;
        for (Object[] row : counts) {
            CardKeyStatus status = (CardKeyStatus) row[0];
            long cnt = (Long) row[1];
            total += cnt;
            switch (status) {
                case AVAILABLE -> available = cnt;
                case SOLD -> sold = cnt;
                case LOCKED -> locked = cnt;
                case INVALID -> invalid = cnt;
            }
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("product_id", productId);
        map.put("product_title", productTitle);
        map.put("spec_id", specId);
        map.put("spec_name", specName);
        map.put("total", total);
        map.put("available", available);
        map.put("sold", sold);
        map.put("locked", locked);
        map.put("invalid", invalid);
        map.put("reserved", 0);
        map.put("stock_mode", stockMode);
        map.put("unlimited", false);
        return map;
    }

    private List<String> extractColumns(Map<String, Object> request) {
        Object rawColumns = request.get("content_columns");
        if (rawColumns instanceof List<?> list && !list.isEmpty()) {
            return list.stream().map(value -> value == null ? "" : String.valueOf(value)).toList();
        }
        Object legacy = request.get("content");
        if (legacy != null) return List.of(String.valueOf(legacy));
        throw new BusinessException(ErrorCode.CARD_KEY_FORMAT_ERROR, "至少需要一列卡券内容");
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value == null) return fallback;
        if (value instanceof Boolean bool) return bool;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private StockMode effectiveStockMode(Product product) {
        return product.getStockMode() == null ? StockMode.CARD_KEY : product.getStockMode();
    }
}
