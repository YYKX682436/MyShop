package com.myshop.supplier;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.constant.ErrorCode;
import com.myshop.context.RequestContext;
import com.myshop.entity.SupplierSensitiveOperation;
import com.myshop.exception.BusinessException;
import com.myshop.repository.SupplierSensitiveOperationRepository;
import com.myshop.service.OperationLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.*;

/** Explicit, bounded administrator operations. Recovery only marks uncertainty. */
@Service @RequiredArgsConstructor
public class SupplierSensitiveService {
    private final SupplierSensitiveTransactions tx;
    private final SupplierSensitiveOperationRepository operations;
    private final SupplierService suppliers;
    private final SupplierSecretCipher cipher;
    private final ObjectMapper json;
    private final OperationLogService audit;
    @Value("${supplier.transfer-enabled:false}") private boolean transferEnabled;
    @Value("${supplier.cdkey-enabled:false}") private boolean cdkeyEnabled;
    @Value("${supplier.transfer-max-amount:100}") private BigDecimal transferMax;
    @Value("${supplier.cdkey-max-count:20}") private int cdkeyMaxCount;
    @Value("${supplier.cdkey-max-exposure:100}") private BigDecimal cdkeyMaxExposure;

    public record TransferInput(UUID idempotencyKey,Boolean confirmed,String toUserNo,BigDecimal money,String salePwd) {
        @Override public String toString() { return "TransferInput[redacted]"; }
    }
    public record CdkeyInput(UUID idempotencyKey,Boolean confirmed,String goodsId,Integer cardCount,Integer shortStatus,
                             String cardPrefix,Integer cardLength,Integer shortLength,Integer count,BigDecimal cardFaceValue,
                             BigDecimal safePrice,Integer cardDay,String cardRemark) {
        @Override public String toString() { return "CdkeyInput[redacted]"; }
    }

    public Map<String,Object> transfer(UUID accountId,TransferInput input) {
        UUID operator=operator(true); outsideTransaction();
        if (!transferEnabled) throw invalid("账户转账当前未启用");
        if (input==null || input.idempotencyKey()==null || !Boolean.TRUE.equals(input.confirmed())
                || input.toUserNo()==null || !input.toUserNo().matches("[1-9][0-9]{0,17}")
                || !positiveMoney(input.money()) || transferMax==null || transferMax.signum()<=0
                || input.money().compareTo(transferMax)>0 || input.salePwd()!=null && input.salePwd().length()>128)
            throw invalid("转账参数或单次限额无效，请确认后重试");
        long target=Long.parseLong(input.toUserNo());
        JsonNode params=json.valueToTree(Map.of("to_user_no",input.toUserNo(),"money",input.money().stripTrailingZeros().toPlainString()));
        UUID id=tx.intent(accountId,"TRANSFER",input.idempotencyKey(),operator,params);
        var op=operations.findById(id).orElseThrow();
        if (!"INTENT".equals(op.getStatus())) return view(op);
        SupplierService.RuntimeAccount account;
        try {
            account=suppliers.runtime(accountId,true);
            if (!account.provider().supportsTransfer()) throw new SupplierException("UNSUPPORTED","该货源不支持转账");
            if (account.provider().account(account.connection()).balance().compareTo(input.money())<0)
                throw new SupplierException("BALANCE","供货账号余额不足");
        } catch (RuntimeException e) { tx.rejectBeforeSend(id,kind(e)); return view(operations.findById(id).orElseThrow()); }
        if (tx.claim(id,account)) {
            try {
                account.provider().transfer(account.connection(),new SupplierModels.TransferRequest(target,input.money(),op.getOuterNumber(),input.salePwd()));
                tx.succeeded(id,null);
            } catch (RuntimeException e) { tx.unknown(id,kind(e)); }
        }
        return view(operations.findById(id).orElseThrow());
    }

