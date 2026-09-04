package com.myshop.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A best-effort IP estimate, deliberately separate from device/GPS location. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record IpLocationResponse(
        String country,
        String province,
        String city,
        String isp,
        String displayName,
        boolean resolved
) {
    public static IpLocationResponse unknown() {
        return new IpLocationResponse(null, null, null, null, "未知", false);
    }

    public static IpLocationResponse reserved() {
        return new IpLocationResponse(null, null, null, null, "内网/保留地址", false);
    }
}
