package com.myshop.supplier;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.constant.StockMode;
import com.myshop.entity.*;
import com.myshop.repository.*;
import com.myshop.service.PaymentUrlService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** A committed claim permits one create; recovery only queries the original outer number. */
@Service
@RequiredArgsConstructor
public class SupplierPurchaseWorker {
    private final SupplierPurchaseRepository purchases;
    private final SupplierProductMappingRepository mappings;
    private final ProductRepository products;
    private final ProductSpecRepository specs;
    private final OrderItemRepository items;
    private final SupplierService suppliers;
    private final SupplierPurchasePolicy policy;
    private final SupplierRechargeFields inputRules;
    private final SupplierPurchaseTransactions tx;
    private final PaymentUrlService urls;
    private final ObjectMapper json;
    private final AtomicBoolean scanning=new AtomicBoolean();
    private final ExecutorService workers=Executors.newFixedThreadPool(3, r -> {
        Thread t=new Thread(r,"supplier-purchase"); t.setDaemon(true); return t;
    });

    public void enqueueOrder(UUID orderId) {
        for (var p:purchases.findByOrderId(orderId)) submitTask(() -> process(p.getId()));
    }
    public void enqueueRefresh(UUID purchaseId) { submitTask(() -> refresh(purchaseId)); }

    @Scheduled(fixedDelayString="${supplier.purchase-interval-ms:30000}",initialDelayString="${supplier.purchase-initial-delay-ms:30000}")
    public void recover() {
        if (!scanning.compareAndSet(false,true)) return;
        try {
            List<UUID> ids=purchases.findRecoveryCandidates(PageRequest.of(0,20));
            CompletableFuture<?>[] tasks=ids.stream().map(id -> CompletableFuture.runAsync(() -> process(id),workers)
                    .exceptionally(ignored -> null)).toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(tasks).whenComplete((done,error) -> scanning.set(false));
        } catch (RuntimeException ignored) { scanning.set(false); }
    }