    public Map<String,Object> createCdkeys(UUID accountId,CdkeyInput input) {
        UUID operator=operator(false); outsideTransaction();
        if (!cdkeyEnabled) throw invalid("CDKEY 生成当前未启用");
        SupplierModels.CdkeyRequest req=normalize(input);
        JsonNode params=cdkeyParameters(req);
        UUID id=tx.intent(accountId,"CDKEY",input.idempotencyKey(),operator,params);
        var op=operations.findById(id).orElseThrow();
        if (!"INTENT".equals(op.getStatus())) return view(op);
        SupplierService.RuntimeAccount account;
        try {
            account=suppliers.runtime(accountId,true);
            if (!account.provider().supportsCdkeys()) throw new SupplierException("UNSUPPORTED","该货源不支持生成 CDKEY");
            var product=account.provider().detail(account.connection(),req.goodsId());
            // The CDKEY API has no SKU field; do not guess which price a multi-SKU product uses.
            if (!product.id().equals(req.goodsId()) || product.status()!=1 || product.skuType()!=0
                    || product.skus().size()!=1 || product.multiple()<1) throw new SupplierException("PRODUCT","商品或规格不支持生成");
            var sku=product.skus().getFirst();
            long perCode=Math.multiplyExact((long)req.count(),product.multiple());
            long total=Math.multiplyExact(perCode,(long)req.cardCount());
            if (sku.status()!=1 || sku.cost()==null || sku.cost().signum()<0 || req.safePrice().compareTo(sku.cost())<0
                    || product.minQuantity()>0 && (req.count()<product.minQuantity() || perCode<product.minQuantity())
                    || product.maxQuantity()>0 && (req.count()>product.maxQuantity() || perCode>product.maxQuantity())
                    || sku.stock()<total) throw new SupplierException("PRODUCT","商品价格、库存或兑换数量不符合条件");
            BigDecimal exposure=req.safePrice().multiply(BigDecimal.valueOf(total));
            if (cdkeyMaxExposure==null || cdkeyMaxExposure.signum()<=0 || exposure.compareTo(cdkeyMaxExposure)>0)
                throw new SupplierException("LIMIT","兑换成本风险超过限额");
            if (account.provider().account(account.connection()).balance().compareTo(exposure)<0)
                throw new SupplierException("BALANCE","供货账号余额不足以覆盖风险上限");
        } catch (RuntimeException e) { tx.rejectBeforeSend(id,kind(e)); return view(operations.findById(id).orElseThrow()); }
        if (tx.claim(id,account)) {
            try {
                List<SupplierModels.Cdkey> codes=account.provider().createCdkeys(account.connection(),req);
                validateCodes(codes,req);
                tx.succeeded(id,codes);
            } catch (RuntimeException e) { tx.unknown(id,kind(e)); }
        }
        return view(operations.findById(id).orElseThrow());
    }

