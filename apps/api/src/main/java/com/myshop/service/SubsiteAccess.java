package com.myshop.service;

import com.myshop.constant.UserRole;
import com.myshop.context.SiteRequestContext;
import com.myshop.entity.Subsite;
import com.myshop.entity.User;
import java.util.Objects;

public final class SubsiteAccess {
    private SubsiteAccess() {}
    public static boolean permits(User user) {
        Subsite site = SiteRequestContext.getSite();
        if (site == null) return user.getSubsiteId() == null;
        if (user.getRole() != UserRole.USER) return false;
        return Objects.equals(user.getSubsiteId(), site.getId()) || site.getOwnerId().equals(user.getId());
    }
}
