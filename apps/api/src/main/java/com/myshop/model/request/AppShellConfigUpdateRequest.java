package com.myshop.model.request;

import java.util.List;

public record AppShellConfigUpdateRequest(boolean enabled, List<DomainRule> rules) {
    public record DomainRule(String domain_pattern, String site_type, boolean enabled, String note) {}
}
