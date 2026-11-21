package com.myshop.service;

import com.myshop.context.SiteRequestContext;
import com.myshop.entity.Order;
import com.myshop.entity.Subsite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SubsiteOrderAccessTest {
    @AfterEach void clear() { SiteRequestContext.clear(); }
    @Test void guestIsBoundToOrderSiteAndTransferredUserCanReadHistoryOnMain() {
        UUID originalSite=UUID.randomUUID(), user=UUID.randomUUID();
        Order order=new Order(); order.setSubsiteId(originalSite); order.setUserId(user);
        assertFalse(SubsiteOrderAccess.permits(order,null));
        assertTrue(SubsiteOrderAccess.permits(order,user));
        Subsite site=new Subsite(); site.setId(originalSite); SiteRequestContext.set(site);
        assertTrue(SubsiteOrderAccess.permits(order,null));
        site.setId(UUID.randomUUID());
        assertFalse(SubsiteOrderAccess.permits(order,user));
    }
}
