package com.myshop.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.config.AppShellDomainPolicy;
import com.myshop.config.SubsiteDomainRules;
import com.myshop.constant.ErrorCode;
import com.myshop.entity.SiteConfig;
import com.myshop.entity.Subsite;
import com.myshop.exception.BusinessException;
import com.myshop.model.request.AppShellConfigUpdateRequest;
import com.myshop.model.response.AppShellConfigResponse;
import com.myshop.model.response.AppShellResolveResponse;
import com.myshop.repository.SiteConfigRepository;
import com.myshop.repository.SubsiteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AppShellService {
    private static final String CONFIG_KEY = "app_shell_config";
    private static final int MAX_RULES = 500;
    private static final int SCHEMA_VERSION = 1;

    private final SiteConfigRepository configs;
    private final SubsiteRepository subsites;
    private final ObjectMapper mapper;

    @Value("${app.public-site-url:https://mc.mc520.top}")
    private String publicSiteUrl;

    @Transactional(readOnly = true)
    public AppShellConfigResponse getConfig() {
        var config = readConfig();
        return toResponse(config);
    }

    @Transactional
    public AppShellConfigResponse updateConfig(AppShellConfigUpdateRequest request) {
        if (request == null || request.rules() == null || request.rules().size() > MAX_RULES) {
            throw badRequest("域名规则数量无效");
        }
        Set<String> seen = new HashSet<>();
        List<Rule> rules = new ArrayList<>();
        for (var input : request.rules()) {
            if (input == null) throw badRequest("域名规则不能为空");
            String pattern = AppShellDomainPolicy.normalizePattern(input.domain_pattern());
            if (pattern == null || !seen.add(pattern)) throw badRequest("域名格式无效或重复");
            String type = input.site_type() == null ? "" : input.site_type().trim().toUpperCase(Locale.ROOT);
            if (!Set.of("MYSHOP", "EXTERNAL").contains(type)) throw badRequest("站点类型无效");
            String note = input.note() == null ? "" : input.note().trim();
            if (note.length() > 100) throw badRequest("备注不能超过 100 个字符");
            rules.add(new Rule(pattern, type, input.enabled(), note));
        }
        Config normalized = new Config(request.enabled(), rules);
        String json;
        try {
            json = mapper.writeValueAsString(normalized);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize app shell config", e);
        }
        SiteConfig entity = configs.findByConfigKeyForUpdate(CONFIG_KEY).orElseGet(() -> {
            SiteConfig created = new SiteConfig();
            created.setConfigKey(CONFIG_KEY);
            created.setConfigGroup("site");
            return created;
        });
        entity.setConfigValue(json);
        configs.saveAndFlush(entity);
        return toResponse(normalized);
    }

    @Transactional(readOnly = true)
    public AppShellResolveResponse resolve(String originInput) {
        AppShellDomainPolicy.Origin origin = AppShellDomainPolicy.parseOrigin(originInput);
        if (origin == null) return denied(null, "invalid_origin");
        Config config = readConfig();
        if (!config.enabled()) return denied(origin.canonicalOrigin(), "unavailable");

        String selectedPattern = AppShellDomainPolicy.selectPattern(
                config.rules().stream().map(Rule::pattern).toList(), origin.host());
        Rule match = config.rules().stream().filter(rule -> rule.pattern().equals(selectedPattern)).findFirst().orElse(null);
        if (match == null || !match.enabled()) return denied(origin.canonicalOrigin(), "not_authorized");

        if ("EXTERNAL".equals(match.siteType())) {
            return new AppShellResolveResponse(SCHEMA_VERSION, true, origin.canonicalOrigin(), "external", null, null);
        }
        return resolveMyShop(origin);
    }

    private AppShellResolveResponse resolveMyShop(AppShellDomainPolicy.Origin origin) {
        SubsiteDomainRules domains;
        try {
            domains = SubsiteDomainRules.fromPublicSiteUrl(publicSiteUrl);
        } catch (RuntimeException e) {
            return denied(origin.canonicalOrigin(), "unavailable");
        }
        if (origin.host().equals(domains.mainHost())) {
            String name = configs.findByConfigKey("site_name").map(SiteConfig::getConfigValue).orElse("");
            String icon = configs.findByConfigKey("favicon_url").map(SiteConfig::getConfigValue).orElse("");
            return new AppShellResolveResponse(SCHEMA_VERSION, true, origin.canonicalOrigin(), "myshop",
                    new AppShellResolveResponse.Brand(name, icon), null);
        }
        String suffix = domains.subsiteHostSuffix();
        if (suffix.isEmpty() || !origin.host().endsWith(suffix)) return denied(origin.canonicalOrigin(), "site_unavailable");
        String prefix = origin.host().substring(0, origin.host().length() - suffix.length());
        if (!prefix.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) return denied(origin.canonicalOrigin(), "site_unavailable");
        Subsite site = subsites.findByPrefix(prefix).orElse(null);
        if (site == null || !site.isEnabled()) return denied(origin.canonicalOrigin(), "site_unavailable");
        String icon = site.getFaviconUrl() == null ? "" : site.getFaviconUrl();
        return new AppShellResolveResponse(SCHEMA_VERSION, true, origin.canonicalOrigin(), "myshop",
                new AppShellResolveResponse.Brand(site.getName(), icon), null);
    }

    private Config readConfig() {
        String raw = configs.findByConfigKey(CONFIG_KEY).map(SiteConfig::getConfigValue).orElse(null);
        if (raw == null || raw.isBlank()) return new Config(false, List.of());
        try {
            Config parsed = mapper.readValue(raw, Config.class);
            if (parsed.rules() == null || parsed.rules().size() > MAX_RULES) return new Config(false, List.of());
            return parsed;
        } catch (Exception e) {
            return new Config(false, List.of());
        }
    }

    private AppShellConfigResponse toResponse(Config config) {
        return new AppShellConfigResponse(config.enabled(), config.rules().stream()
                .map(rule -> new AppShellConfigResponse.DomainRule(rule.pattern(), rule.siteType(), rule.enabled(), rule.note()))
                .toList());
    }

    private AppShellResolveResponse denied(String canonicalOrigin, String reason) {
        return new AppShellResolveResponse(SCHEMA_VERSION, false, canonicalOrigin, null, null, reason);
    }

    private BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }

    private record Config(boolean enabled, List<Rule> rules) {}
    private record Rule(String pattern, String siteType, boolean enabled, String note) {}
}
