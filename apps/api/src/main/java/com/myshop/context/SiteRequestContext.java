package com.myshop.context;

import com.myshop.entity.Subsite;
import java.util.UUID;

/** Set exclusively by the validated request-host filter. Null denotes the main site. */
public final class SiteRequestContext {
    private static final ThreadLocal<Subsite> SITE = new ThreadLocal<>();
    private SiteRequestContext() {}
    public static Subsite getSite() { return SITE.get(); }
    public static UUID getSiteId() { Subsite site = getSite(); return site == null ? null : site.getId(); }
    public static void set(Subsite site) { if (site == null) SITE.remove(); else SITE.set(site); }
    public static void clear() { SITE.remove(); }
}
