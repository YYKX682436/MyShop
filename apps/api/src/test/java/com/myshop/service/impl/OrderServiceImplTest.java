package com.myshop.service.impl;

import com.myshop.constant.CardKeyStatus;
import com.myshop.constant.ErrorCode;
import com.myshop.constant.OrderStatus;
import com.myshop.constant.OrderType;
import com.myshop.entity.Order;
import com.myshop.entity.PaymentChannel;
import com.myshop.entity.Product;
import com.myshop.entity.ProductSpec;
import com.myshop.entity.WholesaleRule;
import com.myshop.exception.BusinessException;
import com.myshop.repository.CardKeyRepository;
import com.myshop.repository.CartItemRepository;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.PaymentChannelRepository;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.ProductSpecRepository;
import com.myshop.repository.SiteConfigRepository;
import com.myshop.repository.WholesaleRuleRepository;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private ProductRepository productRepository;
    @Mock private ProductSpecRepository productSpecRepository;
    @Mock private WholesaleRuleRepository wholesaleRuleRepository;
    @Mock private CartItemRepository cartItemRepository;
    @Mock private CardKeyRepository cardKeyRepository;
    @Mock private SiteConfigRepository siteConfigRepository;
    @Mock private PaymentChannelRepository paymentChannelRepository;
    @Mock private PaymentService paymentService;

    private OrderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new OrderServiceImpl(orderRepository, orderItemRepository, productRepository,
                productSpecRepository, wholesaleRuleRepository, cartItemRepository, cardKeyRepository,
                siteConfigRepository, paymentChannelRepository, paymentService);

        lenient().when(siteConfigRepository.findByConfigKey(anyString())).thenReturn(Optional.empty());
        lenient().when(orderRepository.countByClientIpAndStatus(anyString(), eq(OrderStatus.PENDING))).thenReturn(0L);
        lenient().when(paymentChannelRepository.findByChannelCodeAndIsDeleted("alipay", 0))
                .thenReturn(Optional.of(enabledChannel()));
        lenient().when(paymentService.createPayment(any(), eq("alipay"), any(BigDecimal.class), nullable(String.class)))
                .thenReturn(Map.of("payment_url", "https://pay.example/1"));
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

        Map<String, Object> first = service.createDirectOrder(request(productId, null, 2),
                null, "203.0.113.10", "guest-session");

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        org.mockito.Mockito.verify(orderRepository).save(orderCaptor.capture());
        Order saved = orderCaptor.getValue();
        assertEquals(new BigDecimal("20.00"), saved.getActualAmount());
        assertEquals(OrderStatus.PENDING, saved.getStatus());
        assertNotNull(first.get("lookup_token"));
        assertTrue(String.valueOf(first.get("lookup_token")).length() >= 40);
        assertEquals(new BigDecimal("20.00"), saved.getActualAmount());
    }

    @Test
    void usesOwnedSpecPriceAndRejectsSpecFromAnotherProduct() {
        UUID productId = UUID.randomUUID();
        UUID specId = UUID.randomUUID();
        Product product = product(productId, "20.00");
        product.setSpecEnabled(true);
        ProductSpec ownedSpec = spec(specId, productId, "5.00");
        when(productSpecRepository.findByProductIdAndIsDeletedOrderBySortOrderAsc(productId, 0))
                .thenReturn(List.of(ownedSpec));
        when(productSpecRepository.findById(specId)).thenReturn(Optional.of(ownedSpec));
        when(cardKeyRepository.countByProductIdAndSpecIdAndStatus(productId, specId, CardKeyStatus.AVAILABLE))
                .thenReturn(2L);
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));

        service.createDirectOrder(request(productId, specId, 2), null, "203.0.113.11", "guest-session");

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        org.mockito.Mockito.verify(orderRepository, org.mockito.Mockito.atLeastOnce()).save(orderCaptor.capture());
        assertEquals(new BigDecimal("10.00"), orderCaptor.getAllValues().get(0).getActualAmount());

        UUID otherProductId = UUID.randomUUID();
        ProductSpec foreignSpec = spec(UUID.randomUUID(), otherProductId, "1.00");
        when(productSpecRepository.findById(specId)).thenReturn(Optional.of(foreignSpec));
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.createDirectOrder(request(productId, specId, 1), null,
                        "203.0.113.11", "guest-session-2"));
        assertEquals(ErrorCode.SPEC_NOT_FOUND, exception.getCode());
    }

    @Test
    void appliesHighestMatchingWholesaleTier() {
        UUID productId = UUID.randomUUID();
        Product product = product(productId, "10.00");
        product.setWholesaleEnabled(true);
        stubProduct(product, 5);
        WholesaleRule first = new WholesaleRule();
        first.setMinQuantity(1);
        first.setUnitPrice(new BigDecimal("9.00"));
        WholesaleRule second = new WholesaleRule();
        second.setMinQuantity(5);
        second.setUnitPrice(new BigDecimal("7.50"));
        when(wholesaleRuleRepository.findByProductIdAndSpecIdIsNullOrderByMinQuantityAsc(productId))
                .thenReturn(List.of(first, second));

        service.createDirectOrder(request(productId, null, 5), null, "203.0.113.12", "guest-session");

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        org.mockito.Mockito.verify(orderRepository, org.mockito.Mockito.atLeastOnce()).save(orderCaptor.capture());
        assertEquals(new BigDecimal("37.50"), orderCaptor.getAllValues().get(0).getActualAmount());
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
        order.setOrderType(OrderType.DIRECT);
        order.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        order.setLookupTokenHash(com.myshop.utils.LookupTokens.hash(lookupToken));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

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

    private void stubProduct(Product product, long available) {
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(cardKeyRepository.countByProductIdAndSpecIdIsNullAndStatus(product.getId(), CardKeyStatus.AVAILABLE))
                .thenReturn(available);
    }

    private Product product(UUID id, String price) {
        Product product = new Product();
        product.setId(id);
        product.setTitle("Test product");
        product.setBasePrice(new BigDecimal(price));
        product.setEnabled(true);
        product.setIsDeleted(0);
        return product;
    }

    private ProductSpec spec(UUID id, UUID productId, String price) {
        ProductSpec spec = new ProductSpec();
        spec.setId(id);
        spec.setProductId(productId);
        spec.setName("Test spec");
        spec.setPrice(new BigDecimal(price));
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
        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("product_id", productId.toString());
        request.put("quantity", quantity);
        request.put("payment_method", "alipay");
        if (specId != null) request.put("spec_id", specId.toString());
        return request;
    }
}
