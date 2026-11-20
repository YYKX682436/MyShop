package com.myshop.supplier;

import com.myshop.entity.SupplierAccount;
import com.myshop.entity.SupplierProductMapping;
import com.myshop.repository.SupplierAccountRepository;
import com.myshop.repository.SupplierProductMappingRepository;
import com.myshop.service.PaymentUrlService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
public class SupplierService {
    private final SupplierAccountRepository accounts;
    private final SupplierProductMappingRepository mappings;
    private final SupplierSecretCipher cipher;
    private final List<SupplierProvider> providers;
    private final SupplierCatalogTransaction transactions;
    private final PaymentUrlService urls;
    private final AtomicBoolean scheduledRunning = new AtomicBoolean();
    private final ExecutorService syncWorker = Executors.newFixedThreadPool(3, task -> {
        Thread thread = new Thread(task, "supplier-sync");
        thread.setDaemon(true);
        return thread;
    });

    public record AccountRequest(String name,String providerType,String baseUrl,String appId,String secret,Boolean enabled) {
        @Override public String toString() { return "SupplierAccountRequest[redacted]"; }
    }
    public record AccountView(UUID id,String name,String providerType,String baseUrl,String appId,String secretMask,boolean enabled,String goodsCallbackUrl) {}
    public record MappingView(UUID id,String remoteGoodsId,String remoteSku,UUID productId,UUID specId,
                              int goodsType,int skuType,@com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) java.math.BigDecimal cost,long stock,int goodsStatus,int skuStatus,
                              int multiple,int minQuantity,int maxQuantity,LocalDateTime lastSyncedAt,
                              LocalDateTime lastAttemptAt,String syncError) {}

    @Transactional(readOnly=true)
    public List<AccountView> listAccounts() { return accounts.findAll().stream().map(this::view).toList(); }
    @Transactional
    public AccountView saveAccount(UUID id,AccountRequest request) {
        if (request==null) throw invalid("货源账号参数不能为空");
        SupplierAccount a=id==null?new SupplierAccount():accounts.findForUpdate(id).orElseThrow(() -> invalid("货源账号不存在"));
        if (id!=null && request.enabled()!=null && request.enabled()!=a.isEnabled())
            throw invalid("请通过账号状态操作确认启用或停用");
        String name=required(request.name(),120,"名称");
        String providerType=required(request.providerType(),32,"供应商类型");
        provider(providerType);
        String baseUrl=baseUrl(request.baseUrl());
        String appId=required(request.appId(),120,"客户编号");
        if (id!=null && !mappings.findByAccountIdOrderByRemoteGoodsIdAscRemoteSkuAsc(id).isEmpty()
                && (!a.getProviderType().equals(providerType) || !a.getBaseUrl().equals(baseUrl) || !a.getAppId().equals(appId))) {
            throw invalid("已有商品映射的货源不能更换站点、系统或客户编号，请添加新货源");
        }
        if (request.secret()!=null && !request.secret().isBlank()) {
            if (request.secret().length()>512) throw invalid("货源密钥过长");
            a.setEncryptedSecret(cipher.encrypt(request.secret()));
        } else if (id==null || a.getEncryptedSecret()==null) throw invalid("请填写货源密钥");
        a.setName(name); a.setProviderType(providerType); a.setBaseUrl(baseUrl); a.setAppId(appId);
        a.setEnabled(request.enabled()==null ? id==null || a.isEnabled() : request.enabled());
        return view(accounts.saveAndFlush(a));
    }
    @Transactional
    public void setEnabled(UUID id,boolean enabled) {
        SupplierAccount a=accounts.findForUpdate(id).orElseThrow(() -> invalid("货源账号不存在"));
        a.setEnabled(enabled); accounts.save(a);
    }
    public SupplierModels.Account test(UUID id) { var a=active(id); return provider(a.getProviderType()).account(connection(a)); }
    public List<SupplierModels.Category> categories(UUID id) { var a=active(id); return provider(a.getProviderType()).categories(connection(a)); }
    public SupplierModels.Page list(UUID id,String keyword,String categoryId,int page) {
        if (page<1 || page>1000 || (keyword!=null && keyword.length()>200) || (categoryId!=null && categoryId.length()>120))
            throw invalid("搜索参数无效");
        var a=active(id); return provider(a.getProviderType()).list(connection(a),keyword,categoryId,page);
    }
    public SupplierModels.Product detail(UUID id,String goodsId) {
        var a=active(id); return provider(a.getProviderType()).detail(connection(a),goodsId);
    }
    public SupplierCatalogTransaction.ImportResult importProduct(UUID accountId,String goodsId,UUID categoryId) {
        if (categoryId==null) throw invalid("请选择本地分类");
        SupplierModels.Product detail=detail(accountId,goodsId);
        return transactions.importProduct(accountId,categoryId,detail);
    }
    @Transactional(readOnly=true)
    public List<MappingView> mappings(UUID id) {
        accounts.findById(id).orElseThrow(() -> invalid("货源账号不存在"));
        return mappings.findByAccountIdOrderByRemoteGoodsIdAscRemoteSkuAsc(id).stream().map(this::view).toList();
    }
    public void sync(UUID accountId,String goodsId) {
        LocalDateTime attempt=transactions.markAttempt(accountId,goodsId);
        try { transactions.applySync(accountId,detail(accountId,goodsId),attempt); }
        catch (SupplierException e) { transactions.markFailure(accountId,goodsId,attempt,e.kind()); throw e; }
        catch (RuntimeException e) {
            transactions.markFailure(accountId,goodsId,attempt,"INTERNAL");
            throw new SupplierException("INTERNAL","货源同步失败，请检查本地商品和规格");
        }
    }
    @Scheduled(fixedDelayString="${supplier.sync-interval-ms:900000}", initialDelayString="${supplier.sync-initial-delay-ms:120000}")
    public void enqueueScheduledSync() {
        if (!scheduledRunning.compareAndSet(false, true)) return;
        try {
            syncWorker.execute(() -> {
                try {
                    List<Object[]> due = mappings.findDue(PageRequest.of(0,20));
                    var tasks = due.stream().map(row -> java.util.concurrent.CompletableFuture.runAsync(
                            () -> syncOne(row), syncWorker)).toArray(java.util.concurrent.CompletableFuture[]::new);
                    java.util.concurrent.CompletableFuture.allOf(tasks)
                            .whenComplete((result, failure) -> scheduledRunning.set(false));
                } catch (RuntimeException ignored) { scheduledRunning.set(false); }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            scheduledRunning.set(false);
        }
    }
    private void syncOne(Object[] row) {
        try { sync((UUID)row[0],(String)row[1]); }
        catch (RuntimeException ignored) { /* error is recorded per product; never log request data */ }
    }
    @PreDestroy public void stopWorker() { syncWorker.shutdownNow(); }
    private SupplierAccount active(UUID id) {
        SupplierAccount a=accounts.findById(id).orElseThrow(() -> invalid("货源账号不存在"));
        if (!a.isEnabled()) throw invalid("货源账号已停用");
        return a;
    }
    /** Historical purchases remain queryable after the account is disabled. */
    public RuntimeAccount runtime(UUID id, boolean requireEnabled) {
        SupplierAccount a=accounts.findById(id).orElseThrow(() -> invalid("货源账号不存在"));
        if (requireEnabled && !a.isEnabled()) throw invalid("货源账号已停用");
        return new RuntimeAccount(a.isEnabled(), provider(a.getProviderType()), connection(a));
    }
    public List<com.myshop.model.CustomerInputField> customerFields(UUID id,com.fasterxml.jackson.databind.JsonNode snapshot) {
        SupplierAccount a=accounts.findById(id).orElseThrow(() -> invalid("货源账号不存在"));
        return provider(a.getProviderType()).customerFields(snapshot);
    }
    public record RuntimeAccount(boolean enabled,SupplierProvider provider,SupplierModels.Connection connection) {
        @Override public String toString() { return "SupplierRuntimeAccount[redacted]"; }
    }
    private SupplierModels.Connection connection(SupplierAccount a) {
        return new SupplierModels.Connection(a.getBaseUrl(),a.getAppId(),cipher.decrypt(a.getEncryptedSecret()));
    }
    private SupplierProvider provider(String type) {
        return providers.stream().filter(p -> p.type().equals(type)).findFirst().orElseThrow(() -> invalid("不支持的供应商类型"));
    }
    private String baseUrl(String value) {
        try {
            String normalized=required(value,500,"站点地址");
            URI uri=URI.create(normalized);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null
                    || uri.getQuery()!=null || uri.getFragment()!=null) throw new IllegalArgumentException();
            return normalized.replaceAll("/+$","");
        } catch (Exception ignored) { throw invalid("站点地址必须是有效的 HTTPS 地址，且不能包含账号、查询参数或片段"); }
    }
    private String required(String value,int max,String field) {
        if (value==null || value.isBlank() || value.strip().length()>max || value.chars().anyMatch(Character::isISOControl))
            throw invalid(field+"无效");
        return value.strip();
    }
    private AccountView view(SupplierAccount a) {
        String callback=null;
        if ("KAYIXIN".equals(a.getProviderType())) {
            try { callback=urls.supplierGoodsWebhookUrl(a.getId().toString()); }
            catch (RuntimeException ignored) { /* public HTTPS is optional until notifications are configured */ }
        }
        return new AccountView(a.getId(),a.getName(),a.getProviderType(),a.getBaseUrl(),a.getAppId(),"••••••••",a.isEnabled(),callback);
    }
    private MappingView view(SupplierProductMapping m) {
        return new MappingView(m.getId(),m.getRemoteGoodsId(),m.getRemoteSku(),m.getProductId(),m.getSpecId(),m.getGoodsType(),m.getSkuType(),
                m.getCost(),m.getStock(),m.getGoodsStatus(),m.getSkuStatus(),m.getMultiple(),m.getMinQuantity(),m.getMaxQuantity(),
                m.getLastSyncedAt(),m.getLastAttemptAt(),m.getSyncError());
    }
    private SupplierException invalid(String message) { return new SupplierException("CONFIG",message); }
}
