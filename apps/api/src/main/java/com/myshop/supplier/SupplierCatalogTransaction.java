package com.myshop.supplier;

import com.myshop.constant.StockMode;
import com.myshop.entity.Product;
import com.myshop.entity.ProductSpec;
import com.myshop.entity.SupplierProductMapping;
import com.myshop.repository.ProductCategoryRepository;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.ProductSpecRepository;
import com.myshop.repository.SupplierAccountRepository;
import com.myshop.repository.SupplierProductMappingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SupplierCatalogTransaction {
    private final SupplierAccountRepository accounts;
    private final SupplierProductMappingRepository mappings;
    private final ProductRepository products;
    private final ProductSpecRepository specs;
    private final ProductCategoryRepository categories;

    @Transactional
    public ImportResult importProduct(UUID accountId, UUID categoryId, SupplierModels.Product remote) {
        var account = accounts.findForUpdate(accountId).orElseThrow(() -> error("货源账号不存在"));
        if (!account.isEnabled()) throw error("货源账号已停用");
        List<SupplierProductMapping> existing=mappings.findByAccountIdAndRemoteGoodsIdOrderByRemoteSku(accountId, remote.id());
        if (!existing.isEmpty()) return new ImportResult(existing.getFirst().getProductId(), false);
        categories.findById(categoryId).filter(c -> c.getIsDeleted()==0).orElseThrow(() -> error("请选择有效的本地分类"));
        if (remote.name().length()>255) throw error("上游商品名称超过本地长度限制");
        Product product=new Product();
        product.setProductNo(products.nextProductNoValue());
        product.setTitle(remote.name());
        product.setCategoryId(categoryId);
        product.setDeliveryType("AUTO");
        product.setStockMode(StockMode.SUPPLIER);
        product.setEnabled(false);
        product.setSpecEnabled(remote.skuType()!=0);
        BigDecimal first=localCost(remote.skus().getFirst().cost());
        BigDecimal initialRetail=remote.skus().stream().map(s -> localCost(s.cost()))
                .min(BigDecimal::compareTo).orElse(first);
        product.setRetailPrice(initialRetail.max(new BigDecimal("0.01")));
        product.setCostPrice(product.isSpecEnabled() ? null : first);
        product=products.saveAndFlush(product);
        LocalDateTime now=LocalDateTime.now();
        Set<String> names = new HashSet<>();
        int order=0;
        for (SupplierModels.Sku sku:remote.skus()) {
            ProductSpec spec=null;
            if (product.isSpecEnabled()) {
                spec=newSpec(product,sku,names,order++, true);
                specs.saveAndFlush(spec);
            }
            SupplierProductMapping mapping=new SupplierProductMapping();
            mapping.setAccountId(accountId);
            mapping.setRemoteGoodsId(remote.id());
            mapping.setRemoteSku(sku.id());
            mapping.setProductId(product.getId());
            mapping.setSpecId(spec==null ? null : spec.getId());
            update(mapping, remote, sku, now);
            mappings.save(mapping);
        }
        mappings.flush();
        return new ImportResult(product.getId(),true);
    }

    @Transactional
    public LocalDateTime markAttempt(UUID accountId,String goodsId) {
        accounts.findForUpdate(accountId).orElseThrow(() -> error("货源账号不存在"));
        List<SupplierProductMapping> rows=mappings.findByAccountIdAndRemoteGoodsIdOrderByRemoteSku(accountId,goodsId);
        if (rows.isEmpty()) throw error("商品尚未导入");
        LocalDateTime now=LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        rows.forEach(m -> m.setLastAttemptAt(now));
        mappings.saveAll(rows);
        return now;
    }

    @Transactional
    public void markFailure(UUID accountId,String goodsId,LocalDateTime attempt,String kind) {
        accounts.findForUpdate(accountId).orElseThrow(() -> error("货源账号不存在"));
        List<SupplierProductMapping> rows=mappings.findByAccountIdAndRemoteGoodsIdOrderByRemoteSku(accountId,goodsId);
        for (var m:rows) if (m.getLastAttemptAt().equals(attempt)) m.setSyncError(kind);
        mappings.saveAll(rows);
    }

    @Transactional
    public void applySync(UUID accountId, SupplierModels.Product remote, LocalDateTime attempt) {
        var account=accounts.findForUpdate(accountId).orElseThrow(() -> error("货源账号不存在"));
        if (!account.isEnabled()) throw error("货源账号已停用");
        List<SupplierProductMapping> rows=mappings.findByAccountIdAndRemoteGoodsIdOrderByRemoteSku(accountId,remote.id());
        if (rows.isEmpty()) throw error("商品尚未导入");
        if (rows.stream().anyMatch(m -> !m.getLastAttemptAt().equals(attempt))) return;
        if (rows.getFirst().getSkuType()!=remote.skuType() || rows.getFirst().getGoodsType()!=remote.goodsType())
            throw new SupplierException("DATA", "上游商品类型或规格模式已变化，需要人工检查");
        Product product=products.findByIdForUpdate(rows.getFirst().getProductId()).orElseThrow(() -> error("本地商品不存在"));
        if (product.getStockMode()!=StockMode.SUPPLIER || product.getIsDeleted()!=0)
            throw error("本地商品状态已变化，需要人工检查");
        Map<String,SupplierProductMapping> bySku=new HashMap<>();
        rows.forEach(m -> bySku.put(m.getRemoteSku(),m));
        Set<String> names=new HashSet<>();
        List<ProductSpec> localSpecs=specs.findByProductIdAndIsDeletedOrderBySortOrderAsc(product.getId(),0);
        localSpecs.forEach(s -> names.add(s.getName().toLowerCase(java.util.Locale.ROOT)));
        int order=localSpecs.size();
        Set<String> seen=new HashSet<>();
        LocalDateTime now=LocalDateTime.now();
        for (SupplierModels.Sku sku:remote.skus()) {
            seen.add(sku.id());
            SupplierProductMapping mapping=bySku.get(sku.id());
            if (mapping==null) {
                if (!product.isSpecEnabled()) throw error("上游单规格标识已变化，需要人工检查");
                ProductSpec spec=newSpec(product,sku,names,order++,false);
                specs.saveAndFlush(spec);
                mapping=new SupplierProductMapping();
                mapping.setAccountId(accountId);
                mapping.setRemoteGoodsId(remote.id());
                mapping.setRemoteSku(sku.id());
                mapping.setProductId(product.getId());
                mapping.setSpecId(spec.getId());
            } else if (mapping.getSpecId()!=null) {
                ProductSpec spec=specs.findById(mapping.getSpecId()).filter(s -> s.getIsDeleted()==0)
                        .orElseThrow(() -> error("本地规格已删除，需要人工检查"));
                spec.setCostPrice(localCost(sku.cost()));
                specs.save(spec);
            } else {
                product.setCostPrice(localCost(sku.cost()));
                products.save(product);
            }
            update(mapping,remote,sku,now);
            mapping.setLastAttemptAt(attempt);
            mappings.save(mapping);
        }
        for (SupplierProductMapping old:rows) if (!seen.contains(old.getRemoteSku())) {
            old.setSkuStatus(0);
            old.setGoodsStatus(remote.status());
            old.setStock(0);
            old.setSnapshot(remote.snapshot());
            old.setLastSyncedAt(now);
            old.setSyncError(null);
            mappings.save(old);
        }
    }

    private void update(SupplierProductMapping m,SupplierModels.Product p,SupplierModels.Sku s,LocalDateTime now) {
        m.setGoodsType(p.goodsType()); m.setSkuType(p.skuType()); m.setCost(s.cost()); m.setStock(s.stock());
        m.setGoodsStatus(p.status()); m.setSkuStatus(s.status()); m.setMultiple(p.multiple());
        m.setMinQuantity(p.minQuantity()); m.setMaxQuantity(p.maxQuantity()); m.setSnapshot(p.snapshot());
        m.setLastSyncedAt(now); m.setLastAttemptAt(now); m.setSyncError(null);
    }
    private ProductSpec newSpec(Product p,SupplierModels.Sku sku,Set<String> names,int order,boolean visible) {
        String label=(sku.group().isBlank() ? "" : sku.group()+" / ")+sku.name();
        if (label.isBlank()) label=sku.id();
        if (label.length()>200) label=label.substring(0,200);
        String candidate=label;
        if (!names.add(candidate.toLowerCase(java.util.Locale.ROOT))) {
            candidate=label+" ["+sku.id()+"]";
            if (candidate.length()>255) candidate=candidate.substring(0,255);
            if (!names.add(candidate.toLowerCase(java.util.Locale.ROOT))) throw error("上游规格名称重复且无法安全映射");
        }
        ProductSpec s=new ProductSpec(); s.setId(UUID.randomUUID()); s.setProductId(p.getId());
        s.setName(candidate); s.setCostPrice(localCost(sku.cost()));
        s.setRetailPrice(s.getCostPrice().max(new BigDecimal("0.01"))); s.setVisible(visible);
        s.setSortOrder(order); return s;
    }
    private BigDecimal localCost(BigDecimal cost) {
        BigDecimal value=cost.setScale(2, RoundingMode.CEILING);
        if (value.compareTo(new BigDecimal("99999999.99"))>0) throw error("上游成本超出本地商品支持范围");
        return value;
    }
    private SupplierException error(String message) { return new SupplierException("DATA",message); }
    public record ImportResult(UUID productId,boolean created) {}
}
