package com.myshop.service;

import com.myshop.constant.StockMode;
import com.myshop.entity.Product;
import com.myshop.entity.ProductSpec;
import com.myshop.model.pricing.PriceTarget;
import com.myshop.repository.CardKeyRepository;
import com.myshop.repository.ManualStockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Loads all product/spec inventory pools with grouped SQL, avoiding list N+1 queries. */
@Service
@RequiredArgsConstructor
public class ProductInventoryBatchService {

    private final CardKeyRepository cardKeyRepository;
    private final ManualStockRepository manualStockRepository;

    @Transactional(readOnly = true)
    public Map<PriceTarget, StockSnapshot> load(Collection<Product> products,
                                                Map<UUID, List<ProductSpec>> specsByProduct) {
        if (products == null || products.isEmpty()) return Map.of();
        List<UUID> productIds = products.stream().map(Product::getId).toList();
        Map<PriceTarget, Long> cardCounts = new HashMap<>();
        for (Object[] row : cardKeyRepository.countAvailableByProductIds(productIds)) {
            cardCounts.put(new PriceTarget(uuid(row[0]), row[1] == null ? null : uuid(row[1])), number(row[2]));
        }
        Map<PriceTarget, Long> manualCounts = new HashMap<>();
        for (Object[] row : manualStockRepository.findAvailableByProductIds(productIds)) {
            manualCounts.put(new PriceTarget(uuid(row[0]), row[1] == null ? null : uuid(row[1])), number(row[2]));
        }

        Map<PriceTarget, StockSnapshot> result = new HashMap<>();
        for (Product product : products) {
            if (product.getStockMode() == StockMode.UNLIMITED) {
                result.put(new PriceTarget(product.getId(), null), new StockSnapshot(null, true));
                for (ProductSpec spec : specsByProduct.getOrDefault(product.getId(), List.of())) {
                    result.put(new PriceTarget(product.getId(), spec.getId()), new StockSnapshot(null, true));
                }
                continue;
            }
            Map<PriceTarget, Long> source = product.getStockMode() == StockMode.MANUAL
                    ? manualCounts : cardCounts;
            List<ProductSpec> specs = specsByProduct.getOrDefault(product.getId(), List.of());
            if (product.isSpecEnabled() && !specs.isEmpty()) {
                long total = 0;
                for (ProductSpec spec : specs) {
                    PriceTarget target = new PriceTarget(product.getId(), spec.getId());
                    long available = source.getOrDefault(target, 0L);
                    total += available;
                    result.put(target, new StockSnapshot(available, false));
                }
                result.put(new PriceTarget(product.getId(), null), new StockSnapshot(total, false));
            } else {
                PriceTarget target = new PriceTarget(product.getId(), null);
                result.put(target, new StockSnapshot(source.getOrDefault(target, 0L), false));
            }
        }
        return result;
    }

    private UUID uuid(Object value) {
        return value instanceof UUID id ? id : UUID.fromString(String.valueOf(value));
    }

    private long number(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    public record StockSnapshot(Long available, boolean unlimited) {
    }
}
