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
import com.myshop.service.ManualStockService;
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

    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PasswordEncoder passwordEncoder;
    private final ManualStockService manualStockService;
    private final UserLevelRepository userLevelRepository;

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
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
        if (!passwordEncoder.matches(request.getOldPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.OLD_PASSWORD_WRONG, "原密码错误");
        }
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
    }

    @Override
    @Transactional
    public PageResult<?> getOrders(UUID userId, String status, int page, int pageSize) {
        PageRequest pageable = PageRequest.of(page - 1, pageSize);
        Page<Order> orderPage;
        if (status != null) {
            OrderStatus os;
            try {
                os = OrderStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的订单状态: " + status);
            }
            orderPage = orderRepository.findByUserIdAndStatusOrderByCreatedAtDesc(
                    userId, os, pageable);
        } else {
            orderPage = orderRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        }

        // 主动过期检查：PENDING 且已超时的订单标记为 EXPIRED（与 queryOrders 逻辑一致）
        LocalDateTime now = LocalDateTime.now();
        for (Order o : orderPage.getContent()) {
            if (o.getStatus() == OrderStatus.PENDING && o.getExpiresAt() != null && o.getExpiresAt().isBefore(now)) {
                // The list query may race the payment/expiry worker. Lock the
                // order before touching its item reservation so recovery is
                // idempotent across all expiry entry points.
                Order locked = orderRepository.findByIdForUpdate(o.getId()).orElse(o);
                if (locked.getStatus() != OrderStatus.PENDING || locked.getExpiresAt() == null
                        || !locked.getExpiresAt().isBefore(LocalDateTime.now())) {
                    continue;
                }
                orderItemRepository.findByOrderId(locked.getId()).forEach(item -> {
                    if (item.getManualStockReserved() > 0) {
                        manualStockService.release(item.getProductId(), item.getSpecId(), item.getManualStockReserved());
                        item.setManualStockReserved(0);
                    }
                    item.setFulfillmentStatus(com.myshop.constant.FulfillmentStatus.CANCELLED);
                    orderItemRepository.save(item);
                });
                locked.setStatus(OrderStatus.EXPIRED);
                orderRepository.save(locked);
            }
        }

        var list = orderPage.getContent().stream().map(this::toOrderBrief).toList();
        return PageResult.of(orderPage, list);
    }

    private Map<String, Object> toOrderBrief(Order o) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", o.getId());
        map.put("total_amount", o.getTotalAmount());
        map.put("actual_amount", o.getActualAmount());
        map.put("status", o.getStatus().name());
        map.put("order_type", o.getOrderType());
        map.put("payment_method", o.getPaymentMethod());
        map.put("created_at", o.getCreatedAt());
        map.put("items", orderItemRepository.findByOrderId(o.getId()).stream().map(this::toOrderItemBrief).toList());
        return map;
    }

    private Map<String, Object> toOrderItemBrief(OrderItem item) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", item.getId());
        map.put("product_id", item.getProductId());
        map.put("product_code", item.getProductCode());
        map.put("product_title", item.getProductTitle());
        map.put("spec_name", item.getSpecName());
        map.put("quantity", item.getQuantity());
        map.put("unit_price", item.getUnitPrice());
        map.put("subtotal", item.getSubtotal());
        map.put("delivery_type", item.getDeliveryType());
        map.put("customer_inputs", item.getCustomerInputs() == null ? List.of() : item.getCustomerInputs());
        map.put("stock_mode", item.getStockMode());
        map.put("fulfillment_status", item.getFulfillmentStatus());
        map.put("delivered_at", item.getDeliveredAt());
        return map;
    }
}
