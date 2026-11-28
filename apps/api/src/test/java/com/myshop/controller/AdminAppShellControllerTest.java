package com.myshop.controller;

import com.myshop.context.RequestContext;
import com.myshop.context.SiteRequestContext;
import com.myshop.entity.Subsite;
import com.myshop.exception.BusinessException;
import com.myshop.model.request.AppShellConfigUpdateRequest;
import com.myshop.service.AppShellService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AdminAppShellControllerTest {
    private final AppShellService service = mock(AppShellService.class);
    private final AdminAppShellController controller = new AdminAppShellController(service);

    @AfterEach
    void clearRequestContexts() {
        RequestContext.clear();
        SiteRequestContext.clear();
    }

    @Test
    void onlyMainSiteSuperAdminCanReadTheAppShellConfig() {
        RequestContext.set(new RequestContext.UserInfo(UUID.randomUUID(), "root", "SUPER_ADMIN"));
        assertNotNull(controller.getConfig());
        verify(service).getConfig();
    }

    @Test
    void ordinaryAdminAndSubsiteSuperAdminAreRejectedForReadsAndWrites() {
        var request = new AppShellConfigUpdateRequest(false, List.of());
        RequestContext.set(new RequestContext.UserInfo(UUID.randomUUID(), "admin", "ADMIN"));
        assertThrows(BusinessException.class, controller::getConfig);
        assertThrows(BusinessException.class, () -> controller.updateConfig(request));

        RequestContext.set(new RequestContext.UserInfo(UUID.randomUUID(), "subsite-root", "SUPER_ADMIN"));
        SiteRequestContext.set(mock(Subsite.class));
        assertThrows(BusinessException.class, controller::getConfig);
        assertThrows(BusinessException.class, () -> controller.updateConfig(request));
        verifyNoInteractions(service);
    }
}
