package com.myshop.model.response;

public record AppShellResolveResponse(
        int schema_version,
        boolean allowed,
        String canonical_origin,
        String site_type,
        Brand brand,
        String reason
) {
    public record Brand(String site_name, String favicon_url) {}
}
