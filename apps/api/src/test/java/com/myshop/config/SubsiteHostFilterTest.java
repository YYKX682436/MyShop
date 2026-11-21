package com.myshop.config;

import com.myshop.context.SiteRequestContext;
import com.myshop.entity.Subsite;
import com.myshop.repository.SubsiteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SubsiteHostFilterTest {
    @Test void acceptsOnlyRegisteredSingleLabelHostAndIgnoresForwardedHeaders() throws Exception {
        SubsiteRepository sites = mock(SubsiteRepository.class);
        Subsite site = new Subsite(); site.setPrefix("one");
        when(sites.findByPrefix("one")).thenReturn(Optional.of(site));
        SubsiteHostFilter filter = new SubsiteHostFilter(sites, "https://mc.mc520.top");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/site/config");
        request.addHeader("Host", "one.mc520.top"); request.addHeader("X-Forwarded-Host", "other.mc520.top");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req,res) -> assertSame(site, SiteRequestContext.getSite()));
        assertNull(SiteRequestContext.getSite());
        verify(sites).findByPrefix("one"); verify(sites, never()).findByPrefix("other");
    }
    @Test void rejectsUnknownAndNestedNames() throws Exception {
        SubsiteRepository sites = mock(SubsiteRepository.class);
        SubsiteHostFilter filter = new SubsiteHostFilter(sites, "https://mc.mc520.top");
        for (String host : new String[]{"unknown.mc520.top", "a.b.mc520.top", "mc520.top", "evil.test", "mc.mc520.top.evil.test"}) {
            MockHttpServletRequest request = new MockHttpServletRequest(); request.addHeader("Host", host);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain(); filter.doFilter(request, response, chain);
            assertEquals(404, response.getStatus(), host); assertNull(chain.getRequest());
        }
    }
}