    public void process(UUID id) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("provider request inside transaction");
        SupplierPurchase p=purchases.findById(id).orElse(null);
        if (p==null) return;
        if (!"WAITING_PAYMENT".equals(p.getStatus())) {
            if ("SUBMITTING".equals(p.getStatus()) && p.getSubmitStartedAt()!=null
                    && p.getSubmitStartedAt().isAfter(LocalDateTime.now().minusSeconds(30))) return;
            if (List.of("SUBMITTING","UNKNOWN","UNPAID","PROCESSING","REFUNDING").contains(p.getStatus())) refresh(id);
            return;
        }
        Prepared ready;
        try { ready=preflight(p); }
        catch (RuntimeException e) { tx.blockBeforeClaim(id,kind(e)); return; }
        if (!tx.claim(id)) return;
        try {
            // A disable or credential change may have committed during preflight.
            var currentAccount=suppliers.runtime(p.getAccountId(),true);
            var request=new SupplierModels.PurchaseRequest(p.getRemoteGoodsId(),p.getBuyerQuantity(),ready.notifyUrl(),
                    p.getOuterNumber(),p.getSafePrice(),p.getRemoteSku(),attachments(p));
            var created=currentAccount.provider().createPurchase(currentAccount.connection(),request);
            tx.recordCreated(id,created);
        } catch (RuntimeException e) { tx.submissionUnknown(id,kind(e)); }
        refresh(id);
    }

    /** Safe for both administrator refresh and crash recovery; never creates an order. */
    public void refresh(UUID id) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("provider request inside transaction");
        SupplierPurchase p=purchases.findById(id).orElseThrow(); LocalDateTime started=LocalDateTime.now();
        if (p.getSubmitStartedAt()==null) return;
        try {
            var account=suppliers.runtime(p.getAccountId(),false);
            var detail=account.provider().purchaseDetail(account.connection(),p.getOuterNumber());
            tx.applyDetail(id,detail,started);
        } catch (RuntimeException e) { tx.queryFailed(id,kind(e),started); }
    }

    private Prepared preflight(SupplierPurchase p) {
        OrderItem item=items.findById(p.getOrderItemId()).orElseThrow();
        Product product=products.findById(item.getProductId()).orElseThrow();
        if (!product.isEnabled() || product.getIsDeleted()!=0 || product.getStockMode()!=StockMode.SUPPLIER) throw issue("PRODUCT_DISABLED");
        if (item.getSpecId()!=null && specs.findById(item.getSpecId())
                .filter(s -> s.getIsDeleted()==0 && s.isVisible() && s.getProductId().equals(product.getId())).isEmpty()) throw issue("SKU_DISABLED");
        SupplierProductMapping m=mappings.findLocal(item.getProductId(),item.getSpecId()).orElseThrow();
        if (!m.getAccountId().equals(p.getAccountId()) || !m.getRemoteGoodsId().equals(p.getRemoteGoodsId())
                || !m.getRemoteSku().equals(p.getRemoteSku()) || m.getGoodsType()!=p.getGoodsType()
                || m.getMultiple()!=p.getMultiple()) throw issue("MAPPING_CHANGED");
        var account=suppliers.runtime(p.getAccountId(),true);
        BigDecimal revenue = item.getSubsiteSupplyPriceSnapshot() == null ? item.getSubtotal()
                : item.getSubsiteSupplyPriceSnapshot().multiply(BigDecimal.valueOf(item.getQuantity()));
        var quote=policy.quote(m,account.enabled(),p.getBuyerQuantity(),revenue,LocalDateTime.now());
        if (item.getSubsiteSupplyPriceSnapshot()!=null && quote.totalCost().signum()==0) throw issue("COST_EXCEEDED");
        String notifyUrl;
        try { notifyUrl=urls.supplierWebhookUrl(p.getAccountId().toString()); }
        catch (RuntimeException e) { throw issue("CALLBACK_CONFIG"); }
        var remote=account.provider().detail(account.connection(),p.getRemoteGoodsId());
        if (remote.status()!=1 || remote.goodsType()!=p.getGoodsType() || remote.multiple()!=p.getMultiple()) throw issue("PRODUCT_CHANGED");
        if ((remote.minQuantity()>0 && p.getBuyerQuantity()<remote.minQuantity())
                || (remote.maxQuantity()>0 && p.getBuyerQuantity()>remote.maxQuantity())) throw issue("QUANTITY");
        var sku=remote.skus().stream().filter(s -> s.id().equals(p.getRemoteSku())).findFirst().orElseThrow(() -> issue("SKU_CHANGED"));
        if (sku.status()!=1 || sku.stock()<p.getActualQuantity()) throw issue("STOCK");
        BigDecimal cost=sku.cost().multiply(BigDecimal.valueOf(p.getActualQuantity()));
        if ((item.getSubsiteSupplyPriceSnapshot()!=null && cost.signum()==0)
                || cost.compareTo(p.getSafePrice())>0 || cost.compareTo(revenue)>=0) throw issue("COST_EXCEEDED");
        var fields=account.provider().customerFields(remote.snapshot());
        if (!inputRules.supported(fields) || !json.valueToTree(fields).equals(p.getRechargeTemplate())) throw issue("TEMPLATE_CHANGED");
        if (account.provider().account(account.connection()).balance().compareTo(cost)<0) throw issue("BALANCE");
        return new Prepared(notifyUrl);
    }
    private List<SupplierModels.Attachment> attachments(SupplierPurchase p) {
        try { return json.convertValue(p.getAttach(),new TypeReference<List<SupplierModels.Attachment>>(){}); }
        catch (IllegalArgumentException e) { throw issue("DATA"); }
    }
    private String kind(RuntimeException e) { return e instanceof SupplierException s ? s.kind() : "INTERNAL"; }
    private SupplierException issue(String code) { return new SupplierException(code,"货源采购条件发生变化"); }
    private void submitTask(Runnable task) {
        try { workers.execute(() -> { try { task.run(); } catch (RuntimeException ignored) { } }); }
        catch (RejectedExecutionException ignored) { }
    }
    @PreDestroy public void stop() { workers.shutdownNow(); }
    private record Prepared(String notifyUrl) {}
}
