package com.myshop.supplier;

import com.myshop.constant.ErrorCode;
import com.myshop.context.RequestContext;
import com.myshop.entity.*;
import com.myshop.exception.BusinessException;
import com.myshop.repository.*;
import com.myshop.service.PaymentUrlService;
import com.myshop.service.PrivateUploadStorage;
import com.myshop.service.OperationLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.LocalDateTime;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit administrator actions only; recovery performs reads, never repeats a write. */
@Service @RequiredArgsConstructor
public class SupplierAftercareService {
    private final SupplierAftercareTransactions transactions;
    private final SupplierAftercareOperationRepository operations;
    private final SupplierPurchaseRepository purchases;
    private final SupplierDeliveryCardRepository cards;
    private final SupplierService suppliers;
    private final SupplierPurchaseWorker purchaseWorker;
    private final AfterSaleAttachmentRepository attachments;
    private final PrivateUploadStorage storage;
    private final PaymentUrlService urls;
    private final ObjectMapper json;
    private final OperationLogRepository logs;
    private final OperationLogService audit;
    private final SupplierComplaintNotifications complaintNotifications;
    private final AtomicBoolean scanning=new AtomicBoolean();
    private final ExecutorService recovery=Executors.newFixedThreadPool(2,r -> {
        Thread t=new Thread(r,"supplier-aftercare-recovery"); t.setDaemon(true); return t;
    });

