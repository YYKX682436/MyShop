package com.myshop.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.context.RequestContext;
import com.myshop.entity.SiteConfig;
import com.myshop.repository.SiteConfigRepository;
import com.myshop.repository.UserRepository;
import com.myshop.utils.JwtUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MessageAccessFilterTest {
    @AfterEach void clear() { RequestContext.clear(); }

    @Test void viewerAssertionDetectsCookieChangesButNeverGrantsAuthority() throws Exception {
        UUID user=UUID.randomUUID();
        RequestContext.set(new RequestContext.UserInfo(user,"buyer","USER"));
        var filter=new MessageIdentityFilter();
        var request=new MockHttpServletRequest("GET","/api/support/conversations");
        request.setServletPath("/support/conversations"); request.addHeader("X-Message-Viewer","guest");
        var rejected=new MockHttpServletResponse(); var blocked=new MockFilterChain();
        filter.doFilter(request,rejected,blocked);
        assertEquals(409,rejected.getStatus()); assertNull(blocked.getRequest());
        var matching=new MockHttpServletRequest("GET","/api/support/conversations");
        matching.setServletPath("/support/conversations"); matching.addHeader("X-Message-Viewer","user:"+user);
        var accepted=new MockFilterChain(); filter.doFilter(matching,new MockHttpServletResponse(),accepted);
        assertNotNull(accepted.getRequest());
    }

    @Test void maintenanceAllowsOnlySupportAndRequiredAuthenticatedImageReads() throws Exception {
        SiteConfigRepository configs=mock(SiteConfigRepository.class);
        SiteConfig enabled=new SiteConfig(); enabled.setConfigValue("true");
        when(configs.findByConfigKey("maintenance_enabled")).thenReturn(Optional.of(enabled));
        when(configs.findByConfigKey("maintenance_message")).thenReturn(Optional.empty());
        var filter=new MaintenanceFilter(configs,mock(UserRepository.class),new ObjectMapper(),mock(JwtUtils.class));
        for (String path : new String[]{"/api/support/conversations", "/api/user/profile", "/api/after-sale-attachments/123", "/api/supplier-complaint-attachments/123"}) {
            var request=new MockHttpServletRequest("GET",path); var response=new MockHttpServletResponse(); var chain=new MockFilterChain();
            filter.doFilter(request,response,chain); assertNotNull(chain.getRequest(),path);
        }
        var blockedRequest=new MockHttpServletRequest("GET","/api/orders/123");
        var blockedResponse=new MockHttpServletResponse(); var blockedChain=new MockFilterChain();
        filter.doFilter(blockedRequest,blockedResponse,blockedChain);
        assertEquals(503,blockedResponse.getStatus()); assertNull(blockedChain.getRequest());
        var wrongMethod=new MockHttpServletRequest("POST","/api/user/profile");
        var wrongResponse=new MockHttpServletResponse(); var wrongChain=new MockFilterChain();
        filter.doFilter(wrongMethod,wrongResponse,wrongChain);
        assertEquals(503,wrongResponse.getStatus()); assertNull(wrongChain.getRequest());
    }
}
