package com.myshop.supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.constant.FulfillmentStatus;
import com.myshop.constant.OrderStatus;
import com.myshop.constant.StockMode;
import com.myshop.entity.*;
import com.myshop.repository.*;
import com.myshop.service.impl.SubsiteSettlementService;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Short database-only boundaries. No provider request may run inside these transactions. */
@Service
@RequiredArgsConstructor
public class SupplierPurchaseTransactions {
    private final SupplierPurchaseRepository purchases;
    private final SupplierDeliveryCardRepository cards;
    private final SupplierCallbackEventRepository events;
    private final SupplierAftercareOperationRepository aftercare;
    private final WebhookEventRepository paymentEvents;
    private final SupplierAccountRepository accounts;
    private final SupplierProductMappingRepository mappings;
    private final ProductRepository products;
    private final ProductSpecRepository specs;
    private final SupplierPurchasePolicy policy;
    private final SupplierService suppliers;
    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final ObjectMapper json;
    private SubsiteSettlementService subsiteSettlementService;
    @Autowired public void setSubsiteSettlementService(SubsiteSettlementService service) { this.subsiteSettlementService=service; }

    /** Joins checkout so the purchase intent commits atomically with the order item. */
    @Transactional(propagation=Propagation.MANDATORY)
    public SupplierPurchase createIntent(OrderItem item, SupplierProductMapping mapping,
                                         int actualQuantity, BigDecimal safePrice,
                                         List<SupplierModels.Attachment> attach,
                                         List<com.myshop.model.CustomerInputField> fields) {
        SupplierPurchase p=new SupplierPurchase();
        p.setOrderId(item.getOrderId()); p.setOrderItemId(item.getId());
        p.setAccountId(mapping.getAccountId()); p.setRemoteGoodsId(mapping.getRemoteGoodsId());
        p.setRemoteSku(mapping.getRemoteSku()); p.setGoodsType(mapping.getGoodsType());
        p.setOuterNumber("MS"+UUID.randomUUID().toString().replace("-",""));
        p.setBuyerQuantity(item.getQuantity()); p.setMultiple(mapping.getMultiple());
        p.setActualQuantity(actualQuantity); p.setUnitCost(mapping.getCost()); p.setSafePrice(safePrice);
        p.setAttach(json.valueToTree(attach));
        p.setRechargeTemplate(json.valueToTree(fields));
        p.setStatus("WAITING_PAYMENT");
        return purchases.saveAndFlush(p);
    }

