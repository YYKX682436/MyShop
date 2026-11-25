package com.myshop.supplier;

import com.myshop.entity.SupplierProductMapping;
import com.myshop.model.CustomerInputField;
import com.myshop.model.pricing.PriceTarget;
import com.myshop.repository.SupplierProductMappingRepository;
import lombok.RequiredArgsConstructor;
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

    public Map<PriceTarget,Long> available(List<UUID> ids) {
        Map<PriceTarget,SupplierStockRules.Availability> availability=availability(ids);
        Map<PriceTarget,Long> result=new HashMap<>();
        availability.forEach((target,value) -> result.put(target,value.quantity()));
        return result;
    }

    public Map<PriceTarget,SupplierStockRules.Availability> availability(List<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<UUID,SupplierService.RuntimeAccount> accounts=new HashMap<>(); Set<UUID> invalidAccounts=new HashSet<>();
        Map<PriceTarget,SupplierStockRules.Availability> result=new HashMap<>(); LocalDateTime now=LocalDateTime.now();
        for (SupplierProductMapping m:mappings.findByProductIdIn(ids)) {
            PriceTarget target=new PriceTarget(m.getProductId(),m.getSpecId());
            var assessment=SupplierStockRules.assess(m,now);
            if (!"AVAILABLE".equals(assessment.status())) { result.put(target,assessment); continue; }
            if (invalidAccounts.contains(m.getAccountId())) { result.put(target,SupplierStockRules.accountUnavailable(m)); continue; }
            SupplierService.RuntimeAccount account=accounts.get(m.getAccountId());
            try {
                if (account==null) { account=suppliers.runtime(m.getAccountId(),true); accounts.put(m.getAccountId(),account); }
            } catch (RuntimeException ignored) {
                invalidAccounts.add(m.getAccountId()); result.put(target,SupplierStockRules.accountUnavailable(m)); continue;
            }
            try {
                var fields=account.provider().customerFields(m.getSnapshot());
                if (!inputs.supported(fields) || m.getGoodsType()==3 && fields.isEmpty()) {
                    result.put(target,SupplierStockRules.unsupportedInputs(m));
                } else result.put(target,assessment);
            } catch (RuntimeException ignored) {
                result.put(target,SupplierStockRules.unsupportedInputs(m));
            }
        }
        return result;
    }
    public List<CustomerInputField> fields(UUID productId, UUID specId) {
        var row=mappings.findLocal(productId,specId);
        if (row.isEmpty()) return List.of();
        try { var mapping=row.get(); return suppliers.customerFields(mapping.getAccountId(),mapping.getSnapshot()); }
        catch (RuntimeException ignored) { return List.of(); }
    }
}
