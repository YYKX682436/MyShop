package com.myshop.supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.constant.ErrorCode;
import com.myshop.constant.OrderStatus;
import com.myshop.entity.*;
import com.myshop.exception.BusinessException;
import com.myshop.repository.*;
import com.myshop.service.OperationLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

/** Database-only boundaries. A committed SUBMITTING row permits at most one provider write. */
@Service @RequiredArgsConstructor
public class SupplierAftercareTransactions {
    private final SupplierAftercareOperationRepository operations;
    private final SupplierPurchaseRepository purchases;
    private final SupplierDeliveryCardRepository cards;
    private final OrderRepository orders;
    private final AfterSaleRequestRepository afterSales;
    private final OperationLogService audit;
    private final ObjectMapper json;

    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=5)
    public UUID intent(UUID orderId,UUID purchaseId,String kind,UUID key,UUID cardId,UUID afterSaleId,
                       UUID operator,String topic,String content,List<UUID> attachments) {
        SupplierPurchase p=lockPurchase(orderId,purchaseId);
        if (key==null || operator==null) throw invalid("操作标识无效");
        var same=operations.findByAccountIdAndIdempotencyKey(p.getAccountId(),key).orElse(null);
        if (same!=null) {
            if (!same.getPurchaseId().equals(purchaseId) || !same.getKind().equals(kind)
                    || !Objects.equals(same.getCardId(),cardId)) throw invalid("操作标识已用于其他请求");
            return same.getId();
        }
        if (p.getSubmitStartedAt()==null || p.getUpstreamStatus()==null || p.getUpstreamOrderNumber()==null)
            throw invalid("上游采购结果尚未确认，请先查询或人工核实");
        Order order=orders.findById(orderId).orElseThrow();
        if (!Set.of(OrderStatus.PAID,OrderStatus.DELIVERED,OrderStatus.AFTER_SALE).contains(order.getStatus()))
            throw invalid("订单状态不允许供应商售后操作");
        var prior=operations.findByPurchaseIdOrderByCreatedAtAsc(purchaseId);
        if (kind.equals("REFUND")) {
            var existing=operations.findByPurchaseIdAndKind(purchaseId,kind);
            if (existing.isPresent()) return existing.get().getId();
            if (!Set.of(0,1,2,3,7).contains(p.getUpstreamStatus()) || "UNKNOWN".equals(p.getStatus()))
                throw invalid("上游订单状态不允许申请退款");
            if (prior.stream().anyMatch(o -> o.getKind().equals("CARD_VOID") && unresolved(o)))
                throw invalid("卡券作废处理中，请先核实结果");
        } else if (kind.equals("CARD_VOID")) {
            if (cardId==null) throw invalid("卡券不存在");
            SupplierDeliveryCard card=cards.findForUpdate(cardId).orElseThrow(() -> invalid("卡券不存在"));
            if (!card.getPurchaseId().equals(purchaseId) || !card.getAccountId().equals(p.getAccountId()))
                throw invalid("卡券与采购记录不匹配");
            var existing=operations.findByCardIdAndKind(cardId,kind);
            if (existing.isPresent()) return existing.get().getId();
            if (card.getLastCheckedAt()==null || card.getLastCheckedAt().isBefore(LocalDateTime.now().minusSeconds(30))
                    || card.getCancelRequest()!=1 || (card.getCardStatus()!=0 && card.getCardStatus()!=1))
                throw invalid("卡券当前状态不允许作废，请先查询");
            if (prior.stream().anyMatch(o -> o.getKind().equals("REFUND") && unresolved(o)))
                throw invalid("上游退款处理中，请先核实结果");
        } else if (kind.equals("COMPLAINT")) {
            if (afterSaleId==null || afterSales.findById(afterSaleId).filter(a -> a.getOrderId().equals(orderId)).isEmpty())
                throw invalid("请关联本订单的售后申请");
            if (prior.stream().anyMatch(o -> o.getKind().equals("COMPLAINT")
                    && Set.of("INTENT","SUBMITTING","UNKNOWN").contains(o.getStatus())))
                throw invalid("已有申诉请求尚未确认，请人工核实");
            if (prior.stream().anyMatch(o -> o.getKind().equals("COMPLAINT") && o.getUpstreamStatus()!=null
                    && Set.of(3,4).contains(o.getUpstreamStatus()))) throw invalid("供应商申诉已结束，不能继续追加");
            boolean first=prior.stream().noneMatch(o -> o.getKind().equals("COMPLAINT") && !"REJECTED".equals(o.getStatus()));
            if (first && (topic==null || topic.isBlank())) throw invalid("首次申诉需要主题");
        } else throw invalid("不支持的供应商操作");
        SupplierAftercareOperation op=new SupplierAftercareOperation();
        op.setAccountId(p.getAccountId()); op.setPurchaseId(purchaseId); op.setKind(kind);
        op.setIdempotencyKey(key); op.setCardId(cardId); op.setAfterSaleId(afterSaleId); op.setOperatorId(operator);
        op.setStatus("INTENT"); op.setTopic(topic); op.setContent(content); op.setAttachmentIds(json.valueToTree(attachments==null?List.of():attachments));
        operations.saveAndFlush(op);
        audit.logCurrentRequest("supplier.aftercare.intent","SUPPLIER_PURCHASE",purchaseId.toString(),kind+" 操作ID="+op.getId());
        return op.getId();
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=5)
    public boolean claim(UUID id) {
        UUID purchaseId=operations.findPurchaseId(id).orElseThrow();
        UUID orderId=purchases.findOrderId(purchaseId).orElseThrow();
        SupplierPurchase p=lockPurchase(orderId,purchaseId);
        SupplierAftercareOperation op=operations.findForUpdate(id).orElseThrow();
        if (!"INTENT".equals(op.getStatus())) return false;
        Order order=orders.findById(p.getOrderId()).orElseThrow();
        if (!Set.of(OrderStatus.PAID,OrderStatus.DELIVERED,OrderStatus.AFTER_SALE).contains(order.getStatus())) {
            op.setStatus("REJECTED"); op.setErrorCode("ORDER_STATE"); return false;
        }
        if ("REFUND".equals(op.getKind()) && (p.getUpstreamStatus()==null
                || !Set.of(0,1,2,3,7).contains(p.getUpstreamStatus()) || "UNKNOWN".equals(p.getStatus()))) {
            op.setStatus("REJECTED"); op.setErrorCode("PURCHASE_STATE"); return false;
        }
        if ("COMPLAINT".equals(op.getKind()) && operations.findByPurchaseIdOrderByCreatedAtAsc(p.getId()).stream()
                .anyMatch(o -> !o.getId().equals(id) && o.getKind().equals("COMPLAINT")
                        && o.getUpstreamStatus()!=null && Set.of(3,4).contains(o.getUpstreamStatus()))) {
            op.setStatus("REJECTED"); op.setErrorCode("COMPLAINT_ENDED"); return false;
        }
        if ("CARD_VOID".equals(op.getKind())) {
            SupplierDeliveryCard c=cards.findForUpdate(op.getCardId()).orElseThrow();
            if (c.getLastCheckedAt()==null || c.getLastCheckedAt().isBefore(LocalDateTime.now().minusSeconds(30))
                    || c.getCancelRequest()!=1 || (c.getCardStatus()!=0 && c.getCardStatus()!=1)) {
                op.setStatus("REJECTED"); op.setErrorCode("CARD_STATE"); return false;
            }
        }
        op.setStatus("SUBMITTING"); op.setSubmittedAt(LocalDateTime.now()); op.setErrorCode(null);
        return true;
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=5)
    public void unknown(UUID id,String kind) {
        SupplierAftercareOperation op=operations.findForUpdate(id).orElseThrow();
        if (!"SUBMITTING".equals(op.getStatus())) return;
        op.setStatus("BUSINESS".equals(kind) ? "REJECTED" : "UNKNOWN");
        op.setErrorCode(kind!=null && kind.matches("[A-Z_]{1,64}") ? kind : "INTERNAL");
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=5)
    public void queryFailed(UUID id,String kind) {
        SupplierAftercareOperation op=operations.findForUpdate(id).orElseThrow();
        if (!Set.of("SUBMITTING","UNKNOWN","ACCEPTED").contains(op.getStatus())) return;
        if ("SUBMITTING".equals(op.getStatus())) op.setStatus("UNKNOWN");
        op.setLastCheckedAt(LocalDateTime.now());
        op.setErrorCode(kind!=null && kind.matches("[A-Z_]{1,64}") ? kind : "INTERNAL");
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=5)
    public void accepted(UUID id,Integer status,Long requestId,String result) {
        SupplierAftercareOperation op=operations.findForUpdate(id).orElseThrow();
        if (!"SUBMITTING".equals(op.getStatus()) && !"UNKNOWN".equals(op.getStatus())) return;
        op.setStatus("ACCEPTED"); op.setUpstreamStatus(status); op.setSupplierReqId(requestId);
        op.setResult(limit(result)); op.setErrorCode(null);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=5)
    public void cardChecked(UUID orderId,UUID purchaseId,UUID cardId,SupplierModels.CardDetail detail,LocalDateTime started) {
        SupplierPurchase p=lockPurchase(orderId,purchaseId);
        SupplierDeliveryCard card=cards.findForUpdate(cardId).orElseThrow(() -> invalid("卡券不存在"));
        if (!card.getPurchaseId().equals(purchaseId) || !card.getAccountId().equals(p.getAccountId())
                || !card.getRemoteCardId().equals(detail.id()) || detail.status()<0 || detail.status()>4
                || (detail.cancelRequest()!=0 && detail.cancelRequest()!=1)) throw invalid("卡券与采购记录不匹配");
        if (card.getLastCheckedAt()!=null && card.getLastCheckedAt().isAfter(started)) return;
        if ((card.getCardStatus()==2 && detail.status()!=2) || (card.getCardStatus()==3 && detail.status()!=3)) return;
        card.setCardStatus(detail.status()); card.setCancelRequest(detail.cancelRequest());
        card.setSupplierResult(limit(detail.result())); card.setLastCheckedAt(started);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=5)
    public void reconcile(UUID id) {
        SupplierAftercareOperation op=operations.findForUpdate(id).orElseThrow();
        if (!Set.of("SUBMITTING","UNKNOWN","ACCEPTED").contains(op.getStatus())) return;
        op.setLastCheckedAt(LocalDateTime.now());
        if (op.getKind().equals("REFUND")) {
            SupplierPurchase p=purchases.findById(op.getPurchaseId()).orElseThrow();
            if (p.getLastCheckedAt()==null || op.getSubmittedAt()==null || p.getLastCheckedAt().isBefore(op.getSubmittedAt())) return;
            if (Objects.equals(p.getUpstreamStatus(),4) || Objects.equals(p.getUpstreamStatus(),5)) {
                op.setStatus("COMPLETED"); op.setUpstreamStatus(p.getUpstreamStatus()); op.setErrorCode(null);
            } else if (Objects.equals(p.getUpstreamStatus(),8) || Objects.equals(p.getUpstreamStatus(),9)) {
                op.setStatus("ACCEPTED"); op.setUpstreamStatus(p.getUpstreamStatus()); op.setErrorCode(null);
            } else if ("SUBMITTING".equals(op.getStatus())) {
                op.setStatus("UNKNOWN"); op.setErrorCode("UNCONFIRMED");
            }
        } else if (op.getKind().equals("CARD_VOID")) {
            SupplierDeliveryCard c=cards.findById(op.getCardId()).orElseThrow();
            if (c.getLastCheckedAt()==null || op.getSubmittedAt()==null || c.getLastCheckedAt().isBefore(op.getSubmittedAt())) return;
            op.setUpstreamStatus(c.getCardStatus());
            if (c.getCardStatus()==2) { op.setStatus("COMPLETED"); op.setErrorCode(null); }
            else if (c.getCardStatus()==4) { op.setStatus("ACCEPTED"); op.setErrorCode(null); }
            else if ("SUBMITTING".equals(op.getStatus())) { op.setStatus("UNKNOWN"); op.setErrorCode("UNCONFIRMED"); }
        }
    }
    private SupplierPurchase lockPurchase(UUID orderId,UUID purchaseId) {
        orders.findByIdForUpdate(orderId).orElseThrow(() -> invalid("订单不存在"));
        return purchases.findForUpdate(purchaseId).filter(p -> p.getOrderId().equals(orderId))
                .orElseThrow(() -> invalid("采购记录与订单不匹配"));
    }
    private boolean unresolved(SupplierAftercareOperation o) { return Set.of("INTENT","SUBMITTING","UNKNOWN","ACCEPTED").contains(o.getStatus()); }
    private String limit(String value) { return value==null ? null : value.substring(0,Math.min(20_000,value.length())); }
    private BusinessException invalid(String message) { return new BusinessException(ErrorCode.BAD_REQUEST,message); }
}