    /** Only the winner can send a create request, and only after this method commits. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=5)
    public boolean claim(UUID id) {
        UUID accountId=purchases.findAccountId(id).orElseThrow();
        UUID orderId=purchases.findOrderId(id).orElseThrow();
        UUID itemId=purchases.findOrderItemId(id).orElseThrow();
        var account=accounts.findForUpdate(accountId).orElseThrow();
        if (!account.isEnabled()) return false;
        OrderItem item=items.findById(itemId).orElseThrow();
        Product product=products.findByIdForUpdate(item.getProductId()).orElseThrow();
        var order=orders.findByIdForUpdate(orderId).orElseThrow();
        if (order.getStatus()!=OrderStatus.PAID) return false;
        SupplierPurchase p=purchases.findForUpdate(id).orElseThrow();
        if (!"WAITING_PAYMENT".equals(p.getStatus())) return false;
        if (paymentEvents.existsByOrderIdAndProcessResultStartingWith(orderId,"LATE_PAID_")) {
            p.setStatus("BLOCKED"); p.setErrorCode("LATE_PAYMENT"); p.setLastCheckedAt(LocalDateTime.now());
            return false;
        }
        String problem=null;
        if (!product.isEnabled() || product.getIsDeleted()!=0 || product.getStockMode()!=StockMode.SUPPLIER) problem="PRODUCT_DISABLED";
        else if (item.getSpecId()!=null && specs.findById(item.getSpecId())
                .filter(s -> s.getIsDeleted()==0 && s.isVisible() && s.getProductId().equals(product.getId())).isEmpty()) problem="SKU_DISABLED";
        else {
            try {
                SupplierProductMapping m=mappings.findLocal(item.getProductId(),item.getSpecId()).orElseThrow();
                if (!m.getAccountId().equals(p.getAccountId()) || !m.getRemoteGoodsId().equals(p.getRemoteGoodsId())
                        || !m.getRemoteSku().equals(p.getRemoteSku()) || m.getGoodsType()!=p.getGoodsType()
                        || m.getMultiple()!=p.getMultiple()) problem="MAPPING_CHANGED";
                else {
                    BigDecimal revenue = item.getSubsiteSupplyPriceSnapshot() == null ? item.getSubtotal()
                            : item.getSubsiteSupplyPriceSnapshot().multiply(BigDecimal.valueOf(item.getQuantity()));
                    var quote=policy.quote(m,true,p.getBuyerQuantity(),revenue,LocalDateTime.now());
                    if (order.getSubsiteId()!=null && quote.totalCost().signum()==0) problem="COST_EXCEEDED";
                    else if (quote.actualQuantity()!=p.getActualQuantity() || quote.totalCost().compareTo(p.getSafePrice())>0) problem="COST_EXCEEDED";
                    else if (!json.valueToTree(suppliers.customerFields(accountId,m.getSnapshot())).equals(p.getRechargeTemplate())) problem="TEMPLATE_CHANGED";
                }
            } catch (SupplierException e) { problem=safeError(e.kind()); }
            catch (RuntimeException e) { problem="MAPPING_CHANGED"; }
        }
        if (problem!=null) { p.setStatus("BLOCKED"); p.setErrorCode(problem); p.setLastCheckedAt(LocalDateTime.now()); return false; }
        return purchases.claim(id,LocalDateTime.now())==1;
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void blockBeforeClaim(UUID id,String error) {
        SupplierPurchase p=purchases.findForUpdate(id).orElseThrow();
        if ("WAITING_PAYMENT".equals(p.getStatus())) {
            p.setStatus("BLOCKED"); p.setErrorCode(safeError(error)); p.setLastCheckedAt(LocalDateTime.now());
        }
    }

    /** A previously issued payment link may still settle after sales are disabled. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=5)
    public void blockPaidWhileDisabled(UUID id) {
        SupplierPurchase p=lockedWithOrder(id);
        Order order=orders.findById(p.getOrderId()).orElseThrow();
        if ("WAITING_PAYMENT".equals(p.getStatus()) && (order.getStatus()==OrderStatus.PAID
                || order.getStatus()==OrderStatus.AFTER_SALE)) {
            p.setStatus("BLOCKED"); p.setErrorCode("PURCHASE_DISABLED"); p.setLastCheckedAt(LocalDateTime.now());
        }
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void submissionUnknown(UUID id,String error) {
        SupplierPurchase p=purchases.findForUpdate(id).orElseThrow();
        if ("SUBMITTING".equals(p.getStatus())) p.setStatus("UNKNOWN");
        if ("UNKNOWN".equals(p.getStatus())) p.setErrorCode(safeError(error));
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=5)
    public void recordCreated(UUID id,SupplierModels.CreatedPurchase created) {
        SupplierPurchase p=lockedWithOrder(id);
        if (p.getUpstreamOrderNumber()!=null && !p.getUpstreamOrderNumber().equals(created.orderNumber())) throw invalid();
        p.setUpstreamOrderNumber(created.orderNumber());
        saveCards(p,created.cards(),false,null);
        if ("SUBMITTING".equals(p.getStatus()) || "UNKNOWN".equals(p.getStatus())) {
            p.setStatus("PROCESSING"); p.setErrorCode(null);
        }
    }

    /** A signed event is durable before the provider is acknowledged. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=5)
    public UUID acceptCallback(UUID accountId,String outer,String orderNumber,int upstreamStatus,
                               String eventHash,String encrypted,List<SupplierModels.Card> received) {
        UUID id=purchases.findIdByAccountAndOuter(accountId,outer).orElseThrow(this::invalid);
        SupplierPurchase p=lockedWithOrder(id);
        if (p.getSubmitStartedAt()==null || p.getUpstreamOrderNumber()!=null
                && !p.getUpstreamOrderNumber().equals(orderNumber)) throw invalid();
        int inserted=events.insertOnce(UUID.randomUUID(),accountId,p.getId(),eventHash,upstreamStatus,encrypted);
        if (inserted==1) {
            p.setUpstreamOrderNumber(orderNumber);
            // A signed completion may contain cards that a later detail omits.
            // It cannot itself advance fulfillment; a detail query confirms status.
            if (upstreamStatus==3 && (p.getUpstreamStatus()==null || !Set.of(4,5,8,9).contains(p.getUpstreamStatus()))) {
                saveCards(p,received,false,null);
            }
        }
        return p.getId();
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void queryFailed(UUID id,String error,LocalDateTime started) {
        SupplierPurchase p=purchases.findForUpdate(id).orElseThrow();
        if (p.getLastCheckedAt()!=null && p.getLastCheckedAt().isAfter(started)) return;
        p.setLastCheckedAt(started);
        if ("SUBMITTING".equals(p.getStatus()) || "PROCESSING".equals(p.getStatus())) p.setStatus("UNKNOWN");
        p.setErrorCode(safeError(error));
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=5)
    public void applyDetail(UUID id,SupplierModels.PurchaseDetail detail,LocalDateTime started) {
        SupplierPurchase p=lockedWithOrder(id);
        if (p.getLastCheckedAt()!=null && p.getLastCheckedAt().isAfter(started)) return;
        if (!p.getOuterNumber().equals(detail.outerNumber()) || detail.count()!=p.getBuyerQuantity()
                || detail.money()==null || detail.refunded()==null || detail.money().signum()<0
                || detail.refunded().signum()<0 || detail.refunded().compareTo(detail.money())>0
                || (p.getUpstreamOrderNumber()!=null && !p.getUpstreamOrderNumber().equals(detail.orderNumber()))) throw invalid();
        if (!allowedTransition(p.getUpstreamStatus(),detail.status())) return;
        p.setUpstreamOrderNumber(detail.orderNumber()); p.setUpstreamStatus(detail.status());
        p.setActualCost(detail.money()); p.setRefundedAmount(detail.refunded());
        p.setResult(detail.result()==null ? null : detail.result().substring(0,Math.min(20_000,detail.result().length())));
        p.setStartCount(detail.startCount()); p.setNowCount(detail.nowCount()); p.setEndCount(detail.endCount());
        p.setLastCheckedAt(started); p.setErrorCode(null);
        saveCards(p,detail.cards(),true,started);
        switch (detail.status()) {
            case 0 -> p.setStatus("UNPAID");
            case 1,2,7 -> p.setStatus("PROCESSING");
            case 4 -> p.setStatus("WITHDRAWN");
            case 5 -> p.setStatus("REFUNDED");
            case 8,9 -> p.setStatus("REFUNDING");
            case 3 -> {
                if (aftercare.refundUnresolved(p.getId())) {
                    p.setStatus("PROCESSING"); p.setErrorCode("REFUND_PENDING"); return;
                }
                // A void may already have reached the supplier even if the
                // last card query still says it is usable. Both this method
                // and creation of the void intent hold the order lock.
                if (aftercare.cardVoidUnresolved(p.getId())) {
                    p.setStatus("PROCESSING"); p.setErrorCode("CARD_VOID_PENDING"); return;
                }
                if (detail.money().compareTo(p.getSafePrice())>0) {
                    p.setStatus("BLOCKED"); p.setErrorCode("COST_EXCEEDED"); return;
                }
                List<SupplierDeliveryCard> deliveredCards=cards.findByPurchaseIdOrderByCreatedAtAsc(p.getId());
                boolean deliverable = p.getGoodsType()==3 || (deliveredCards.size()==p.getActualQuantity()
                        && deliveredCards.stream().allMatch(c -> c.getCardStatus()==0 || c.getCardStatus()==1));
                if (!deliverable) { p.setStatus("PROCESSING"); p.setErrorCode("DELIVERY_INCOMPLETE"); return; }
                OrderItem item=items.findById(p.getOrderItemId()).orElseThrow();
                Order order=orders.findById(p.getOrderId()).orElseThrow();
                if (order.getStatus()==OrderStatus.AFTER_SALE && item.getFulfillmentStatus()!=FulfillmentStatus.DELIVERED) {
                    p.setStatus("PROCESSING"); p.setErrorCode("AFTER_SALE"); return;
                }
                if (order.getStatus()!=OrderStatus.PAID && order.getStatus()!=OrderStatus.DELIVERED
                        && order.getStatus()!=OrderStatus.AFTER_SALE) {
                    p.setStatus("BLOCKED"); p.setErrorCode("ORDER_STATE"); return;
                }
                LocalDateTime now=LocalDateTime.now();
                if (item.getFulfillmentStatus()!=FulfillmentStatus.DELIVERED) {
                    item.setFulfillmentStatus(FulfillmentStatus.DELIVERED); item.setDeliveredAt(now);
                    if (p.getGoodsType()==3) item.setCustomerDeliveryContent(p.getResult());
                    items.save(item);
                }
                p.setStatus("COMPLETED");
                List<OrderItem> orderItems=items.findByOrderId(order.getId());
                if (order.getStatus()==OrderStatus.PAID && !orderItems.isEmpty() && orderItems.stream()
                        .allMatch(i -> i.getFulfillmentStatus()==FulfillmentStatus.DELIVERED)) {
                    if (order.getSubsiteId()!=null) {
                        if (subsiteSettlementService==null) throw new IllegalStateException("分站结算服务不可用");
                        subsiteSettlementService.settle(order);
                    }
                    order.setStatus(OrderStatus.DELIVERED); order.setDeliveredAt(now); orders.save(order);
                }
            }
            default -> throw invalid();
        }
        for (SupplierCallbackEvent event:events.findByPurchaseIdAndProcessedFalse(id)) event.setProcessed(true);
    }

    private SupplierPurchase lockedWithOrder(UUID id) {
        UUID orderId=purchases.findOrderId(id).orElseThrow();
        orders.findByIdForUpdate(orderId).orElseThrow();
        return purchases.findForUpdate(id).orElseThrow();
    }

    private void saveCards(SupplierPurchase p,List<SupplierModels.Card> received,boolean updateExisting,LocalDateTime checkedAt) {
        for (var remote:received) {
            SupplierDeliveryCard c=cards.findByAccountIdAndRemoteCardId(p.getAccountId(),remote.id()).orElse(null);
            boolean isNew=c==null;
            if (c==null) {
                c=new SupplierDeliveryCard(); c.setAccountId(p.getAccountId()); c.setPurchaseId(p.getId());
                c.setRemoteCardId(remote.id()); c.setCardNo(remote.number()); c.setCardPwd(remote.password());
            } else if (!c.getPurchaseId().equals(p.getId()) || !sameContent(c.getCardNo(),remote.number())
                    || !sameContent(c.getCardPwd(),remote.password())) throw invalid();
            if (isNew || (updateExisting && (c.getLastCheckedAt()==null || !c.getLastCheckedAt().isAfter(checkedAt))
                    && (c.getCardStatus()!=2 || remote.status()==2)
                    && (c.getCardStatus()!=3 || remote.status()==3))) {
                c.setCardStatus(remote.status()); c.setCancelRequest(remote.cancelRequest());
                if (checkedAt!=null) c.setLastCheckedAt(checkedAt);
            }
            cards.save(c);
        }
        cards.flush();
    }
    private boolean sameContent(String left,String right) {
        return Objects.equals(left==null ? "" : left,right==null ? "" : right);
    }
    private boolean allowedTransition(Integer previous,int next) {
        if (previous==null || previous==next) return true;
        return switch (previous) {
            case 0 -> Set.of(1,2,3,4,7,8,9,5).contains(next);
            case 1 -> Set.of(2,3,4,5,8,9).contains(next);
            case 2 -> Set.of(3,4,5,8,9).contains(next);
            case 7 -> Set.of(1,2,3,4,5,8,9).contains(next);
            case 3 -> Set.of(5,8,9).contains(next);
            case 8 -> Set.of(2,3,4,5,9).contains(next);
            case 9 -> Set.of(3,5).contains(next);
            default -> false;
        };
    }
    private String safeError(String raw) {
        return raw!=null && raw.matches("[A-Z_]{1,64}") ? raw : "INTERNAL";
    }
    private SupplierException invalid() { return new SupplierException("DATA","供应商订单数据不一致，需人工核实"); }
}
