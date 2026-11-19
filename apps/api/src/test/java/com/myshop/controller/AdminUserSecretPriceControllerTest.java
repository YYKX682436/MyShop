package com.myshop.controller;

import com.myshop.aspect.OperationLogAspect;
import com.myshop.common.ApiResponse;
import com.myshop.config.ClientIpResolver;
import com.myshop.context.RequestContext;
import com.myshop.model.request.UserSecretPriceRequest;
import com.myshop.model.response.UserSecretPriceResponse;
import com.myshop.service.OperationLogService;
import com.myshop.service.impl.UserSecretPriceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminUserSecretPriceControllerTest {

    private final UserSecretPriceService secretPriceService = mock(UserSecretPriceService.class);
    private final OperationLogService operationLogService = mock(OperationLogService.class);
    private final UUID operatorId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final UUID specId = UUID.randomUUID();
    private final UUID secretPriceId = UUID.randomUUID();
    private AdminUserSecretPriceController controller;

    @BeforeEach
    void setUp() {
        RequestContext.set(new RequestContext.UserInfo(operatorId, "pricing-admin", "ADMIN"));
        var aspect = new OperationLogAspect(operationLogService, mock(ClientIpResolver.class));
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(new AdminUserSecretPriceController(secretPriceService));
        proxyFactory.addAspect(aspect);
        controller = proxyFactory.getProxy();
    }

    @AfterEach
    void clearRequestContext() {
        RequestContext.clear();
    }

    @Test
    void createUpdateAndDeleteAreLoggedWithOperatorUserAndSecretPriceTarget() {
        UserSecretPriceRequest createRequest = new UserSecretPriceRequest(productId, specId, new BigDecimal("8.50"));
        UserSecretPriceResponse response = new UserSecretPriceResponse(secretPriceId, productId,
                "Test product", specId, "Monthly", new BigDecimal("8.50"), new BigDecimal("10.00"),
                new BigDecimal("12.00"), new BigDecimal("5.00"), false);
        when(secretPriceService.create(userId, createRequest)).thenReturn(response);
        when(secretPriceService.update(eq(userId), eq(secretPriceId), any(UserSecretPriceRequest.class)))
                .thenReturn(response);

        controller.create(userId, createRequest);
        controller.update(userId, secretPriceId, new UserSecretPriceRequest(null, null, new BigDecimal("9.00")));
        controller.delete(userId, secretPriceId);

        verify(operationLogService).log(operatorId, "pricing-admin", "secret_price.create", "USER_SECRET_PRICE",
                secretPriceId.toString(), "新增用户密价：用户=" + userId + " 商品=" + productId + " 规格=" + specId, "unknown");
        verify(operationLogService).log(operatorId, "pricing-admin", "secret_price.update", "USER_SECRET_PRICE",
                secretPriceId.toString(), "修改用户密价：用户=" + userId + " 密价记录=" + secretPriceId
                        + " 商品=" + productId + " 规格=" + specId, "unknown");
        verify(operationLogService).log(operatorId, "pricing-admin", "secret_price.delete", "USER_SECRET_PRICE",
                secretPriceId.toString(), "删除用户密价：用户=" + userId + " 密价记录=" + secretPriceId, "unknown");
    }
}
