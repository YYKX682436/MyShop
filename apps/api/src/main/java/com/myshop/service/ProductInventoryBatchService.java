package com.myshop.service;

import com.myshop.constant.StockMode;
import com.myshop.entity.Product;
import com.myshop.entity.ProductSpec;
import com.myshop.model.pricing.PriceTarget;
import com.myshop.repository.CardKeyRepository;
import com.myshop.repository.ManualStockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.myshop.supplier.SupplierStockService;
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
    private SupplierStockService supplierStockService;
    @Autowired public void setSupplierStockService(SupplierStockService value) { this.supplierStockService=value; }

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
        List<UUID> supplierIds=products.stream().filter(p -> p.getStockMode()==StockMode.SUPPLIER).map(Product::getId).toList();
        Map<PriceTarget,com.myshop.supplier.SupplierStockRules.Availability> supplierAvailability=
                supplierStockService==null ? Map.of() : supplierStockService.availability(supplierIds);
        for (Product product : products) {
            if (product.getStockMode() == StockMode.SUPPLIER) {
                long total=0;
                boolean hasUnknown=false;
                String aggregateState="OUT_OF_STOCK";
                for (ProductSpec spec : specsByProduct.getOrDefault(product.getId(), List.of())) {
                    var availability=supplierAvailability.get(new PriceTarget(product.getId(),spec.getId()));
                    if (availability==null) availability=new com.myshop.supplier.SupplierStockRules.Availability(null,"UNMAPPED",0,0,null);
                    if (availability.quantity()==null) {
                        hasUnknown=true;
                        if (statePriority(availability.status())>statePriority(aggregateState)) aggregateState=availability.status();
                    } else {
                        long count=availability.quantity();
                        total=total>Long.MAX_VALUE-count ? Long.MAX_VALUE : total+count;
                        if (count>0) aggregateState="AVAILABLE";
                        else if (!"AVAILABLE".equals(aggregateState)
                                && statePriority(availability.status())>statePriority(aggregateState)) aggregateState=availability.status();
                    }
                    result.put(new PriceTarget(product.getId(),spec.getId()),snapshot(availability));
                }
                StockSnapshot root;
                if (!product.isSpecEnabled()) {
                    var availability=supplierAvailability.get(new PriceTarget(product.getId(),null));
                    root=availability==null ? new StockSnapshot(null,false,"UNMAPPED",null,null) : snapshot(availability);
                } else {
                    Long rootCount=total>0 ? total : hasUnknown ? null : 0L;
                    root=new StockSnapshot(rootCount,false,total>0 ? "AVAILABLE" : aggregateState,null,null);
                }
                result.put(new PriceTarget(product.getId(),null),root);
                continue;
            }
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

    private StockSnapshot snapshot(com.myshop.supplier.SupplierStockRules.Availability availability) {
        return new StockSnapshot(availability.quantity(),false,availability.status(),availability.minQuantity(),availability.maxQuantity());
    }

    private int statePriority(String status) {
        return switch (status) {
            case "SYNC_FAILED" -> 9;
            case "ACCOUNT_UNAVAILABLE" -> 8;
            case "STALE" -> 7;
            case "INVALID_DATA", "INPUT_UNSUPPORTED", "UNMAPPED" -> 6;
            case "LOCAL_UNAVAILABLE", "UPSTREAM_UNAVAILABLE" -> 5;
            case "BELOW_MINIMUM", "MULTIPLE_SHORTAGE" -> 4;
            case "OUT_OF_STOCK" -> 2;
            default -> 0;
        };
    }

    public record StockSnapshot(Long available, boolean unlimited, String status, Integer minQuantity, Integer maxQuantity) {
        public StockSnapshot(Long available, boolean unlimited) {
            this(available,unlimited,unlimited ? "UNLIMITED" : available==null ? "UNKNOWN" : available>0 ? "AVAILABLE" : "OUT_OF_STOCK",null,null);
        }
    }
}
