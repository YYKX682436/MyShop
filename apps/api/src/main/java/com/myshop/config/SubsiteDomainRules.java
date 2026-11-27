package com.myshop.config;

import java.net.URI;
import java.util.Locale;

/** Shared public-host parsing for subsite URLs and host routing. */
public record SubsiteDomainRules(String mainHost, String domainSuffix) {
    public static SubsiteDomainRules fromPublicSiteUrl(String publicSiteUrl) {
        String mainHost = URI.create(publicSiteUrl).getHost().toLowerCase(Locale.ROOT);
        int dot = mainHost.indexOf('.');
        return new SubsiteDomainRules(mainHost, dot < 0 ? null : mainHost.substring(dot + 1));
    }

    public String subsiteHostSuffix() {
        return domainSuffix == null ? "" : "." + domainSuffix;
    }

    public String subsiteUrl(String prefix) {
        return domainSuffix == null ? null : "https://" + prefix + "." + domainSuffix;
    }
}
