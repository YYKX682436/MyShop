package com.myshop.model.response;

import java.util.List;

public record AppShellConfigResponse(boolean enabled, List<DomainRule> rules) {
    public record DomainRule(String domain_pattern, String site_type, boolean enabled, String note) {}
}
