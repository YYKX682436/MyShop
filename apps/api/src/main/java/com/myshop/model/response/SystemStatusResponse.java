package com.myshop.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Admin-only host observability response. Null metrics mean they were unavailable. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SystemStatusResponse(
        boolean available,
        String sampledAt,
        CpuMetric cpu,
        SystemResourceMetric memory,
        SystemResourceMetric disk,
        Long uptimeSeconds,
        Double load1m,
        ServicesHealth services
) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CpuMetric(Double usagePct, Integer cores) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SystemResourceMetric(Long totalBytes, Long usedBytes, Long availableBytes, Double usagePct) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ServicesHealth(ServiceHealth api, ServiceHealth web,
                                 ServiceHealth database, ServiceHealth caddy) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ServiceHealth(ServiceStatus status) { }

    public enum ServiceStatus {
        UP,
        DOWN,
        UNKNOWN
    }
}
