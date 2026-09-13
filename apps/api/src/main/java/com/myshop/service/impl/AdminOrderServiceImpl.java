package com.myshop.service.impl;

import com.myshop.common.PageResult;
import com.myshop.constant.BalanceTransactionType;
import com.myshop.constant.ErrorCode;
import com.myshop.constant.OrderPaymentMethod;
import com.myshop.constant.OrderStatus;
import com.myshop.constant.OrderType;
import com.myshop.entity.BalanceTransaction;
import com.myshop.entity.Order;
import com.myshop.entity.OrderItem;
import com.myshop.entity.PaymentAttempt;
import com.myshop.entity.User;
import com.myshop.exception.BusinessException;
import com.myshop.model.request.AdminManualFulfillRequest;
import com.myshop.model.request.AdminOrderSearchRequest;
import com.myshop.model.response.AdminOrderRefundResponse;
import com.myshop.payment.PaymentStatus;
import com.myshop.repository.BalanceTransactionRepository;
import com.myshop.service.CardKeyReclaimService;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.PaymentAttemptRepository;
import com.myshop.repository.UserRepository;
import com.myshop.service.AdminOrderService;
import com.myshop.service.FulfillmentService;
import com.myshop.service.OperationLogService;
import com.myshop.service.OrderPaymentCompletionService;
import com.myshop.service.OrderRefundService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
public class AdminOrderServiceImpl implements AdminOrderService {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final UserRepository userRepository;
    private final FulfillmentService fulfillmentService;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final OrderPaymentCompletionService orderPaymentCompletionService;
    private final OperationLogService operationLogService;
    private final OrderRefundService orderRefundService;
    private final BalanceTransactionRepository balanceTransactionRepository;
    private final CardKeyReclaimService cardKeyReclaimService;