    public List<Map<String,Object>> list(UUID accountId,String kind) {
        operator("TRANSFER".equals(kind)); checkKind(kind);
        return operations.findByAccountIdAndKindOrderByCreatedAtDesc(accountId,kind,PageRequest.of(0,100)).stream().map(this::view).toList();
    }
    public List<SupplierModels.Cdkey> result(UUID accountId,UUID id) {
        operator(false);
        var op=lookup(accountId,id,"CDKEY");
        if (!"SUCCEEDED".equals(op.getStatus()) || op.getEncryptedResult()==null) throw invalid("兑换码结果不可用，请人工核实");
        try {
            var codes=json.readValue(cipher.decrypt(op.getEncryptedResult()),new TypeReference<List<SupplierModels.Cdkey>>(){});
            audit.logCurrentRequest("supplier.cdkey.view","SUPPLIER_OPERATION",id.toString(),"查看兑换码结果");
            return codes;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw invalid("兑换码结果无法读取"); }
    }
    public Map<String,Object> review(UUID accountId,UUID id,String kind,String outcome) {
        UUID operator=operator("TRANSFER".equals(kind)); checkKind(kind);
        if (outcome==null) throw invalid("核实结果无效");
        tx.review(id,accountId,kind,operator,outcome);
        return view(lookup(accountId,id,kind));
    }
    public Map<String,Object> cancel(UUID accountId,UUID id,String kind) {
        operator("TRANSFER".equals(kind)); checkKind(kind);
        tx.cancelIntent(id,accountId,kind);
        return view(lookup(accountId,id,kind));
    }
    public Map<String,Object> capabilities() {
        operator(false);
        boolean isSuper="SUPER_ADMIN".equals(RequestContext.getRole());
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("transfer_enabled",isSuper && transferEnabled); m.put("transfer_max_amount",transferMax);
        m.put("cdkey_enabled",cdkeyEnabled); m.put("cdkey_max_count",cdkeyMaxCount); m.put("cdkey_max_exposure",cdkeyMaxExposure);
        return m;
    }
    @Scheduled(fixedDelayString="${supplier.sensitive-recovery-interval-ms:30000}",initialDelayString="${supplier.purchase-initial-delay-ms:30000}")
    public void recover() {
        for (UUID id:operations.stale(LocalDateTime.now().minusSeconds(30),PageRequest.of(0,100)))
            try { tx.unknown(id,"UNCONFIRMED"); } catch (RuntimeException ignored) { }
    }
    private SupplierModels.CdkeyRequest normalize(CdkeyInput x) {
        if (x==null || x.idempotencyKey()==null || !Boolean.TRUE.equals(x.confirmed()) || x.goodsId()==null
                || !x.goodsId().matches("[1-9][0-9]{0,17}") || x.cardCount()==null || x.cardCount()<1
                || cdkeyMaxCount<1 || x.cardCount()>cdkeyMaxCount || x.cardCount()>1000
                || x.count()==null || x.count()<1 || x.count()>1000 || x.shortStatus()==null
                || !Set.of(0,1).contains(x.shortStatus()) || x.cardLength()==null || x.cardLength()<5 || x.cardLength()>32
                || x.shortLength()==null || x.shortLength()<5 || x.shortLength()>32
                || x.cardDay()==null || x.cardDay()<-1 || !nonnegativeMoney(x.cardFaceValue()) || !positiveMoney(x.safePrice()))
            throw invalid("CDKEY 参数或数量限额无效，请确认后重试");
        String prefix=x.cardPrefix()==null ? "" : x.cardPrefix(); String remark=x.cardRemark()==null ? "" : x.cardRemark();
        if (prefix.length()>20 || remark.length()>255 || prefix.chars().anyMatch(Character::isISOControl)
                || remark.chars().anyMatch(Character::isISOControl)) throw invalid("前缀或备注无效");
        return new SupplierModels.CdkeyRequest(x.goodsId(),x.cardCount(),x.shortStatus(),prefix,x.cardLength(),x.shortLength(),
                x.count(),x.cardFaceValue().stripTrailingZeros(),x.safePrice().stripTrailingZeros(),x.cardDay(),remark);
    }
    private JsonNode cdkeyParameters(SupplierModels.CdkeyRequest r) {
        var p=json.createObjectNode();
        p.put("goods_id",r.goodsId()); p.put("card_count",r.cardCount()); p.put("short_status",r.shortStatus());
        p.put("card_prefix",r.cardPrefix()); p.put("card_length",r.cardLength()); p.put("short_length",r.shortLength());
        p.put("count",r.count()); p.put("card_face_value",r.cardFaceValue().toPlainString());
        p.put("safe_price",r.safePrice().toPlainString()); p.put("card_day",r.cardDay()); p.put("card_remark",r.cardRemark());
        return p;
    }
    private void validateCodes(List<SupplierModels.Cdkey> codes,SupplierModels.CdkeyRequest request) {
        if (codes==null || codes.size()!=request.cardCount()) throw new SupplierException("DATA","兑换码结果不完整");
        Set<String> seen=new HashSet<>();
        for (var c:codes) {
            if (c==null || c.pwd()==null || c.pwd().isBlank() || !c.pwd().startsWith(request.cardPrefix())
                    || c.pwd().length()!=request.cardPrefix().length()+request.cardLength()
                    || c.pwd().chars().anyMatch(Character::isISOControl) || !seen.add(c.pwd())) throw new SupplierException("DATA","兑换码结果无效");
            if (c.url()==null || c.url().isBlank()) { if (request.shortStatus()==1) throw new SupplierException("DATA","短链接缺失"); }
            else {
                try { URI u=URI.create(c.url()); if (c.url().length()>2048 || u.getHost()==null || u.getUserInfo()!=null
                        || !("http".equalsIgnoreCase(u.getScheme()) || "https".equalsIgnoreCase(u.getScheme()))) throw new IllegalArgumentException(); }
                catch (IllegalArgumentException e) { throw new SupplierException("DATA","短链接无效"); }
            }
        }
    }
    private boolean nonnegativeMoney(BigDecimal n) { return n!=null && n.signum()>=0 && n.scale()<=8 && n.precision()-n.scale()<=16; }
    private boolean positiveMoney(BigDecimal n) { return nonnegativeMoney(n) && n.signum()>0; }
    private UUID operator(boolean superOnly) {
        var user=RequestContext.get();
        if (user==null || user.getUserId()==null || user.getRole()==null || (superOnly ? !"SUPER_ADMIN".equals(user.getRole())
                : !Set.of("ADMIN","SUPER_ADMIN").contains(user.getRole()))) throw new BusinessException(ErrorCode.FORBIDDEN,"没有供应商操作权限");
        return user.getUserId();
    }
    private void outsideTransaction() { if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("provider request inside transaction"); }
    private void checkKind(String kind) { if (!Set.of("TRANSFER","CDKEY").contains(kind)) throw invalid("操作类型无效"); }
    private SupplierSensitiveOperation lookup(UUID account,UUID id,String kind) {
        return operations.findById(id).filter(o -> o.getAccountId().equals(account) && o.getKind().equals(kind))
                .orElseThrow(() -> invalid("操作记录不存在"));
    }
    private Map<String,Object> view(SupplierSensitiveOperation op) {
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("id",op.getId()); m.put("kind",op.getKind()); m.put("status",op.getStatus());
        m.put("account_name",op.getAccountNameSnapshot()); m.put("provider_type",op.getProviderTypeSnapshot());
        m.put("base_url",op.getBaseUrlSnapshot()); m.put("app_id",op.getAppIdSnapshot());
        m.put("parameters",op.getParameters()); m.put("outer_number",op.getOuterNumber());
        m.put("operator_id",op.getOperatorId()); m.put("created_at",op.getCreatedAt());
        m.put("submitted_at",op.getSubmittedAt()); m.put("error_code",op.getErrorCode());
        m.put("review_outcome",op.getReviewOutcome()); m.put("reviewed_by",op.getReviewedBy()); m.put("reviewed_at",op.getReviewedAt());
        return m;
    }
    private String kind(RuntimeException e) { return e instanceof SupplierException s ? s.kind() : "INTERNAL"; }
    private SupplierException invalid(String message) { return new SupplierException("CONFIG",message); }
}
