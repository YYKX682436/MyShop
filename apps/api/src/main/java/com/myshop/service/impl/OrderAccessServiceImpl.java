package com.myshop.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.constant.ErrorCode;
import com.myshop.constant.FulfillmentStatus;
import com.myshop.constant.OrderPaymentMethod;
import com.myshop.constant.OrderStatus;
import com.myshop.constant.StockMode;
import com.myshop.entity.CardKey;
import com.myshop.entity.Order;
import com.myshop.entity.OrderItem;
import com.myshop.entity.PaymentAttempt;
import com.myshop.exception.BusinessException;
import com.myshop.exception.OrderExpiredException;
import com.myshop.payment.PaymentNextAction;
import com.myshop.payment.PaymentNextActionPayload;
import com.myshop.payment.PaymentStatus;
import com.myshop.payment.PaymentCreationStatus;
import com.myshop.repository.CardKeyRepository;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.PaymentAttemptRepository;
import com.myshop.repository.SupplierPurchaseRepository;
import com.myshop.repository.SupplierDeliveryCardRepository;
import com.myshop.entity.SupplierDeliveryCard;
import com.myshop.service.BalanceOrderPaymentService;
import com.myshop.service.AfterSaleService;
import com.myshop.service.CardKeyReclaimService;
import com.myshop.service.OrderAccessService;
import com.myshop.service.OrderService;
import com.myshop.service.PaymentService;
import com.myshop.supplier.SupplierOrderAvailability;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The single authorization boundary for customer order reads and mutations.
 * Authentication is checked before any item, payment, or card-key lookup.
 */
@Service
public class OrderAccessServiceImpl implements OrderAccessService {
    @Autowired private com.myshop.service.PublicImageResolver publicImageResolver;

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CardKeyRepository cardKeyRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final OrderService orderService;
    private final PaymentService paymentService;
    private final BalanceOrderPaymentService balanceOrderPaymentService;
    private final CardKeyReclaimService cardKeyReclaimService;
    private final AfterSaleService afterSaleService;
    private final ObjectMapper objectMapper;
    private final SupplierOrderAvailability supplierOrderAvailability;
    private final TransactionTemplate transactions;
    private SupplierPurchaseRepository supplierPurchases;
    private SupplierDeliveryCardRepository supplierCards;
    private com.myshop.repository.WebhookEventRepository paymentEvents;
    @Autowired public void setPaymentEvents(com.myshop.repository.WebhookEventRepository events) { this.paymentEvents=events; }
    @Autowired public void setSupplierRepositories(SupplierPurchaseRepository purchases,SupplierDeliveryCardRepository cards) {
        this.supplierPurchases=purchases; this.supplierCards=cards;
    }