    @Autowired
    public AdminOrderServiceImpl(OrderRepository orderRepository,
                                 OrderItemRepository orderItemRepository,
                                 UserRepository userRepository,
                                 FulfillmentService fulfillmentService,
                                 PaymentAttemptRepository paymentAttemptRepository,
                                 OrderPaymentCompletionService orderPaymentCompletionService,
                                 OperationLogService operationLogService,
                                 OrderRefundService orderRefundService,
                                 BalanceTransactionRepository balanceTransactionRepository,
                                 CardKeyReclaimService cardKeyReclaimService) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.userRepository = userRepository;
        this.fulfillmentService = fulfillmentService;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.orderPaymentCompletionService = orderPaymentCompletionService;
        this.operationLogService = operationLogService;
        this.orderRefundService = orderRefundService;
        this.balanceTransactionRepository = balanceTransactionRepository;
        this.cardKeyReclaimService = cardKeyReclaimService;
    }

    @Override
    public PageResult<?> searchOrders(AdminOrderSearchRequest request) {
        AdminOrderSearchRequest input = request == null
                ? new AdminOrderSearchRequest(null, null, null, null, null, null, null)
                : request;
        int page = input.page() == null ? 1 : input.page();
        int pageSize = input.pageSize() == null ? 20 : input.pageSize();
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分页参数无效");
        }

        String status = normalizeStatus(input.status());
        LocalDateTime createdFrom = parseDateBoundary(input.createdFrom(), "created_from");
        LocalDateTime createdTo = parseDateBoundary(input.createdTo(), "created_to");
        if (createdFrom != null && createdTo != null && !createdFrom.isBefore(createdTo)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "创建时间范围无效");
        }
        String sortDirection = normalizeSortDirection(input.sortDirection());
        String keyword = input.keyword() == null ? null : input.keyword().trim();
        String keywordPattern = keyword == null || keyword.isEmpty() ? null : "%" + escapeLike(keyword) + "%";

        var pageable = PageRequest.of(page - 1, pageSize);
        Page<Order> orderPage = orderRepository.searchAdminOrders(
                status, createdFrom, createdTo, keywordPattern, sortDirection, pageable);

        List<Order> orders = orderPage.getContent();
        List<UUID> orderIds = orders.stream().map(Order::getId).toList();
        Map<UUID, List<OrderItem>> itemsByOrderId = orderIds.isEmpty()
                ? Map.of()
                : orderItemRepository.findByOrderIdIn(orderIds).stream()
                .collect(java.util.stream.Collectors.groupingBy(OrderItem::getOrderId));
        List<UUID> userIds = orders.stream().map(Order::getUserId).filter(Objects::nonNull).distinct().toList();
        Map<UUID, User> usersById = userIds.isEmpty()
                ? Map.of()
                : userRepository.findAllById(userIds).stream()
                .collect(java.util.stream.Collectors.toMap(User::getId, user -> user));

        List<Map<String, Object>> list = orders.stream()
                .map(order -> toAdminOrder(order,
                        itemsByOrderId.getOrDefault(order.getId(), List.of()),
                        order.getUserId() == null ? null : usersById.get(order.getUserId()), false))
                .toList();
        return PageResult.of(orderPage, list);
    }

    @Override
    public Object getOrderDetail(UUID id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));
        User user = order.getUserId() == null
                ? null
                : userRepository.findById(order.getUserId()).orElse(null);
        return toAdminOrder(order, orderItemRepository.findByOrderId(order.getId()), user, true);
    }

    @Override
    @Transactional
    public void markPaid(UUID id, String reason, boolean confirmed) {
        if (!confirmed) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "必须明确确认手动标记已支付");
        }
        String normalizedReason = reason == null ? null : reason.trim();
        if (normalizedReason == null || normalizedReason.length() < 3 || normalizedReason.length() > 500) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请填写 3 至 500 个字符的操作原因");
        }
        Order order = orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));
        if (order.getStatus() == OrderStatus.PAID || order.getStatus() == OrderStatus.DELIVERED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "订单已支付");
        }
        if (order.getStatus() == OrderStatus.REFUNDED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "订单已退款，不能确认支付");
        }
        if (order.getStatus() == OrderStatus.EXPIRED) {
            throw new BusinessException(ErrorCode.ORDER_EXPIRED, "订单已过期，不能恢复为已支付");
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "当前订单状态不允许人工确认支付");
        }
        if (order.getOrderType() != OrderType.NORMAL
                || order.getPaymentMethod() != OrderPaymentMethod.ONLINE
                || order.getPaymentChannelCode() == null
                || order.getPaymentChannelCode().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "人工确认支付仅支持 NORMAL 在线支付订单，余额订单不能人工确认");
        }
        if (order.getExpiresAt() != null && !order.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.ORDER_EXPIRED, "订单已过期，不能人工确认支付");
        }
        // The repository query locks every pending attempt in creation order.
        // The last one is the current effective attempt; all older pending
        // attempts are closed so only one payment fact can remain active.
        List<PaymentAttempt> pendingAttempts = paymentAttemptRepository
                .findByOrderIdAndStatusForUpdate(order.getId(), PaymentStatus.PENDING);
        if (pendingAttempts == null || pendingAttempts.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "当前订单没有可确认的支付尝试");
        }
        PaymentAttempt current = pendingAttempts.get(pendingAttempts.size() - 1);
        if (current.getExpiresAt() != null && !current.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.ORDER_EXPIRED, "当前支付尝试已过期，不能人工确认支付");
        }
        for (PaymentAttempt attempt : pendingAttempts) {
            if (attempt == current) {
                attempt.setStatus(PaymentStatus.PAID);
            } else {
                attempt.setStatus(PaymentStatus.CLOSED);
            }
            paymentAttemptRepository.save(attempt);
        }
        if (!orderPaymentCompletionService.completeFirstPayment(order)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "订单支付状态未能完成转换");
        }
        operationLogService.logCurrentRequest(
                "order.mark_paid", "ORDER", order.getId().toString(),
                "订单号=" + order.getOrderNo() + "，人工确认支付，原因=" + normalizedReason);
    }

    @Override
    @Transactional
    public AdminOrderRefundResponse refund(UUID id, String reason, boolean confirmed,
                                           boolean reclaimCardKeys) {
        return orderRefundService.refund(id, reason, confirmed, reclaimCardKeys);
    }

    @Override
    @Transactional
    public Map<String, Object> completeManualItems(UUID orderId, AdminManualFulfillRequest request) {
        if (request == null || !Boolean.TRUE.equals(request.confirmed())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "必须明确确认完成发货");
        }
        if (request.items() == null || request.items().isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "至少选择一个待人工发货订单项");
        }

        Set<UUID> seen = new HashSet<>();
        List<FulfillmentService.ManualFulfillmentItem> items = new ArrayList<>(request.items().size());
        for (AdminManualFulfillRequest.Item item : request.items()) {
            if (item == null || item.orderItemId() == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "订单项不能为空");
            }
            if (!seen.add(item.orderItemId())) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "不能重复提交同一订单项");
            }
            items.add(new FulfillmentService.ManualFulfillmentItem(
                    item.orderItemId(), item.customerDeliveryContent()));
        }
        return fulfillmentService.completeManualItems(orderId, items);
    }

    private Map<String, Object> toAdminOrder(Order o, List<OrderItem> items, User user, boolean detail) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", o.getId());
        map.put("order_no", o.getOrderNo());
        map.put("total_amount", o.getTotalAmount());
        map.put("actual_amount", o.getActualAmount());
        map.put("status", o.getStatus().name());
        map.put("order_type", o.getOrderType());
        map.put("payment_method", o.getPaymentMethod() == null ? null : o.getPaymentMethod().name());
        map.put("payment_channel_code", o.getPaymentChannelCode());
        map.put("created_at", o.getCreatedAt());
        map.put("refunded_at", o.getRefundedAt());
        map.put("refund_amount", o.getStatus() == OrderStatus.REFUNDED ? o.getActualAmount() : null);
        map.put("refund_destination", o.getStatus() == OrderStatus.REFUNDED ? "BALANCE" : null);
        map.put("customer_type", user == null ? "GUEST" : "REGISTERED");
        map.put("username", user == null ? null : user.getUsername());
        map.put("user_no", user == null ? null : user.getUserNo());
        map.put("is_risk_flagged", o.isRiskFlagged());

        List<Map<String, Object>> itemMaps = items.stream().map(i -> {
            Map<String, Object> im = new LinkedHashMap<>();
            im.put("id", i.getId());
            im.put("product_id", i.getProductId());
            im.put("product_no", i.getProductNo());
            im.put("product_title", i.getProductTitle());
            im.put("product_cover_url", i.getProductCoverUrl());
            im.put("spec_id", i.getSpecId());
            im.put("spec_name", i.getSpecName());
            im.put("quantity", i.getQuantity());
            im.put("unit_price", i.getUnitPrice());
            im.put("subtotal", i.getSubtotal());
            im.put("cost_price_snapshot", i.getCostPriceSnapshot());
            im.put("retail_price_snapshot", i.getRetailPriceSnapshot());
            im.put("pricing_source", i.getPricingSource());
            im.put("gross_profit", grossProfit(i));
            im.put("gross_margin", grossMargin(i));
            im.put("delivery_type", i.getDeliveryType());
            im.put("customer_inputs", i.getCustomerInputs() == null ? List.of() : i.getCustomerInputs());
            im.put("stock_mode", i.getStockMode());
            im.put("fulfillment_status", i.getFulfillmentStatus());
            im.put("delivered_at", i.getDeliveredAt());
            im.put("customer_delivery_content", i.getCustomerDeliveryContent());
            im.put("manual_stock_reserved", i.getManualStockReserved());
            return im;
        }).toList();
        map.put("items", itemMaps);
        map.put("cost_total", costTotal(items));
        map.put("gross_profit_total", grossProfitTotal(items));
        map.put("gross_margin_total", grossMarginTotal(items));

        if (detail) {
            map.put("user_id", o.getUserId());
            map.put("registered_email", user == null ? null : user.getEmail());
            map.put("phone", user == null ? null : user.getPhone());
            map.put("user_level_id_snapshot", o.getUserLevelIdSnapshot());
            map.put("user_level_name_snapshot", o.getUserLevelNameSnapshot());
            map.put("expires_at", o.getExpiresAt());
            map.put("paid_at", o.getPaidAt());
            map.put("delivered_at", o.getDeliveredAt());
            map.put("client_ip", o.getClientIp());
            if (o.getStatus() == OrderStatus.REFUNDED) {
                BalanceTransaction refund = balanceTransactionRepository
                        .findFirstByOrderIdAndTypeOrderByCreatedAtDesc(
                                o.getId(), BalanceTransactionType.REFUND)
                        .orElse(null);
                if (refund != null && refund.getReason() != null && !refund.getReason().isBlank()) {
                    map.put("refund_reason", refund.getReason().trim());
                }
                map.put("refund_operator_id", refund == null ? null : refund.getOperatorId());
                map.put("refund_operator_username", refund == null || refund.getOperatorId() == null
                        ? null
                        : userRepository.findById(refund.getOperatorId())
                        .map(User::getUsername).orElse(null));
                map.put("reclaimed_card_key_count", Math.toIntExact(
                        cardKeyReclaimService.countByOrderId(o.getId())));
            }
        }
        return map;
    }

    private String normalizeStatus(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return OrderStatus.valueOf(value.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的状态筛选");
        }
    }

    private String normalizeSortDirection(String value) {
        if (value == null || value.isBlank()) return "desc";
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("asc") && !normalized.equals("desc")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "sort_direction 只能是 asc 或 desc");
        }
        return normalized;
    }

    private LocalDateTime parseDateBoundary(String value, String field) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        try {
            return LocalDate.parse(normalized).atStartOfDay();
        } catch (DateTimeParseException ignored) {
            // Continue with the date-time forms accepted by the API.
        }
        try {
            return LocalDateTime.parse(normalized);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(normalized, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            } catch (DateTimeParseException ignoredAgain) {
                try {
                    return OffsetDateTime.parse(normalized).toLocalDateTime();
                } catch (DateTimeParseException exception) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, field + " 时间格式无效");
                }
            }
        }
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private BigDecimal costTotal(List<OrderItem> items) {
        return items.stream()
                .filter(item -> item.getCostPriceSnapshot() != null)
                .map(item -> item.getCostPriceSnapshot().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal grossProfitTotal(List<OrderItem> items) {
        return items.stream()
                .map(this::grossProfit)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal grossMarginTotal(List<OrderItem> items) {
        BigDecimal revenue = items.stream()
                .filter(item -> item.getUnitPrice() != null)
                .map(item -> item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (revenue.signum() <= 0) return null;
        return grossProfitTotal(items).multiply(ONE_HUNDRED)
                .divide(revenue, 2, RoundingMode.HALF_UP);
    }

    private BigDecimal grossProfit(OrderItem item) {
        if (item.getUnitPrice() == null || item.getCostPriceSnapshot() == null) return null;
        return item.getUnitPrice().subtract(item.getCostPriceSnapshot())
                .multiply(BigDecimal.valueOf(item.getQuantity()))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal grossMargin(OrderItem item) {
        if (item.getUnitPrice() == null || item.getCostPriceSnapshot() == null
                || item.getUnitPrice().signum() <= 0) return null;
        return item.getUnitPrice().subtract(item.getCostPriceSnapshot())
                .multiply(ONE_HUNDRED)
                .divide(item.getUnitPrice(), 2, RoundingMode.HALF_UP);
    }
}
