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
import com.myshop.repository.CardKeyRepository;
import com.myshop.repository.ManualStockRepository;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.SupplierPurchaseRepository;
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
    private final CardKeyRepository cardKeys;
    private final ManualStockRepository manualStock;
    private final OrderItemRepository orderItems;
    private final OrderRepository orders;
    private final SupplierPurchaseRepository purchases;
    private final SupplierPresentation presentation;

    @Transactional
    public ImportResult importProduct(UUID accountId, UUID categoryId, SupplierModels.Product remote) {
        var account = accounts.findForUpdate(accountId).orElseThrow(() -> error("货源账号不存在"));
        if (!account.isEnabled()) throw error("货源账号已停用");
        List<SupplierProductMapping> existing=mappings.findByAccountIdAndRemoteGoodsIdOrderByRemoteSku(accountId, remote.id());
        // Historical soft-deleted products must not permanently reserve the remote SKU.
        for (UUID id:existing.stream().map(SupplierProductMapping::getProductId).distinct().sorted().toList()) {
            Product old=products.findByIdForUpdate(id).orElseThrow(() -> error("关联的本地商品不存在"));
            if (old.getIsDeleted()!=0) {
                List<SupplierProductMapping> stale=existing.stream().filter(m -> m.getProductId().equals(id)).toList();
                lockOrders(id, null, true);
                for (SupplierProductMapping row:stale) requireRemovable(row);
                mappings.deleteAll(stale);
            }
        }
        mappings.flush();
        existing=mappings.findByAccountIdAndRemoteGoodsIdOrderByRemoteSku(accountId, remote.id());
        if (!existing.isEmpty()) {
            UUID productId=existing.getFirst().getProductId();
            Set<String> ids=remote.skus().stream().map(SupplierModels.Sku::id).collect(java.util.stream.Collectors.toSet());
            if (existing.size()!=ids.size() || existing.stream().anyMatch(m -> !m.getProductId().equals(productId) || !ids.contains(m.getRemoteSku())))
                throw error("部分上游规格已绑定其他商品，请逐个选择规格绑定");
            return new ImportResult(productId, false);
        }
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
        presentation.apply(product, account.getBaseUrl(), remote.snapshot());
        BigDecimal first=localCost(remote.skus().getFirst().cost(),remote.multiple());
        BigDecimal initialRetail=remote.skus().stream().map(s -> localCost(s.cost(),remote.multiple()))
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
                spec=newSpec(product,sku,remote.multiple(),names,order++, true);
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

    /** Account and product locks serialize binds with sync, checkout and other binds. */
    @Transactional
    public ImportResult bind(UUID accountId, UUID productId, UUID specId, String remoteSku,
                             boolean confirmed, boolean moveConfirmed, BindingExpectation expected, SupplierModels.Product remote) {
        if (!confirmed || productId==null || remoteSku==null) throw error("请选择目标并明确确认绑定");
        SupplierModels.Sku sku=remote.skus().stream().filter(s -> s.id().equals(remoteSku)).findFirst()
                .orElseThrow(() -> error("请选择上游商品的有效规格"));
        Set<UUID> accountIds=new HashSet<>();
        accountIds.add(accountId);
        mappings.findLocalAccount(productId,specId).ifPresent(accountIds::add);
        for (UUID id:accountIds.stream().sorted().toList()) {
            var account=accounts.findForUpdate(id).orElseThrow(() -> error("货源账号不存在"));
            if (id.equals(accountId) && !account.isEnabled()) throw error("货源账号已停用");
        }
        SupplierProductMapping source=mappings.findByAccountIdAndRemoteGoodsIdOrderByRemoteSku(accountId,remote.id())
                .stream().filter(m -> m.getRemoteSku().equals(remoteSku)).findFirst().orElse(null);
        Set<UUID> productIds=new HashSet<>(); productIds.add(productId);
        if (source!=null) productIds.add(source.getProductId());
        Map<UUID,Product> locked=new HashMap<>();
        for (UUID id:productIds.stream().sorted().toList()) locked.put(id,products.findByIdForUpdate(id)
                .orElseThrow(() -> error("本地商品不存在")));
        Product target=locked.get(productId);
        if (target.getIsDeleted()!=0) throw error("目标商品已删除，请选择其他商品");
        if (target.isSpecEnabled()) {
            if (specId==null || specs.findById(specId).filter(s -> s.getProductId().equals(productId) && s.getIsDeleted()==0).isEmpty())
                throw error("请选择本地商品的有效规格");
        } else if (specId!=null) throw error("单规格商品不能选择本地规格");
        SupplierProductMapping current=mappings.findLocal(productId,specId).orElse(null);
        if (current!=null && !accountIds.contains(current.getAccountId())) throw error("绑定关系已变化，请刷新后重试");
        if (expected==null || !java.util.Objects.equals(current==null ? null : current.getId(),expected.targetMappingId())
                || !java.util.Objects.equals(source==null ? null : source.getId(),expected.sourceMappingId())
                || !java.util.Objects.equals(source==null ? null : source.getProductId(),expected.sourceProductId())
                || !java.util.Objects.equals(source==null ? null : source.getSpecId(),expected.sourceSpecId()))
            throw error("绑定关系已变化，请刷新后重新确认");
        boolean moving=source!=null && (!source.getProductId().equals(productId) || !java.util.Objects.equals(source.getSpecId(),specId));
        if (moving && !moveConfirmed) throw error("该上游规格已绑定其他商品，请确认移动绑定");

        Set<UUID> affectedOrders=new HashSet<>(orderItems.orderIdsForBinding(productId,specId,target.getStockMode()!=StockMode.SUPPLIER));
        if (moving) affectedOrders.addAll(orderItems.orderIdsForBinding(source.getProductId(),source.getSpecId(),false));
        for (UUID orderId:affectedOrders.stream().sorted().toList()) orders.findByIdForUpdate(orderId);
        requireClear(productId,specId);
        if (moving) requireClear(source.getProductId(),source.getSpecId());
        if (moving) {
            Product previous=locked.get(source.getProductId());
            if (previous.getIsDeleted()==0) { previous.setEnabled(false); products.save(previous); }
        }
        if (target.getStockMode()!=StockMode.SUPPLIER) {
            if (cardKeys.countByProductId(productId)>0 || manualStock.findByProductIdIn(List.of(productId)).stream()
                    .anyMatch(s -> s.getAvailableQuantity()!=0 || s.getReservedQuantity()!=0)
                    || orderItems.hasUnfinishedForBinding(productId,null,true))
                throw error("本地商品存在卡密、库存或未完成订单，不能转换为货源库存");
            target.setStockMode(StockMode.SUPPLIER); target.setDeliveryType("AUTO");
        }
        if (current!=null && current!=source) { mappings.delete(current); mappings.flush(); }
        SupplierProductMapping mapping=source==null ? new SupplierProductMapping() : source;
        mapping.setAccountId(accountId); mapping.setRemoteGoodsId(remote.id()); mapping.setRemoteSku(sku.id());
        mapping.setProductId(productId); mapping.setSpecId(specId);
        if (specId==null) target.setCostPrice(localCost(sku.cost(),remote.multiple()));
        else {
            ProductSpec spec=specs.findById(specId).orElseThrow();
            spec.setCostPrice(localCost(sku.cost(),remote.multiple())); specs.save(spec);
        }
        update(mapping,remote,sku,LocalDateTime.now());
        products.save(target); mappings.saveAndFlush(mapping);
        if (target.isEnabled() && target.isSpecEnabled()) {
            var bound=mappings.findByProductId(target.getId());
            boolean incomplete=specs.findByProductIdAndIsDeletedOrderBySortOrderAsc(target.getId(),0).stream()
                    .filter(ProductSpec::isVisible).anyMatch(s -> bound.stream().noneMatch(m -> s.getId().equals(m.getSpecId())));
            if (incomplete) { target.setEnabled(false); products.save(target); }
        }
        return new ImportResult(productId,false);
    }

    public record BindingExpectation(UUID targetMappingId,UUID sourceMappingId,UUID sourceProductId,UUID sourceSpecId) {}

    private void requireClear(UUID productId,UUID specId) {
        if (orderItems.hasUnfinishedForBinding(productId,specId,false) || purchases.hasUnfinishedForBinding(productId,specId))
            throw error("该商品规格存在未完成订单、采购或售后操作，请先处理");
    }

    private void lockOrders(UUID productId,UUID specId,boolean allSpecs) {
        for (UUID id:orderItems.orderIdsForBinding(productId,specId,allSpecs).stream().sorted().toList())
            orders.findByIdForUpdate(id);
    }

    private void requireRemovable(SupplierProductMapping row) {
        requireClear(row.getProductId(),row.getSpecId());
    }

    /** Caller holds the product lock; changing mode must not strand an active order. */
    public void requireLocalConversionSafe(UUID productId) {
        if (mappings.existsByProductId(productId)) throw error("请先解除该商品全部货源关联");
        lockOrders(productId,null,true);
        if (orderItems.hasUnfinishedForBinding(productId,null,true) || purchases.hasUnfinishedForProduct(productId))
            throw error("该商品存在未完成订单、采购或售后操作，请先处理");
    }

    /** Same lock order as binding: accounts, product, then orders. */
    @Transactional
    public void deleteProduct(UUID productId) {
        Set<UUID> accountIds=new HashSet<>(mappings.findAccountsByProductId(productId));
        for (UUID id:accountIds.stream().sorted().toList())
            accounts.findForUpdate(id).orElseThrow(() -> error("货源账号不存在"));
        Product product=products.findByIdForUpdate(productId).filter(p -> p.getIsDeleted()==0)
                .orElseThrow(() -> error("本地商品不存在或已删除"));
        List<SupplierProductMapping> rows=mappings.findByProductId(productId);
        if (rows.stream().anyMatch(m -> !accountIds.contains(m.getAccountId())))
            throw error("货源关联已变化，请刷新后重试");
        if (!rows.isEmpty()) {
            lockOrders(productId,null,true);
            for (SupplierProductMapping row:rows) requireRemovable(row);
            mappings.deleteAll(rows);
        }
        product.setEnabled(false); product.setIsDeleted(1); products.save(product);
    }

    @Transactional
    public void unbind(UUID accountId,UUID mappingId,boolean confirmed) {
        if (!confirmed) throw error("请确认解除货源关联");
        List<Object[]> coordinates=mappings.findCoordinates(mappingId);
        if (coordinates.size()!=1 || !accountId.equals(coordinates.getFirst()[0])) throw error("货源关联不存在或已变化");
        UUID productId=(UUID)coordinates.getFirst()[1];
        accounts.findForUpdate(accountId).orElseThrow(() -> error("货源账号不存在"));
        Product product=products.findByIdForUpdate(productId).orElseThrow(() -> error("关联的本地商品不存在"));
        SupplierProductMapping row=mappings.findById(mappingId)
                .filter(m -> m.getAccountId().equals(accountId) && m.getProductId().equals(productId))
                .orElseThrow(() -> error("货源关联已变化，请刷新后重试"));
        lockOrders(productId,row.getSpecId(),false);
        requireRemovable(row);
        mappings.delete(row);
        if (product.getIsDeleted()==0) { product.setEnabled(false); products.save(product); }
    }

    @Transactional
    public void syncPresentation(UUID accountId, UUID productId, SupplierModels.Product remote) {
        var account=accounts.findForUpdate(accountId).orElseThrow(() -> error("货源账号不存在"));
        if (!account.isEnabled()) throw error("货源账号已停用");
        Product product=products.findByIdForUpdate(productId).filter(p -> p.getIsDeleted()==0)
                .orElseThrow(() -> error("本地商品不存在"));
        if (mappings.findByAccountIdAndRemoteGoodsIdOrderByRemoteSku(accountId,remote.id()).stream()
                .noneMatch(m -> m.getProductId().equals(productId))) throw error("商品绑定关系已变化，请刷新后重试");
        presentation.apply(product,account.getBaseUrl(),remote.snapshot());
        products.save(product);
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
        Map<UUID,Product> locked=new HashMap<>();
        for (UUID id:rows.stream().map(SupplierProductMapping::getProductId).distinct().sorted().toList()) {
            Product product=products.findByIdForUpdate(id).orElseThrow(() -> error("本地商品不存在"));
            locked.put(id,product);
        }
        List<SupplierProductMapping> valid=new ArrayList<>();
        for (SupplierProductMapping mapping:rows) {
            Product product=locked.get(mapping.getProductId());
            String localError=null;
            if (product.getIsDeleted()!=0) localError="LOCAL_DELETED";
            else if (product.getStockMode()!=StockMode.SUPPLIER) localError="LOCAL_CHANGED";
            else if ((mapping.getSpecId()==null && product.isSpecEnabled()) ||
                    (mapping.getSpecId()!=null && (!product.isSpecEnabled() || specs.findById(mapping.getSpecId())
                            .filter(s -> s.getIsDeleted()==0 && s.getProductId().equals(product.getId())).isEmpty())))
                localError="LOCAL_SPEC_MISSING";
            if (localError==null) valid.add(mapping);
            else { mapping.setSyncError(localError); mappings.save(mapping); }
        }
        if (valid.isEmpty()) throw error("本地商品已删除或规格已失效，请先解除关联");
        if (valid.stream().anyMatch(m -> m.getSkuType()!=remote.skuType() || m.getGoodsType()!=remote.goodsType()))
            throw new SupplierException("DATA", "上游商品类型或规格模式已变化，需要人工检查");
        Map<String,SupplierModels.Sku> bySku=new HashMap<>();
        remote.skus().forEach(s -> bySku.put(s.id(),s));
        LocalDateTime now=LocalDateTime.now();
        for (SupplierProductMapping mapping:valid) {
            SupplierModels.Sku sku=bySku.get(mapping.getRemoteSku());
            if (sku==null) {
                mapping.setSkuStatus(0); mapping.setGoodsStatus(remote.status()); mapping.setStock(0);
                mapping.setSnapshot(remote.snapshot()); mapping.setLastSyncedAt(now); mapping.setSyncError(null);
                mappings.save(mapping);
                continue;
            }
            Product product=locked.get(mapping.getProductId());
            if (mapping.getSpecId()!=null) {
                ProductSpec spec=specs.findById(mapping.getSpecId()).filter(s -> s.getIsDeleted()==0 && s.getProductId().equals(product.getId()))
                        .orElseThrow(() -> error("本地规格已删除，需要人工检查"));
                spec.setCostPrice(localCost(sku.cost(),remote.multiple()));
                specs.save(spec);
            } else {
                product.setCostPrice(localCost(sku.cost(),remote.multiple()));
                products.save(product);
            }
            update(mapping,remote,sku,now);
            mapping.setLastAttemptAt(attempt);
            mappings.save(mapping);
        }
    }

    private void update(SupplierProductMapping m,SupplierModels.Product p,SupplierModels.Sku s,LocalDateTime now) {
        m.setGoodsType(p.goodsType()); m.setSkuType(p.skuType()); m.setCost(s.cost()); m.setStock(s.stock());
        m.setGoodsStatus(p.status()); m.setSkuStatus(s.status()); m.setMultiple(p.multiple());
        m.setMinQuantity(p.minQuantity()); m.setMaxQuantity(p.maxQuantity()); m.setSnapshot(p.snapshot());
        m.setLastSyncedAt(now); m.setLastAttemptAt(now); m.setSyncError(null);
    }
    private ProductSpec newSpec(Product p,SupplierModels.Sku sku,int multiple,Set<String> names,int order,boolean visible) {
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
        s.setName(candidate); s.setCostPrice(localCost(sku.cost(),multiple));
        s.setRetailPrice(s.getCostPrice().max(new BigDecimal("0.01"))); s.setVisible(visible);
        s.setSortOrder(order); return s;
    }
    private BigDecimal localCost(BigDecimal cost,int multiple) {
        BigDecimal value=cost.multiply(BigDecimal.valueOf(multiple)).setScale(2, RoundingMode.CEILING);
        if (value.compareTo(new BigDecimal("99999999.99"))>0) throw error("上游成本超出本地商品支持范围");
        return value;
    }
    private SupplierException error(String message) { return new SupplierException("DATA",message); }
    public record ImportResult(UUID productId,boolean created) {}
}