    public OrderAccessServiceImpl(OrderRepository orderRepository,
                                  OrderItemRepository orderItemRepository,
                                  CardKeyRepository cardKeyRepository,
                                  PaymentAttemptRepository paymentAttemptRepository,
                                  OrderService orderService,
                                  PaymentService paymentService,
                                  BalanceOrderPaymentService balanceOrderPaymentService,
                                  CardKeyReclaimService cardKeyReclaimService,
                                  AfterSaleService afterSaleService,
                                  ObjectMapper objectMapper,
                                  PlatformTransactionManager transactionManager,
                                  SupplierOrderAvailability supplierOrderAvailability) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.cardKeyRepository = cardKeyRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.orderService = orderService;
        this.paymentService = paymentService;
        this.balanceOrderPaymentService = balanceOrderPaymentService;
        this.cardKeyReclaimService = cardKeyReclaimService;
        this.afterSaleService = afterSaleService;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.supplierOrderAvailability = supplierOrderAvailability;
    }

    @Override
    @Transactional
    public Map<String, Object> getOrder(UUID orderId, UUID requestUserId, String sessionToken) {
        Order order = authorizedOrder(orderId, requestUserId, sessionToken);
        orderService.expireIfNeeded(order);
        return view(order);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Map<String, Object> refreshOrder(UUID orderId, UUID requestUserId, String sessionToken) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));
        authorize(order, requestUserId, sessionToken);
        paymentService.refreshPayment(orderId);
        return transactions.execute(status -> getOrder(orderId, requestUserId, sessionToken));
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Map<String, Object> repayOrder(UUID orderId, String device,
                                          UUID requestUserId, String sessionToken) {
        java.util.concurrent.atomic.AtomicReference<OrderExpiredException> expired = new java.util.concurrent.atomic.AtomicReference<>();
        Map<String, Object> intent = transactions.execute(status -> {
            try {
                authorizedOrder(orderId, requestUserId, sessionToken);
                return paymentService.prepareRepayment(orderId, device);
            } catch (OrderExpiredException exception) { expired.set(exception); return null; }
        });
        if (expired.get() != null) throw expired.get();
        try { return paymentService.dispatchPayment((UUID) intent.get("payment_id")); }
        catch (RuntimeException exception) {
            Map<String, Object> result = new LinkedHashMap<>(intent);
            result.put("creation_status", "UNKNOWN");
            result.put("next_action", null);
            result.put("can_retry", false);
            return result;
        }
    }

    @Override
    @Transactional
    public String exportCardKeys(UUID orderId, UUID requestUserId, String sessionToken) {
        Order order = authorizedOrder(orderId, requestUserId, sessionToken);
        List<CardKey> keys = cardKeyRepository.findByOrderIdOrderByCreatedAtAsc(order.getId());
        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        Map<UUID, List<CardKey>> grouped = keys.stream()
                .filter(key -> key.getOrderItemId() != null)
                .collect(Collectors.groupingBy(CardKey::getOrderItemId,
                        LinkedHashMap::new, Collectors.toList()));
        StringBuilder result = new StringBuilder("MyShop 卡密\n");
        boolean found=!keys.isEmpty();
        for (OrderItem item : items) {
            List<CardKey> itemKeys = grouped.getOrDefault(item.getId(), List.of());
            List<SupplierDeliveryCard> external=supplierCards(item);
            if (itemKeys.isEmpty() && external.isEmpty()) continue;
            found=true;
            result.append("\n").append(item.getProductTitle());
            if (item.getSpecName() != null && !item.getSpecName().isBlank()) {
                result.append(" [").append(item.getSpecName()).append("]");
            }
            result.append("\n");
            if (!itemKeys.isEmpty()) result.append(itemKeys.stream()
                    .map(key -> String.join("\n", key.getContentParts()))
                    .collect(Collectors.joining("\n\n"))).append("\n");
            if (!external.isEmpty() && !itemKeys.isEmpty()) result.append("\n");
            if (!external.isEmpty()) result.append(external.stream().map(card -> cardStatusPrefix(card)+String.join("\n",parts(card)))
                    .collect(Collectors.joining("\n\n"))).append("\n");
        }
        if (!found) throw new BusinessException(ErrorCode.ORDER_NOT_PAID, "订单尚未产生卡密");
        return result.toString();
    }

    private Order authorizedOrder(UUID orderId, UUID requestUserId, String sessionToken) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));
        return authorize(order, requestUserId, sessionToken);
    }

    private Order authorize(Order order, UUID requestUserId, String sessionToken) {
        com.myshop.service.OrderOwnership.requireAccess(order, requestUserId, sessionToken);
        return order;
    }

    private Map<String, Object> view(Order order) {
        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> orderResult = orderDetail(order, items);
        Map<String, Object> afterSale = afterSaleService.buildOrderView(order.getId());
        if (afterSale == null) {
            afterSale = emptyAfterSaleView();
        }
        orderResult.putAll(afterSale);
        result.put("order", orderResult);
        result.put("payment", paymentView(order, items));
        result.put("delivery", deliveryView(order, items));
        // Keep the same top-level fields used by the admin order detail while
        // the nested order object remains self-contained for storefront code.
        result.putAll(afterSale);
        return result;
    }

    private Map<String, Object> emptyAfterSaleView() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("after_sale", null);
        result.put("after_sale_history", List.of());
        return result;
    }

    private Map<String, Object> orderDetail(Order order, List<OrderItem> items) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", order.getId());
        result.put("order_no", order.getOrderNo());
        result.put("guest", order.getUserId() == null);
        result.put("total_amount", order.getTotalAmount());
        result.put("actual_amount", order.getActualAmount());
        result.put("status", order.getStatus().name());
        result.put("order_type", order.getOrderType());
        result.put("payment_method", order.getPaymentMethod() == null ? null : order.getPaymentMethod().name());
        result.put("payment_channel_code", order.getPaymentChannelCode());
        result.put("created_at", order.getCreatedAt());
        result.put("expires_at", order.getExpiresAt());
        result.put("paid_at", order.getPaidAt());
        result.put("delivered_at", order.getDeliveredAt());
        result.put("refunded_at", order.getRefundedAt());
        if (order.getStatus() == OrderStatus.REFUNDED) {
            result.put("reclaimed_card_key_count", cardKeyReclaimService.countByOrderId(order.getId()));
        }
        result.put("items", items.stream().map(this::itemDetail).toList());
        return result;
    }

    private Map<String, Object> itemDetail(OrderItem item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", item.getId());
        result.put("product_id", item.getProductId());
        result.put("product_no", item.getProductNo());
        result.put("product_title", item.getProductTitle());
        result.put("product_cover_url", publicImageResolver == null ? item.getProductCoverUrl() : publicImageResolver.visible(item.getProductCoverUrl()));
        result.put("spec_name", item.getSpecName());
        result.put("quantity", item.getQuantity());
        result.put("unit_price", item.getUnitPrice());
        result.put("subtotal", item.getSubtotal());
        result.put("delivery_type", item.getDeliveryType());
        result.put("customer_inputs", item.getCustomerInputs() == null ? List.of() : item.getCustomerInputs());
        result.put("stock_mode", item.getStockMode());
        result.put("fulfillment_status", item.getFulfillmentStatus());
        result.put("delivered_at", item.getDeliveredAt());
        if (item.getStockMode()==StockMode.SUPPLIER && supplierPurchases!=null) {
            supplierPurchases.findByOrderItemId(item.getId()).ifPresent(p -> {
                result.put("supplier_status",p.getStatus());
                result.put("supplier_result",p.getResult());
                result.put("supplier_start_count",p.getStartCount());
                result.put("supplier_now_count",p.getNowCount());
                result.put("supplier_end_count",p.getEndCount());
            });
        }
        if (item.getFulfillmentStatus() == FulfillmentStatus.DELIVERED) {
            result.put("customer_delivery_content", item.getCustomerDeliveryContent());
        }
        return result;
    }

    private Map<String, Object> paymentView(Order order, List<OrderItem> items) {
        if (order.getPaymentMethod() == OrderPaymentMethod.BALANCE) {
            return balanceOrderPaymentService.buildPaymentResult(order);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order_id", order.getId());
        result.put("order_no", order.getOrderNo());
        result.put("status", paymentStatus(order));
        result.put("paid", order.getStatus() == OrderStatus.PAID
                || order.getStatus() == OrderStatus.DELIVERED
                || order.getStatus() == OrderStatus.REFUNDED
                || order.getStatus() == OrderStatus.AFTER_SALE);
        result.put("payment_method", order.getPaymentMethod() == null ? null : order.getPaymentMethod().name());
        result.put("payment_channel_code", order.getPaymentChannelCode());
        result.put("next_action", null);
        result.put("expires_at", order.getExpiresAt());
        result.put("remaining_seconds", remainingSeconds(order.getExpiresAt(), order.getStatus()));
        boolean paymentBlocked = paymentEvents != null
                && paymentEvents.existsByOrderIdAndProcessResultStartingWith(order.getId(), "LATE_PAID_");
        boolean supplierItems = items.stream().anyMatch(item -> item.getStockMode() == StockMode.SUPPLIER);
        boolean supplierPaymentAvailable = !supplierItems || supplierOrderAvailability.allowsPayment(order.getId(), items);
        if (!supplierPaymentAvailable)
            paymentBlocked = true;
        result.put("can_retry", !paymentBlocked && order.getStatus() == OrderStatus.PENDING);

        PaymentAttempt attempt = latestAttempt(order.getId());
        if (attempt != null) {
            result.put("payment_id", attempt.getId());
            result.put("payment_type", attempt.getPaymentType());
            if (attempt.getCreationStatus() == PaymentCreationStatus.SUBMITTING
                    && attempt.getCreationStartedAt() != null
                    && attempt.getCreationStartedAt().plusMinutes(2).isBefore(LocalDateTime.now())) {
                attempt.setCreationStatus(PaymentCreationStatus.UNKNOWN);
            }
            result.put("creation_status", attempt.getCreationStatus());
            boolean snapshotMatches = java.util.Objects.equals(order.getPaymentChannelCode(), attempt.getChannelCodeSnapshot())
                    && order.getActualAmount() != null && attempt.getAmount() != null
                    && order.getActualAmount().compareTo(attempt.getAmount()) == 0;
            if (!snapshotMatches) result.put("can_retry", false);
            if (attempt.getCreationStatus() == PaymentCreationStatus.SUBMITTING
                    || attempt.getCreationStatus() == PaymentCreationStatus.UNKNOWN) result.put("can_retry", false);
            if (order.getStatus() == OrderStatus.PENDING && attempt.getStatus() == PaymentStatus.PENDING
                    && snapshotMatches
                    && attempt.getCreationStatus() == PaymentCreationStatus.READY
                    && attempt.getNextAction() != null && !attempt.getNextAction().isBlank()
                    && attempt.getExpiresAt() != null && attempt.getExpiresAt().isAfter(LocalDateTime.now())
                    && (paymentEvents == null || !paymentEvents.existsByOrderIdAndProcessResultStartingWith(order.getId(),"LATE_PAID_"))
                    && supplierPaymentAvailable) {
                result.put("expires_at", attempt.getExpiresAt());
                result.put("remaining_seconds", remainingSeconds(attempt.getExpiresAt(), order.getStatus()));
                result.put("next_action", PaymentNextActionPayload.from(readNextAction(attempt.getNextAction())));
            }
        } else {
            result.put("payment_type", null);
        }
        return result;
    }

    private PaymentStatus paymentStatus(Order order) {
        if (order.getStatus() == OrderStatus.EXPIRED) return PaymentStatus.CLOSED;
        if (order.getStatus() == OrderStatus.PAID || order.getStatus() == OrderStatus.DELIVERED
                || order.getStatus() == OrderStatus.REFUNDED
                || order.getStatus() == OrderStatus.AFTER_SALE) {
            return PaymentStatus.PAID;
        }
        return PaymentStatus.PENDING;
    }

    private PaymentAttempt latestAttempt(UUID orderId) {
        List<PaymentAttempt> attempts = paymentAttemptRepository.findByOrderIdOrderByCreatedAtDesc(orderId);
        return attempts.stream().filter(attempt -> attempt.getStatus() == PaymentStatus.PENDING
                && (attempt.getCreationStatus() == PaymentCreationStatus.INTENT
                || attempt.getCreationStatus() == PaymentCreationStatus.SUBMITTING
                || attempt.getCreationStatus() == PaymentCreationStatus.UNKNOWN)).findFirst()
                .orElseGet(() -> attempts.stream().findFirst().orElse(null));
    }

    private PaymentNextAction readNextAction(String raw) {
        try {
            if (raw == null || raw.isBlank()) throw new IllegalArgumentException();
            return objectMapper.readValue(raw, PaymentNextAction.class);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE, "支付动作数据无效");
        }
    }

    private long remainingSeconds(LocalDateTime expiresAt, OrderStatus status) {
        if (status != OrderStatus.PENDING || expiresAt == null) return 0;
        return Math.max(0, Duration.between(LocalDateTime.now(), expiresAt).getSeconds());
    }

    private Map<String, Object> deliveryView(Order order, List<OrderItem> items) {
        List<CardKey> keys = cardKeyRepository.findByOrderIdOrderByCreatedAtAsc(order.getId());
        Map<UUID, List<CardKey>> byItem = keys.stream()
                .filter(key -> key.getOrderItemId() != null)
                .collect(Collectors.groupingBy(CardKey::getOrderItemId,
                        LinkedHashMap::new, Collectors.toList()));
        List<Map<String, Object>> groups = new ArrayList<>();
        for (OrderItem item : items) {
            if ("MANUAL".equalsIgnoreCase(item.getDeliveryType())) {
                continue;
            }
            List<CardKey> itemKeys = byItem.getOrDefault(item.getId(), List.of());
            List<SupplierDeliveryCard> external=supplierCards(item);
            if (itemKeys.isEmpty() && external.isEmpty()) continue;
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("product_no", item.getProductNo());
            group.put("product_title", item.getProductTitle());
            group.put("spec_name", item.getSpecName());
            group.put("quantity", item.getQuantity());
            group.put("delivery_type", item.getDeliveryType());
            group.put("fulfillment_status", item.getFulfillmentStatus());
            List<Map<String,Object>> deliveryItems=new ArrayList<>();
            itemKeys.forEach(key -> deliveryItems.add(Map.of("content_parts", List.copyOf(key.getContentParts()))));
            external.forEach(card -> deliveryItems.add(Map.of("content_parts",parts(card),"supplier_card_status",card.getCardStatus())));
            group.put("delivery_items",deliveryItems);
            groups.add(group);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("groups", groups);
        result.put("item_statuses", items.stream().map(item -> {
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("id", item.getId());
            status.put("product_no", item.getProductNo());
            status.put("product_title", item.getProductTitle());
            status.put("fulfillment_status", item.getFulfillmentStatus());
            status.put("delivery_type", item.getDeliveryType());
            return status;
        }).toList());
        return result;
    }

    private List<SupplierDeliveryCard> supplierCards(OrderItem item) {
        if (item.getStockMode()!=StockMode.SUPPLIER || item.getFulfillmentStatus()!=FulfillmentStatus.DELIVERED
                || supplierPurchases==null || supplierCards==null) return List.of();
        return supplierPurchases.findByOrderItemId(item.getId())
                .filter(p -> p.getGoodsType()==1).map(p -> supplierCards.findByPurchaseIdOrderByCreatedAtAsc(p.getId()))
                .orElseGet(List::of);
    }
    private List<String> parts(SupplierDeliveryCard card) {
        List<String> parts=new ArrayList<>();
        if (card.getCardNo()!=null && !card.getCardNo().isBlank()) parts.add(card.getCardNo());
        if (card.getCardPwd()!=null && !card.getCardPwd().isBlank()) parts.add(card.getCardPwd());
        return List.copyOf(parts);
    }
    private String cardStatusPrefix(SupplierDeliveryCard card) {
        return switch (card.getCardStatus()) {
            case 2 -> "[卡券已作废或已退款，金额以供应商订单为准]\n";
            case 3 -> "[卡券已使用]\n";
            case 4 -> "[卡券作废处理中]\n";
            default -> "";
        };
    }

}
