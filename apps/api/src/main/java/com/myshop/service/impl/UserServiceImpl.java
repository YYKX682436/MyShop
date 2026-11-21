package com.myshop.service.impl;

import com.myshop.common.PageResult;
import com.myshop.constant.ErrorCode;
import com.myshop.constant.OrderStatus;
import com.myshop.entity.Order;
import com.myshop.entity.OrderItem;
import com.myshop.entity.User;
import com.myshop.entity.UserLevel;
import com.myshop.exception.BusinessException;
import com.myshop.model.request.ChangePasswordRequest;
import com.myshop.model.response.UserProfileResponse;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.UserRepository;
import com.myshop.repository.UserLevelRepository;
import com.myshop.service.UserService;
import com.myshop.service.AfterSaleService;
import com.myshop.service.OrderExpiryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.myshop.service.PublicImageResolver publicImageResolver;

    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PasswordEncoder passwordEncoder;
    private final OrderExpiryService orderExpiryService;
    private final UserLevelRepository userLevelRepository;
    private final AfterSaleService afterSaleService;

    @Override
    public UserProfileResponse getProfile(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
        UserLevel level = user.getUserLevelId() == null ? null
                : userLevelRepository.findById(user.getUserLevelId()).orElse(null);
        return UserProfileResponse.from(user, level);
    }

    @Override
    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest request) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
        if (!passwordEncoder.matches(request.getOldPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.OLD_PASSWORD_WRONG, "原密码错误");
        }
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        if (user.getAuthVersion() == Integer.MAX_VALUE) {
            throw new IllegalStateException("User auth version overflow");
        }
        user.setAuthVersion(user.getAuthVersion() + 1);
        userRepository.save(user);
    }

    @Override
    public PageResult<?> getOrders(UUID userId, String status, int page, int pageSize) {
        PageRequest pageable = PageRequest.of(page - 1, pageSize);
        OrderStatus requestedStatus = null;
        if (status != null) {
            try {
                requestedStatus = OrderStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的订单状态: " + status);
            }
        }
        Page<Order> orderPage = findOrderPage(userId, requestedStatus, pageable);
        LocalDateTime now = LocalDateTime.now();
        while (true) {
            List<UUID> expiredIds = orderPage.getContent().stream()
                    .filter(order -> order.getStatus() == OrderStatus.PENDING && order.getExpiresAt() != null
                            && !order.getExpiresAt().isAfter(now))
                    .map(Order::getId).toList();
            if (expiredIds.isEmpty()) break;
            expiredIds.forEach(orderExpiryService::expireById);
            // Expiry and a concurrent payment both change what this page
            // should contain. A PENDING filter can refill the page with more
            // overdue orders, so validate each replacement page as well.
            orderPage = findOrderPage(userId, requestedStatus, pageable);
        }
        List<Order> orders = orderPage.getContent();
        List<UUID> orderIds = orders.stream().map(Order::getId).toList();
        Map<UUID, List<OrderItem>> itemsByOrderId = orderIds.isEmpty()
                ? Map.of()
                : orderItemRepository.findByOrderIdIn(orderIds).stream()
                .collect(java.util.stream.Collectors.groupingBy(OrderItem::getOrderId));

        List<UUID> afterSaleOrderIds = orders.stream()
                .filter(order -> order.getStatus() == OrderStatus.AFTER_SALE)
                .map(Order::getId)
                .toList();
        Map<UUID, Map<String, Object>> afterSaleSummaries = afterSaleOrderIds.isEmpty()
                ? Map.of()
                : java.util.Optional.ofNullable(afterSaleService.buildOrderSummaries(afterSaleOrderIds))
                .orElseGet(Map::of);

        var list = orders.stream()
                .map(order -> toOrderBrief(order,
                        itemsByOrderId.getOrDefault(order.getId(), List.of()),
                        afterSaleSummaries.get(order.getId())))
                .toList();
        return PageResult.of(orderPage, list);
    }

    private Page<Order> findOrderPage(UUID userId, OrderStatus status, PageRequest pageable) {
        UUID siteId = com.myshop.context.SiteRequestContext.getSiteId();
        if (siteId != null) return status == null
                ? orderRepository.findByUserIdAndSubsiteIdOrderByCreatedAtDesc(userId, siteId, pageable)
                : orderRepository.findByUserIdAndSubsiteIdAndStatusOrderByCreatedAtDesc(userId, siteId, status, pageable);
        return status == null
                ? orderRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                : orderRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, status, pageable);
    }

    private Map<String, Object> toOrderBrief(Order o, List<OrderItem> items,
                                             Map<String, Object> afterSaleSummary) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", o.getId());
        map.put("order_no", o.getOrderNo());
        map.put("actual_amount", o.getActualAmount());
        map.put("status", o.getStatus().name());
        map.put("created_at", o.getCreatedAt());
        map.put("items", items.stream().map(this::toOrderItemBrief).toList());
        if (o.getStatus() == OrderStatus.AFTER_SALE) {
            map.put("after_sale", afterSaleSummary);
        }
        return map;
    }

    private Map<String, Object> toOrderItemBrief(OrderItem item) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", item.getId());
        map.put("product_title", item.getProductTitle());
        map.put("product_cover_url", publicImageResolver == null ? item.getProductCoverUrl() : publicImageResolver.visible(item.getProductCoverUrl()));
        map.put("spec_name", item.getSpecName());
        map.put("quantity", item.getQuantity());
        map.put("unit_price", item.getUnitPrice());
        map.put("delivery_type", item.getDeliveryType());
        map.put("stock_mode", item.getStockMode());
        map.put("fulfillment_status", item.getFulfillmentStatus());
        return map;
    }
}
