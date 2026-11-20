package com.myshop.supplier;

import com.myshop.entity.SupplierProductMapping;
import com.myshop.model.CustomerInputField;
import com.myshop.model.pricing.PriceTarget;
import com.myshop.repository.SupplierProductMappingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.*;

/** Only fresh, enabled and validated supplier snapshots can be advertised as stock. */
@Service
@RequiredArgsConstructor
public class SupplierStockService {
    private final SupplierProductMappingRepository mappings;
    private final SupplierService suppliers;
    private final SupplierRechargeFields inputs;
    @Value("${supplier.purchase-enabled:false}") private boolean purchaseEnabled;

    public Map<PriceTarget,Long> available(List<UUID> ids) {
        if (!purchaseEnabled || ids.isEmpty()) return Map.of();
        Map<UUID,SupplierService.RuntimeAccount> accounts=new HashMap<>(); Set<UUID> invalidAccounts=new HashSet<>();
        Map<PriceTarget,Long> result=new HashMap<>(); LocalDateTime now=LocalDateTime.now();
        for (SupplierProductMapping m:mappings.findByProductIdIn(ids)) {
            if (invalidAccounts.contains(m.getAccountId())) continue;
            SupplierService.RuntimeAccount account=accounts.get(m.getAccountId());
            try {
                if (account==null) { account=suppliers.runtime(m.getAccountId(),true); accounts.put(m.getAccountId(),account); }
            } catch (RuntimeException ignored) { invalidAccounts.add(m.getAccountId()); continue; }
            try {
                var fields=account.provider().customerFields(m.getSnapshot());
                if (!inputs.supported(fields) || m.getGoodsType()==3 && fields.isEmpty() || m.getGoodsStatus()!=1
                        || m.getSkuStatus()!=1 || m.getMultiple()<1 || m.getSyncError()!=null || m.getLastSyncedAt()==null
                        || m.getLastSyncedAt().isBefore(now.minusMinutes(30)) || m.getLastSyncedAt().isAfter(now.plusMinutes(1))) continue;
                long qty=m.getStock()/m.getMultiple();
                if (m.getMaxQuantity()>0) qty=Math.min(qty,m.getMaxQuantity()/m.getMultiple());
                if (m.getMinQuantity()>0 && qty<m.getMinQuantity()) qty=0;
                result.put(new PriceTarget(m.getProductId(),m.getSpecId()),qty);
            } catch (RuntimeException ignored) { /* Invalid product data does not hide other products. */ }
        }
        return result;
    }
    public List<CustomerInputField> fields(UUID productId) {
        var rows=mappings.findByProductId(productId);
        if (rows.isEmpty()) return List.of();
        try { var first=rows.getFirst(); return suppliers.customerFields(first.getAccountId(),first.getSnapshot()); }
        catch (RuntimeException ignored) { return List.of(); }
    }
}