    public Map<String,Object> refund(UUID orderId,UUID purchaseId,UUID key,boolean confirmed) {
        UUID operator=admin(); requireConfirmation(confirmed); outsideTransaction();
        UUID id=transactions.intent(orderId,purchaseId,"REFUND",key,null,null,operator,null,null,List.of());
        if (transactions.claim(id)) {
            SupplierPurchase p=purchases.findById(purchaseId).orElseThrow();
            try {
                var account=suppliers.runtime(p.getAccountId(),false);
                var result=account.provider().requestRefund(account.connection(),p.getOuterNumber());
                transactions.accepted(id,result.status(),null,null);
            } catch (RuntimeException e) { transactions.unknown(id,kind(e)); }
            refreshInternal(id);
        }
        return view(operations.findById(id).orElseThrow());
    }
    public Map<String,Object> queryCard(UUID orderId,UUID purchaseId,UUID cardId) {
        admin(); outsideTransaction();
        return queryCardInternal(orderId,purchaseId,cardId);
    }
    public Map<String,Object> complaint(UUID orderId,UUID purchaseId,UUID afterSaleId,UUID key,
                                       boolean confirmed,String topic,String content,List<UUID> attachmentIds) {
        UUID operator=admin(); requireConfirmation(confirmed); outsideTransaction();
        String normalizedTopic=topic==null ? null : topic.strip();
        String normalizedContent=content==null ? "" : content.strip();
        if (normalizedTopic!=null && normalizedTopic.codePointCount(0,normalizedTopic.length())>50)
            throw invalid("申诉主题最多 50 字");
        if (normalizedContent.isBlank() || normalizedContent.codePointCount(0,normalizedContent.length())>500)
            throw invalid("申诉内容须为 1 至 500 字");
        List<UUID> ids=attachmentIds==null ? List.of() : List.copyOf(attachmentIds);
        validateImages(afterSaleId,ids);
        UUID id=transactions.intent(orderId,purchaseId,"COMPLAINT",key,null,afterSaleId,operator,normalizedTopic,normalizedContent,ids);
        SupplierAftercareOperation op=operations.findById(id).orElseThrow();
        if (!"INTENT".equals(op.getStatus())) return view(op);
        List<UUID> original=json.convertValue(op.getAttachmentIds(),new TypeReference<List<UUID>>(){});
        List<String> images=validateImages(op.getAfterSaleId(),original);
        SupplierPurchase p=purchases.findById(purchaseId).orElseThrow();
        String notifyUrl=urls.supplierComplaintWebhookUrl(p.getAccountId().toString());
        if (transactions.claim(id)) {
            try {
                var account=suppliers.runtime(p.getAccountId(),false);
                var request=new SupplierModels.ComplaintRequest(p.getUpstreamOrderNumber(),op.getTopic(),op.getContent(),notifyUrl,images);
                var result=account.provider().createComplaint(account.connection(),request);
                transactions.accepted(id,1,result.requestId(),null);
            } catch (RuntimeException e) { transactions.unknown(id,kind(e)); }
        }
        return view(operations.findById(id).orElseThrow());
    }
    private List<String> validateImages(UUID afterSaleId,List<UUID> ids) {
        if (afterSaleId==null || ids.size()>5 || new HashSet<>(ids).size()!=ids.size()) throw invalid("售后凭证无效，最多 5 张");
        List<String> result=new ArrayList<>(),types=new ArrayList<>(); long total=0;
        for (UUID id:ids) {
            AfterSaleAttachment file=attachments.findById(id).filter(a -> a.getRequestId().equals(afterSaleId))
                    .orElseThrow(() -> invalid("凭证不属于此售后申请"));
            if (!Set.of("image/jpeg","image/png","image/webp","image/gif","image/svg+xml").contains(file.getContentType())
                    || file.getFileSize()<=0 || file.getFileSize()>10L*1024*1024) throw invalid("售后凭证格式或大小无效");
            total+=file.getFileSize(); if (total>10L*1024*1024) throw invalid("售后凭证总大小不能超过 10 MB");
            try {
                var path=storage.resolveForRead(file.getStorageKey());
                if (Files.size(path)!=file.getFileSize()) throw invalid("售后凭证大小不一致");
                result.add(Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
                types.add(file.getContentType());
            } catch (java.io.IOException e) { throw invalid("售后凭证读取失败"); }
        }
        if (!complaintNotifications.validateImages(result).equals(types)) throw invalid("售后凭证内容与格式不一致");
        return List.copyOf(result);
    }
    private Map<String,Object> queryCardInternal(UUID orderId,UUID purchaseId,UUID cardId) {
        SupplierPurchase p=purchases.findById(purchaseId).filter(x -> x.getOrderId().equals(orderId))
                .orElseThrow(() -> invalid("采购记录不存在"));
        SupplierDeliveryCard card=cards.findById(cardId).filter(c -> c.getPurchaseId().equals(purchaseId)
                && c.getAccountId().equals(p.getAccountId())).orElseThrow(() -> invalid("卡券不存在"));
        LocalDateTime started=LocalDateTime.now();
        var account=suppliers.runtime(p.getAccountId(),false);
        var detail=account.provider().cardDetail(account.connection(),card.getRemoteCardId());
        transactions.cardChecked(orderId,purchaseId,cardId,detail,started);
        return cardView(cards.findById(cardId).orElseThrow());
    }
    public Map<String,Object> voidCard(UUID orderId,UUID purchaseId,UUID cardId,UUID key,boolean confirmed) {
        UUID operator=admin(); requireConfirmation(confirmed); outsideTransaction();
        queryCardInternal(orderId,purchaseId,cardId);
        UUID id=transactions.intent(orderId,purchaseId,"CARD_VOID",key,cardId,null,operator,null,null,List.of());
        if (transactions.claim(id)) {
            SupplierPurchase p=purchases.findById(purchaseId).orElseThrow();
            SupplierDeliveryCard card=cards.findById(cardId).orElseThrow();
            try {
                var account=suppliers.runtime(p.getAccountId(),false);
                var result=account.provider().voidCard(account.connection(),card.getRemoteCardId());
                transactions.accepted(id,result.status(),null,result.result());
            } catch (RuntimeException e) { transactions.unknown(id,kind(e)); }
            refreshInternal(id);
        }
        return view(operations.findById(id).orElseThrow());
    }
    public Map<String,Object> refresh(UUID orderId,UUID purchaseId,UUID operationId) {
        admin(); outsideTransaction();
        var op=operations.findById(operationId).filter(o -> o.getPurchaseId().equals(purchaseId))
                .orElseThrow(() -> invalid("操作记录不存在"));
        if (purchases.findById(purchaseId).filter(p -> p.getOrderId().equals(orderId)).isEmpty()) throw invalid("采购记录不存在");
        if (op.getKind().equals("COMPLAINT")) throw invalid("供应商没有申诉查询接口，请人工核实");
        refreshInternal(operationId);
        return view(operations.findById(operationId).orElseThrow());
    }
    public Map<String,Object> recordReview(UUID orderId,UUID purchaseId,UUID operationId,String outcome) {
        admin();
        if (outcome==null || !Set.of("CONTACTED_SUPPLIER","MANUAL_HANDLING","RESULT_UNCONFIRMED").contains(outcome))
            throw invalid("核实结果无效");
        var op=operations.findById(operationId).filter(o -> o.getPurchaseId().equals(purchaseId))
                .orElseThrow(() -> invalid("操作记录不存在"));
        if (purchases.findById(purchaseId).filter(p -> p.getOrderId().equals(orderId)).isEmpty()) throw invalid("采购记录不存在");
        if (!Set.of("INTENT","SUBMITTING","UNKNOWN","ACCEPTED").contains(op.getStatus())) throw invalid("当前操作无需人工核实");
        audit.logCurrentRequest("supplier.aftercare.review","SUPPLIER_AFTERCARE",op.getId().toString(),outcome);
        return view(op);
    }
    @Scheduled(fixedDelayString="${supplier.aftercare-recovery-interval-ms:30000}",initialDelayString="${supplier.purchase-initial-delay-ms:30000}")
    public void recover() {
        if (!scanning.compareAndSet(false,true)) return;
        try {
            for (UUID id:operations.staleComplaints(LocalDateTime.now().minusSeconds(30),PageRequest.of(0,20)))
                transactions.unknown(id,"UNCONFIRMED");
            var tasks=operations.recovery(PageRequest.of(0,20)).stream()
                    .map(id -> CompletableFuture.runAsync(() -> refreshInternal(id),recovery).exceptionally(ignored -> null))
                    .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(tasks).whenComplete((ok,error) -> scanning.set(false));
        } catch (RuntimeException ignored) { scanning.set(false); }
    }
    private void refreshInternal(UUID id) {
        outsideTransaction();
        SupplierAftercareOperation op=operations.findById(id).orElse(null);
        if (op==null || !Set.of("SUBMITTING","UNKNOWN","ACCEPTED").contains(op.getStatus())) return;
        // A concurrently running create request gets time to finish; reads never resubmit it.
        if ("SUBMITTING".equals(op.getStatus()) && op.getSubmittedAt()!=null
                && op.getSubmittedAt().isAfter(LocalDateTime.now().minusSeconds(30))) return;
        SupplierPurchase p=purchases.findById(op.getPurchaseId()).orElseThrow();
        try {
            if (op.getKind().equals("REFUND")) purchaseWorker.refresh(p.getId());
            else if (op.getKind().equals("CARD_VOID")) queryCardInternal(p.getOrderId(),p.getId(),op.getCardId());
            else return;
            transactions.reconcile(id);
        } catch (RuntimeException e) { transactions.queryFailed(id,kind(e)); }
    }
    public List<Map<String,Object>> operations(UUID purchaseId) {
        return operations.findByPurchaseIdOrderByCreatedAtAsc(purchaseId).stream().map(this::view).toList();
    }
    public List<Map<String,Object>> cards(UUID purchaseId) {
        return cards.findByPurchaseIdOrderByCreatedAtAsc(purchaseId).stream().map(this::cardView).toList();
    }
    private Map<String,Object> view(SupplierAftercareOperation op) {
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("id",op.getId()); m.put("kind",op.getKind()); m.put("status",op.getStatus());
        m.put("idempotency_key",op.getIdempotencyKey());
        m.put("card_id",op.getCardId()); m.put("after_sale_id",op.getAfterSaleId());
        m.put("upstream_status",op.getUpstreamStatus()); m.put("supplier_req_id",op.getSupplierReqId());
        m.put("result",op.getResult()); m.put("error_code",op.getErrorCode());
        m.put("topic",op.getTopic()); m.put("content",op.getContent());
        m.put("attachment_ids",op.getAttachmentIds());
        m.put("created_at",op.getCreatedAt()); m.put("last_checked_at",op.getLastCheckedAt());
        m.put("reviews",logs.findByTargetTypeAndTargetIdOrderByCreatedAtDesc("SUPPLIER_AFTERCARE",op.getId().toString(),PageRequest.of(0,20))
                .getContent().stream().filter(log -> "supplier.aftercare.review".equals(log.getAction()))
                .map(log -> Map.of("outcome",log.getDetail(),"operator",log.getUsername(),"created_at",log.getCreatedAt())).toList());
        return m;
    }
    private Map<String,Object> cardView(SupplierDeliveryCard card) {
        Map<String,Object> m=new LinkedHashMap<>(); m.put("id",card.getId()); m.put("remote_card_id",card.getRemoteCardId());
        m.put("status",card.getCardStatus()); m.put("cancel_request",card.getCancelRequest());
        m.put("result",card.getSupplierResult()); m.put("last_checked_at",card.getLastCheckedAt()); return m;
    }
    private UUID admin() {
        var user=RequestContext.get();
        if (user==null || user.getUserId()==null || user.getRole()==null || !Set.of("ADMIN","SUPER_ADMIN").contains(user.getRole()))
            throw new BusinessException(ErrorCode.FORBIDDEN,"仅管理员可操作供应商售后");
        return user.getUserId();
    }
    private void requireConfirmation(boolean confirmed) {
        if (!confirmed) throw invalid("必须明确确认供应商操作");
    }
    private void outsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("supplier request inside transaction");
    }
    private String kind(RuntimeException e) { return e instanceof SupplierException s ? s.kind() : "INTERNAL"; }
    private BusinessException invalid(String message) { return new BusinessException(ErrorCode.BAD_REQUEST,message); }
    @PreDestroy public void stop() { recovery.shutdownNow(); }
}
