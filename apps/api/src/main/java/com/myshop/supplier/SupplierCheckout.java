package com.myshop.supplier;

import com.myshop.entity.OrderItem;
import com.myshop.entity.Product;
import com.myshop.entity.SupplierProductMapping;
import com.myshop.model.CustomerInputField;
import com.myshop.model.CustomerInputValue;
import com.myshop.repository.SupplierProductMappingRepository;
import com.myshop.repository.ProductSpecRepository;
import com.myshop.service.ProductRules;
import com.myshop.constant.ErrorCode;
import com.myshop.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.UUID;

/** Checkout validation and immutable purchase snapshot, within the customer order transaction. */
@Service
@RequiredArgsConstructor
public class SupplierCheckout {
    private final SupplierProductMappingRepository mappings;
    private final SupplierService suppliers;
    private final SupplierRechargeFields inputRules;
    private final SupplierPurchasePolicy policy;
    private final SupplierPurchaseTransactions transactions;
    private final ProductSpecRepository specs;

    public void validateVisibleBindings(Product product, List<UUID> visibleSpecIds) {
        var rows=mappings.findByProductId(product.getId());
        if (product.isSpecEnabled()) {
            if (visibleSpecIds.stream().anyMatch(id -> rows.stream().noneMatch(row -> id.equals(row.getSpecId()))))
                throw new SupplierException("MAPPING","有可售规格尚未关联货源，请先配置");
        } else if (rows.stream().noneMatch(row -> row.getSpecId()==null)) {
            throw new SupplierException("MAPPING","商品尚未关联货源，请先配置");
        }
    }

    public void validateActivation(Product product) {
        var rows=mappings.findByProductId(product.getId());
        if (rows.isEmpty()) throw new SupplierException("MAPPING","货源商品映射不存在");
        validateVisibleBindings(product,specs.findByProductIdAndIsDeletedOrderBySortOrderAsc(product.getId(),0).stream()
                .filter(com.myshop.entity.ProductSpec::isVisible).map(com.myshop.entity.ProductSpec::getId).toList());
        for (var row:rows) {
            var runtime=suppliers.runtime(row.getAccountId(),true);
            var fields=runtime.provider().customerFields(row.getSnapshot());
            if (!inputRules.supported(fields) || row.getGoodsType()==3 && fields.isEmpty())
                throw new SupplierException("TEMPLATE","直充模板缺失或包含尚未验证的字段，暂不能销售");
            Set<String> ids=new HashSet<>();
            for (var field:ProductRules.currentFields(product)) ids.add(field.id());
            for (var field:fields) if (!ids.add(field.id()))
                throw new SupplierException("TEMPLATE","客户字段与货源充值字段冲突，请检查商品配置");
            BigDecimal expected=row.getCost().multiply(BigDecimal.valueOf(row.getMultiple()))
                    .setScale(2,java.math.RoundingMode.CEILING);
            BigDecimal local=row.getSpecId()==null ? product.getCostPrice()
                    : specs.findById(row.getSpecId()).orElseThrow().getCostPrice();
            if (local==null || local.compareTo(expected)!=0)
                throw new SupplierException("STALE_COST","请先同步货源商品成本后再上架");
        }
    }

    public Prepared prepare(Product product,UUID specId,int quantity,BigDecimal customerTotal,Object rawInputs) {
        SupplierProductMapping m=mappings.findLocal(product.getId(),specId)
                .orElseThrow(() -> new SupplierException("MAPPING","货源商品映射不存在"));
        if (com.myshop.context.SiteRequestContext.getSite() != null && m.getCost() != null && m.getCost().signum() == 0)
            throw new BusinessException(ErrorCode.BAD_REQUEST, "零成本货源暂不支持分站购买，请联系站长");
        var runtime=suppliers.runtime(m.getAccountId(),true);
        var quote=policy.quote(m,runtime.enabled(),quantity,customerTotal,LocalDateTime.now());
        List<CustomerInputField> fields=runtime.provider().customerFields(m.getSnapshot());
        if (m.getGoodsType()==3 && fields.isEmpty()) throw new SupplierException("TEMPLATE","直充模板缺失，暂不能购买");
        Set<String> supplierIds=new HashSet<>(),localIds=new HashSet<>();
        for (var field:fields) {
            if (!supplierIds.add(field.id())) throw new SupplierException("TEMPLATE","货源充值字段重复");
        }
        for (var field:ProductRules.currentFields(product)) {
            if (!localIds.add(field.id()) || supplierIds.contains(field.id()))
                throw new SupplierException("TEMPLATE","客户字段与货源充值字段冲突，请检查商品配置");
        }
        List<Object> localRaw=new ArrayList<>(),supplierRaw=new ArrayList<>();
        if (rawInputs!=null) {
            if (!(rawInputs instanceof List<?> rawList)) throw badInputs();
            for (Object input:rawList) {
                Object id=input instanceof Map<?,?> map ? map.get("id")
                        : input instanceof CustomerInputValue value ? value.id() : null;
                if (id instanceof String key && localIds.contains(key)) localRaw.add(input);
                else if (id instanceof String key && supplierIds.contains(key)) supplierRaw.add(input);
                else throw badInputs();
            }
        }
        var localValues=ProductRules.normalizeCustomerInputs(product,localRaw,rawInputs!=null);
        var supplied=inputRules.validate(fields,rawInputs==null ? null : supplierRaw);
        List<CustomerInputValue> values=new ArrayList<>(localValues); values.addAll(supplied.values());
        var inputs=new SupplierRechargeFields.Inputs(List.copyOf(values),supplied.attach());
        return new Prepared(m,quote,fields,inputs);
    }
    public void persist(OrderItem item,Prepared prepared) {
        transactions.createIntent(item,prepared.mapping(),prepared.quote().actualQuantity(),
                prepared.quote().totalCost(),prepared.inputs().attach(),prepared.fields());
    }
    public record Prepared(SupplierProductMapping mapping,SupplierPurchasePolicy.Quote quote,
                           List<CustomerInputField> fields,SupplierRechargeFields.Inputs inputs) {
        @Override public String toString() { return "SupplierCheckout[redacted]"; }
    }
    private BusinessException badInputs() { return new BusinessException(ErrorCode.BAD_REQUEST,"客户填写字段不匹配，请刷新后重试"); }
}
