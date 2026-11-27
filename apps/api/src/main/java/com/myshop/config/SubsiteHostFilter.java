package com.myshop.config;

import com.myshop.context.SiteRequestContext;
import com.myshop.entity.Subsite;
import com.myshop.repository.SubsiteRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Locale;
import java.util.regex.Pattern;

/** Host is forwarded unchanged by the trusted edge; never consume client forwarding headers here. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SubsiteHostFilter extends OncePerRequestFilter {
    private static final Pattern HOST = Pattern.compile("^[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?(?::[0-9]{1,5})?$");
    private final SubsiteRepository sites;
    private final SubsiteDomainRules domainRules;

    public SubsiteHostFilter(SubsiteRepository sites, @Value("${app.public-site-url:http://localhost:3000}") String origin) {
        this.sites = sites;
        this.domainRules = SubsiteDomainRules.fromPublicSiteUrl(origin);
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            String raw = request.getHeader("Host");
            if (raw == null) { response.setStatus(400); return; }
            String host = raw.toLowerCase(Locale.ROOT);
            if (!HOST.matcher(host).matches()) { response.setStatus(400); return; }
            int port = host.indexOf(':');
            if (port >= 0) host = host.substring(0, port);
            boolean health = (host.equals("127.0.0.1") || host.equals("localhost"))
                    && (request.getRemoteAddr().equals("127.0.0.1") || request.getRemoteAddr().equals("::1"))
                    && request.getRequestURI().equals(request.getContextPath() + "/site/config");
            if (host.equals(domainRules.mainHost()) || health) { chain.doFilter(request, response); return; }
            String suffix = domainRules.subsiteHostSuffix();
            if (suffix.isEmpty() || !host.endsWith(suffix)) { response.setStatus(404); return; }
            String prefix = host.substring(0, host.length() - suffix.length());
            if (!prefix.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) { response.setStatus(404); return; }
            Subsite site = sites.findByPrefix(prefix).orElse(null);
            if (site == null) { response.setStatus(404); return; }
            SiteRequestContext.set(site);
            chain.doFilter(request, response);
        } finally { SiteRequestContext.clear(); }
    }
}
