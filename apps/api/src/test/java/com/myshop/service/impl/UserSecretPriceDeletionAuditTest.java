package com.myshop.service.impl;

import com.myshop.aspect.OperationLogAspect;
import com.myshop.config.ClientIpResolver;
import com.myshop.constant.UserRole;
import com.myshop.context.RequestContext;
import com.myshop.entity.Product;
import com.myshop.entity.User;
import com.myshop.entity.UserSecretPrice;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.ProductSpecRepository;
import com.myshop.repository.UserLevelRepository;
import com.myshop.repository.UserRepository;
import com.myshop.repository.UserSecretPriceRepository;
import com.myshop.service.OperationLogService;
import com.myshop.service.PricingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserSecretPriceDeletionAuditTest {

    private final UserSecretPriceRepository secretPrices = mock(UserSecretPriceRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final UserLevelRepository levels = mock(UserLevelRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final ProductSpecRepository specs = mock(ProductSpecRepository.class);
    private final PricingService pricing = mock(PricingService.class);
    private final OperationLogService operationLogs = mock(OperationLogService.class);
    private final UUID operatorId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final UUID secretPriceId = UUID.randomUUID();
    private UserSecretPriceService service;

    @BeforeEach
    void setUp() {
        RequestContext.set(new RequestContext.UserInfo(operatorId, "pricing-admin", "ADMIN"));
        UserSecretPriceService target = new UserSecretPriceService(secretPrices, users, levels,
                products, specs, pricing);
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
        proxyFactory.addAspect(new OperationLogAspect(operationLogs, mock(ClientIpResolver.class)));
        service = proxyFactory.getProxy();
    }

    @AfterEach
    void clearRequestContext() {
        RequestContext.clear();
    }

    @Test
    void deleteLogsTheAdminAndUserLinkedProductRecord() {
        User user = new User();
        user.setId(userId);
        user.setRole(UserRole.USER);
        Product product = new Product();
        product.setId(productId);
        product.setIsDeleted(0);
        UserSecretPrice entry = new UserSecretPrice();
        entry.setId(secretPriceId);
        entry.setUserId(userId);
        entry.setProductId(productId);
        entry.setPrice(new BigDecimal("8.50"));
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(secretPrices.findByIdAndUserId(secretPriceId, userId)).thenReturn(Optional.of(entry));
        when(products.findByIdForUpdate(productId)).thenReturn(Optional.of(product));

        service.delete(userId, secretPriceId);

        verify(operationLogs).log(operatorId, "pricing-admin", "secret_price.delete", "USER",
                userId.toString(), "删除用户密价：密价记录=" + secretPriceId + " 商品=" + productId + " 规格=null", "unknown");
        verify(secretPrices).delete(entry);
        verify(secretPrices).flush();
    }
}
