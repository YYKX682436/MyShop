package com.myshop.service;

import com.myshop.constant.UserRole;
import com.myshop.context.SiteRequestContext;
import com.myshop.entity.Subsite;
import com.myshop.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SubsiteAccessTest {
    @AfterEach void clear() { SiteRequestContext.clear(); }
    @Test void separatesCustomersOwnersAndAdministrators() {
        UUID ownerId = UUID.randomUUID(), siteId = UUID.randomUUID();
        Subsite site = new Subsite(); site.setId(siteId); site.setOwnerId(ownerId);
        User owner = new User(); owner.setId(ownerId);
        User customer = new User(); customer.setId(UUID.randomUUID()); customer.setSubsiteId(siteId);
        User stranger = new User(); stranger.setId(UUID.randomUUID()); stranger.setSubsiteId(UUID.randomUUID());
        User admin = new User(); admin.setRole(UserRole.ADMIN);
        assertTrue(SubsiteAccess.permits(owner)); assertFalse(SubsiteAccess.permits(customer));
        SiteRequestContext.set(site);
        assertTrue(SubsiteAccess.permits(owner)); assertTrue(SubsiteAccess.permits(customer));
        assertFalse(SubsiteAccess.permits(stranger)); assertFalse(SubsiteAccess.permits(admin));
    }
}
