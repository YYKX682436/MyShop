package com.myshop.service.impl;

import com.myshop.constant.CardKeyStatus;
import com.myshop.constant.ErrorCode;
import com.myshop.constant.OrderStatus;
import com.myshop.constant.OrderType;
import com.myshop.constant.PricingType;
import com.myshop.constant.UserRole;
import com.myshop.entity.Order;
import com.myshop.entity.OrderItem;
import com.myshop.entity.PaymentChannel;
import com.myshop.entity.Product;
import com.myshop.entity.ProductSpec;
import com.myshop.entity.User;
import com.myshop.entity.UserLevel;
import com.myshop.exception.BusinessException;
import com.myshop.model.CustomerInputField;
import com.myshop.model.CustomerInputValue;
import com.myshop.repository.CardKeyRepository;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.PaymentChannelRepository;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.ProductSpecRepository;
import com.myshop.repository.SiteConfigRepository;
import com.myshop.repository.UserLevelRepository;
import com.myshop.service.ManualStockService;
import com.myshop.service.AutoFulfillmentService;
import com.myshop.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private ProductRepository productRepository;
    @Mock private ProductSpecRepository productSpecRepository;
    @Mock private CardKeyRepository cardKeyRepository;
    @Mock private SiteConfigRepository siteConfigRepository;
    @Mock private PaymentChannelRepository paymentChannelRepository;
    @Mock private PaymentService paymentService;
    @Mock private ManualStockService manualStockService;
    @Mock private com.myshop.repository.UserRepository userRepository;
    @Mock private UserLevelRepository userLevelRepository;
    @Mock private AutoFulfillmentService autoFulfillmentService;

    private OrderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new OrderServiceImpl(orderRepository, orderItemRepository, productRepository,
                productSpecRepository, cardKeyRepository,
                siteConfigRepository, paymentChannelRepository, paymentService, manualStockService,
                userRepository, userLevelRepository, autoFulfillmentService);

        lenient().when(siteConfigRepository.findByConfigKey(anyString())).thenReturn(Optional.empty());
        lenient().when(paymentChannelRepository.findByChannelCodeAndIsDeleted("alipay", 0))
                .thenReturn(Optional.of(enabledChannel()));
        lenient().when(paymentService.createPayment(any(), eq("alipay"), any(BigDecimal.class), nullable(String.class)))
                .thenReturn(Map.of("payment_url", "https://pay.example/1"));
        lenient().when(orderRepository.nextOrderNo()).thenReturn(1000000001L);
        lenient().when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            if (order.getId() == null) order.setId(UUID.randomUUID());
            return order;
        });
        lenient().when(orderItemRepository.findByOrderId(any())).thenReturn(List.of());
    }

    @Test
    void calculatesBasicPriceOnServerAndReturnsGuestTokenOnce() {
        UUID productId = UUID.randomUUID();
        Product product = product(productId, "10.00");
        stubProduct(product, 3);
        Map<String, Object> request = request(productId, null, 2, "10.00");
        request.put("admin_pickup", true);

        Map<String, Object> first = service.createDirectOrder(request,
                null, "203.0.113.10", "guest-session");

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        org.mockito.Mockito.verify(orderRepository).save(orderCaptor.capture());
        Order saved = orderCaptor.getValue();
        assertEquals(new BigDecimal("20.00"), saved.getActualAmount());
        assertEquals(OrderType.NORMAL, saved.getOrderType());
        assertEquals(OrderStatus.PENDING, saved.getStatus());
        assertNotNull(first.get("lookup_token"));
        assertTrue(String.valueOf(first.get("lookup_token")).length() >= 40);
        Map<?, ?> orderResult = (Map<?, ?>) first.get("order");
        assertEquals(1000000001L, orderResult.get("order_no"));
        assertEquals(new BigDecimal("20.00"), saved.getActualAmount());
    }

    @Test
    void orderItemProductNoIsAnImmutableSnapshot() {
        OrderItem item = new OrderItem();
        item.setProductNo(1L);

        assertEquals(1L, item.getProductNo());
        assertThrows(IllegalStateException.class, () -> item.setProductNo(2L));
    }

    @Test
    void directOrderStoresAllCustomerInputsWithCurrentLabels() {
        UUID productId = UUID.randomUUID();
        Product product = product(productId, "10.00");
        product.setCustomerInputFields(List.of(
                new CustomerInputField("account", "充值账号"),
                new CustomerInputField("zone", "游戏区服")));
        stubProduct(product, 3);

        Map<String, Object> request = request(productId, null, 1, "10.00");
        request.put("customer_inputs", List.of(
                Map.of("id", "zone", "label", "伪造区服", "value", "艾欧尼亚"),
                Map.of("id", "account", "value", "123456")));

        service.createDirectOrder(request, null, "203.0.113.14", "snapshot-session");

        ArgumentCaptor<com.myshop.entity.OrderItem> itemCaptor =
                ArgumentCaptor.forClass(com.myshop.entity.OrderItem.class);
        org.mockito.Mockito.verify(orderItemRepository).save(itemCaptor.capture());
        assertEquals(List.of(
                new CustomerInputValue("account", "充值账号", "123456"),
                new CustomerInputValue("zone", "游戏区服", "艾欧尼亚")),
                itemCaptor.getValue().getCustomerInputs());
    }

    @Test
    void usesOwnedSpecPriceAndRejectsSpecFromAnotherProduct() {
        UUID productId = UUID.randomUUID();
        UUID specId = UUID.randomUUID();
        Product product = product(productId, "20.00");
        product.setSpecEnabled(true);
        ProductSpec ownedSpec = spec(specId, productId, "5.00");
        when(productSpecRepository.findById(specId)).thenReturn(Optional.of(ownedSpec));
        when(cardKeyRepository.countByProductIdAndSpecIdAndStatus(productId, specId, CardKeyStatus.AVAILABLE))
                .thenReturn(2L);
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));

        service.createDirectOrder(request(productId, specId, 2, "5.00"), null, "203.0.113.11", "guest-session");

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        org.mockito.Mockito.verify(orderRepository, org.mockito.Mockito.atLeastOnce()).save(orderCaptor.capture());
        assertEquals(new BigDecimal("10.00"), orderCaptor.getAllValues().get(0).getActualAmount());

        UUID otherProductId = UUID.randomUUID();
        ProductSpec foreignSpec = spec(UUID.randomUUID(), otherProductId, "1.00");
        when(productSpecRepository.findById(specId)).thenReturn(Optional.of(foreignSpec));
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.createDirectOrder(request(productId, specId, 1, "5.00"), null,
                        "203.0.113.11", "guest-session-2"));
        assertEquals(ErrorCode.SPEC_NOT_FOUND, exception.getCode());
    }

    @Test
    void usesRetailPriceAndStoresRetailSnapshotsUntilLevelPricingIsImplemented() {
        UUID productId = UUID.randomUUID();
        Product product = product(productId, "10.00");
        stubProduct(product, 5);

        service.createDirectOrder(request(productId, null, 5, "10.00"), null, "203.0.113.12", "guest-session");

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        org.mockito.Mockito.verify(orderRepository, org.mockito.Mockito.atLeastOnce()).save(orderCaptor.capture());
        assertEquals(new BigDecimal("50.00"), orderCaptor.getAllValues().get(0).getActualAmount());

        ArgumentCaptor<com.myshop.entity.OrderItem> itemCaptor =
                ArgumentCaptor.forClass(com.myshop.entity.OrderItem.class);
        verify(orderItemRepository).save(itemCaptor.capture());
        assertEquals(1L, itemCaptor.getValue().getProductNo());
        assertEquals(new BigDecimal("10.00"), itemCaptor.getValue().getCostPriceSnapshot());
        assertEquals(new BigDecimal("10.00"), itemCaptor.getValue().getRetailPriceSnapshot());
        assertEquals(com.myshop.constant.PricingSource.RETAIL, itemCaptor.getValue().getPricingSource());
    }

    @Test
    void rejectsChangedExpectedPriceBeforeCreatingAnOrderOrReservingStock() {
        UUID productId = UUID.randomUUID();
        Product product = product(productId, "10.00");
        stubProductWithoutStock(product);
        Map<String, Object> request = request(productId, null, 1);
        request.put("expected_unit_price", 9.99);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.createDirectOrder(request, null, "203.0.113.15", "price-session"));

        assertEquals(ErrorCode.PRICE_CHANGED, exception.getCode());
        assertEquals(new BigDecimal("10.00"), exception.getParams().get("current_unit_price"));
        verify(orderRepository, never()).save(any(Order.class));
        verify(manualStockService, never()).reserveOrThrow(any(), any(), any(Integer.class));
    }

    @Test
    void normalOrderRejectsMissingBlankNonPositiveAndOverPrecisionExpectedPrice() {
        UUID productId = UUID.randomUUID();
        Product product = product(productId, "10.00");
        stubProductWithoutStock(product);

        List<Object> invalidValues = java.util.Arrays.asList(null, " ", BigDecimal.ZERO, new BigDecimal("-0.01"), new BigDecimal("10.001"));
        for (int index = 0; index < invalidValues.size(); index++) {
            final int caseIndex = index;
            Map<String, Object> request = request(productId, null, 1);
            if (invalidValues.get(index) != null) request.put("expected_unit_price", invalidValues.get(index));
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> service.createDirectOrder(request, null, "203.0.113.16", "invalid-price-session-" + caseIndex));
            assertEquals(ErrorCode.BAD_REQUEST, exception.getCode(), "invalid value at index " + index);
        }

        verify(orderRepository, never()).save(any(Order.class));
        verify(manualStockService, never()).reserveOrThrow(any(), any(), any(Integer.class));
    }

    @Test
    void rejectsInvalidQuantityBeforeCreatingOrder() {
        UUID productId = UUID.randomUUID();
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.createDirectOrder(request(productId, null, 0), null,
                        "203.0.113.13", "guest-session"));
        assertEquals(ErrorCode.PURCHASE_LIMIT_EXCEEDED, exception.getCode());
        org.mockito.Mockito.verify(orderRepository, org.mockito.Mockito.never()).save(any(Order.class));
    }

    @Test
    void guestOrderRequiresLookupTokenButOwnerMayUseIdentity() {
        UUID orderId = UUID.randomUUID();
        String lookupToken = "guest-lookup-token-123456789";
        Order order = new Order();
        order.setId(orderId);
        order.setStatus(OrderStatus.PENDING);
        order.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        order.setLookupTokenHash(com.myshop.utils.LookupTokens.hash(lookupToken));
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));

        BusinessException noCredential = assertThrows(BusinessException.class,
                () -> service.getOrderStatus(orderId, null, null));
        assertEquals(ErrorCode.FORBIDDEN, noCredential.getCode());

        BusinessException wrongCredential = assertThrows(BusinessException.class,
                () -> service.getOrderStatus(orderId, "wrong-token", null));
        assertEquals(ErrorCode.FORBIDDEN, wrongCredential.getCode());

        Map<String, Object> guestResult = service.getOrderStatus(orderId, lookupToken, null);
        assertEquals("PENDING", guestResult.get("status"));

        UUID userId = UUID.randomUUID();
        order.setUserId(userId);
        Map<String, Object> ownerResult = service.getOrderStatus(orderId, null, userId);
        assertEquals("PENDING", ownerResult.get("status"));
    }

    @Test
    void enabledAdminPickupKeepsNormalAmountAndUsesTheRealFulfillmentBoundary() {
        UUID adminId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        User admin = user(adminId, "store-admin", "admin@example.com");
        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        stubProduct(product(productId, "12.50"), 1);

        Map<String, Object> request = request(productId, null, 1);
        request.put("admin_pickup", false);
        request.put("payment_method", "alipay");

        Map<String, Object> result = service.createDirectOrder(request, adminId,
                "203.0.113.20", "admin-session");

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(orderCaptor.capture());
        Order saved = orderCaptor.getValue();
        assertEquals(OrderType.ADMIN_PICKUP, saved.getOrderType());
        assertEquals("ADMIN_PICKUP", saved.getPaymentMethod());
        assertEquals(new BigDecimal("12.50"), saved.getTotalAmount());
        assertEquals(new BigDecimal("0.00"), saved.getActualAmount());
        assertEquals(OrderStatus.PAID, saved.getStatus());
        assertNotNull(saved.getPaidAt());
        assertEquals(OrderType.ADMIN_PICKUP, ((Map<?, ?>) result.get("payment")).get("order_type"));
        assertEquals(true, ((Map<?, ?>) result.get("payment")).get("admin_pickup"));
        verify(paymentService, never()).createPayment(any(), anyString(), any(BigDecimal.class), nullable(String.class));
        verify(autoFulfillmentService).trigger(saved.getId());
    }

    @Test
    void ordinaryUserStaysOnNormalPaymentEvenWhenTheClientSendsTheFlag() {
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        User user = user(userId, "buyer", "buyer@example.com");
        user.setRole(UserRole.USER);
        UUID levelId = UUID.randomUUID();
        UserLevel level = new UserLevel();
        level.setId(levelId);
        level.setName("普通会员");
        user.setUserLevelId(levelId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userLevelRepository.findById(levelId)).thenReturn(Optional.of(level));
        stubProduct(product(productId, "12.50"), 1);

        Map<String, Object> request = request(productId, null, 1, "12.50");
        request.put("admin_pickup", true);

        Map<String, Object> result = service.createDirectOrder(request, userId,
                "203.0.113.21", "user-session");

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(orderCaptor.capture());
        Order saved = orderCaptor.getValue();
        assertEquals(OrderType.NORMAL, saved.getOrderType());
        assertEquals(OrderStatus.PENDING, saved.getStatus());
        assertEquals("alipay", saved.getPaymentMethod());
        assertEquals(new BigDecimal("12.50"), saved.getActualAmount());
        assertEquals(levelId, saved.getUserLevelIdSnapshot());
        assertEquals("普通会员", saved.getUserLevelNameSnapshot());
        assertEquals("https://pay.example/1", ((Map<?, ?>) result.get("payment")).get("payment_url"));
        verify(paymentService).createPayment(any(), eq("alipay"), any(BigDecimal.class), nullable(String.class));
        verify(autoFulfillmentService, never()).trigger(any());
    }

    @Test
    void ordinaryUserExpectedPriceMustMatchTheCurrentEffectiveLevelPrice() {
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID levelId = UUID.randomUUID();
        User buyer = user(userId, "markup-buyer", "markup@example.com");
        buyer.setRole(UserRole.USER);
        buyer.setUserLevelId(levelId);
        UserLevel level = new UserLevel();
        level.setId(levelId);
        level.setName("成本加价会员");
        level.setPricingType(PricingType.COST_MARKUP);
        level.setMarkupRate(new BigDecimal("10.0000"));
        level.setEnabled(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(buyer));
        when(userLevelRepository.findById(levelId)).thenReturn(Optional.of(level));

        Product product = product(productId, "100.00");
        product.setCostPrice(new BigDecimal("80.00"));
        stubProduct(product, 1);

        Map<String, Object> result = service.createDirectOrder(request(productId, null, 1, "88.00"),
                userId, "203.0.113.23", "markup-session");

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(orderCaptor.capture());
        assertEquals(new BigDecimal("88.00"), orderCaptor.getValue().getActualAmount());
        assertEquals(new BigDecimal("88.00"), ((Map<?, ?>) result.get("order")).get("actual_amount"));
    }

    @Test
    void userOrderDetailDoesNotExposePricingSnapshotsOrLevelContext() {
        UUID productId = UUID.randomUUID();
        Product product = product(productId, "10.00");
        stubProduct(product, 1);
        OrderItem item = new OrderItem();
        item.setId(UUID.randomUUID());
        item.setProductId(productId);
        item.setProductNo(product.getProductNo());
        item.setProductTitle(product.getTitle());
        item.setQuantity(1);
        item.setUnitPrice(new BigDecimal("10.00"));
        item.setSubtotal(new BigDecimal("10.00"));
        item.setCostPriceSnapshot(new BigDecimal("5.00"));
        item.setRetailPriceSnapshot(new BigDecimal("10.00"));
        item.setPricingSource(com.myshop.constant.PricingSource.RETAIL);
        when(orderItemRepository.findByOrderId(any())).thenReturn(List.of(item));

        Map<String, Object> result = service.createDirectOrder(request(productId, null, 1, "10.00"),
                null, "203.0.113.24", "user-detail-session");

        Map<?, ?> order = (Map<?, ?>) result.get("order");
        assertFalse(order.containsKey("user_level_id_snapshot"));
        assertFalse(order.containsKey("user_level_name_snapshot"));
        Map<?, ?> itemDetail = (Map<?, ?>) ((List<?>) order.get("items")).get(0);
        assertFalse(itemDetail.containsKey("cost_price_snapshot"));
        assertFalse(itemDetail.containsKey("retail_price_snapshot"));
        assertFalse(itemDetail.containsKey("pricing_source"));
        assertFalse(itemDetail.containsKey("gross_profit"));
        assertFalse(itemDetail.containsKey("gross_margin"));
        assertEquals(new BigDecimal("10.00"), itemDetail.get("unit_price"));
    }

    @Test
    void superAdminAlwaysGetsAdminPickupWithoutClientFlag() {
        UUID superAdminId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        User superAdmin = user(superAdminId, "super-admin", "super@example.com");
        superAdmin.setRole(UserRole.SUPER_ADMIN);
        when(userRepository.findById(superAdminId)).thenReturn(Optional.of(superAdmin));
        stubProduct(product(productId, "8.00"), 1);

        Map<String, Object> result = service.createDirectOrder(request(productId, null, 1),
                superAdminId, "203.0.113.22", "super-session");

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(orderCaptor.capture());
        Order saved = orderCaptor.getValue();
        assertEquals(OrderType.ADMIN_PICKUP, saved.getOrderType());
        assertEquals("ADMIN_PICKUP", saved.getPaymentMethod());
        assertEquals(OrderStatus.PAID, saved.getStatus());
        assertEquals(new BigDecimal("0.00"), saved.getActualAmount());
        assertEquals(OrderType.ADMIN_PICKUP, ((Map<?, ?>) result.get("payment")).get("order_type"));
        verify(paymentService, never()).createPayment(any(), anyString(), any(BigDecimal.class), nullable(String.class));
        verify(autoFulfillmentService).trigger(saved.getId());
    }

    private User user(UUID id, String username, String email) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(email);
        user.setRole(UserRole.ADMIN);
        user.setIsDeleted(0);
        return user;
    }

    private void stubProduct(Product product, long available) {
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(cardKeyRepository.countByProductIdAndSpecIdIsNullAndStatus(product.getId(), CardKeyStatus.AVAILABLE))
                .thenReturn(available);
    }

    private void stubProductWithoutStock(Product product) {
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
    }

    private Product product(UUID id, String price) {
        Product product = new Product();
        product.setId(id);
        product.setProductNo(1L);
        product.setTitle("Test product");
        product.setRetailPrice(new BigDecimal(price));
        product.setCostPrice(new BigDecimal(price));
        product.setEnabled(true);
        product.setIsDeleted(0);
        return product;
    }

    private ProductSpec spec(UUID id, UUID productId, String price) {
        ProductSpec spec = new ProductSpec();
        spec.setId(id);
        spec.setProductId(productId);
        spec.setName("Test spec");
        spec.setRetailPrice(new BigDecimal(price));
        spec.setCostPrice(new BigDecimal(price));
        spec.setIsDeleted(0);
        return spec;
    }

    private PaymentChannel enabledChannel() {
        PaymentChannel channel = new PaymentChannel();
        channel.setChannelCode("alipay");
        channel.setChannelName("支付宝");
        channel.setProviderType("epay");
        channel.setPaymentType("alipay");
        channel.setEnabled(true);
        channel.setIsDeleted(0);
        return channel;
    }

    private Map<String, Object> request(UUID productId, UUID specId, int quantity) {
        return request(productId, specId, quantity, null);
    }

    private Map<String, Object> request(UUID productId, UUID specId, int quantity, String expectedUnitPrice) {
        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("product_id", productId.toString());
        request.put("quantity", quantity);
        request.put("payment_method", "alipay");
        if (specId != null) request.put("spec_id", specId.toString());
        if (expectedUnitPrice != null) request.put("expected_unit_price", expectedUnitPrice);
        return request;
    }
}
