package com.myshop.service.impl;

import com.myshop.constant.OrderStatus;
import com.myshop.constant.OrderType;
import com.myshop.entity.Order;
import com.myshop.entity.User;
import com.myshop.repository.CardKeyRepository;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.SiteConfigRepository;
import com.myshop.repository.UserRepository;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailServiceImplTest {

    @Mock private ObjectProvider<JavaMailSender> mailSenderProvider;
    @Mock private JavaMailSender mailSender;
    @Mock private OrderRepository orderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private CardKeyRepository cardKeyRepository;
    @Mock private SiteConfigRepository siteConfigRepository;
    @Mock private UserRepository userRepository;

    private EmailServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new EmailServiceImpl(mailSenderProvider, orderRepository, orderItemRepository,
                cardKeyRepository, siteConfigRepository, userRepository);
        ReflectionTestUtils.setField(service, "mailEnabled", true);
        ReflectionTestUtils.setField(service, "fromAddress", "noreply@example.com");
        ReflectionTestUtils.setField(service, "siteUrl", "https://shop.example");
    }

    @Test
    void guestDeliverySkipsEmailWithoutLookingUpARecipientOrLoggingAFailure() {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order(orderId, null)));

        service.sendDeliveryEmail(orderId, "guest-token", List.of());

        verify(orderRepository).findById(orderId);
        verifyNoInteractions(userRepository, mailSenderProvider, mailSender);
    }

    @Test
    void registeredDeliveryUsesTheCurrentAccountEmail() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order(orderId, userId)));

        User user = new User();
        user.setId(userId);
        user.setEmail("account@example.com");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(mailSenderProvider.getIfAvailable()).thenReturn(mailSender);
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(List.of());
        when(cardKeyRepository.findByOrderIdOrderByCreatedAtAsc(orderId)).thenReturn(List.of());
        when(siteConfigRepository.findByConfigKey("site_name")).thenReturn(Optional.empty());

        service.sendDeliveryEmail(orderId, null, List.of());

        verify(userRepository).findById(userId);
        verify(mailSender).send(any(MimeMessage.class));
    }

    private Order order(UUID id, UUID userId) {
        Order order = new Order();
        order.setId(id);
        order.setUserId(userId);
        order.setOrderNo(1000000001L);
        order.setOrderType(OrderType.NORMAL);
        order.setStatus(OrderStatus.DELIVERED);
        order.setActualAmount(new BigDecimal("10.00"));
        order.setTotalAmount(new BigDecimal("10.00"));
        return order;
    }
}
