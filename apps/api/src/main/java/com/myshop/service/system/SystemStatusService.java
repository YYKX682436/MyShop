package com.myshop.service.system;

import com.myshop.model.response.SystemStatusResponse;
import com.myshop.model.response.SystemStatusResponse.CpuMetric;
import com.myshop.model.response.SystemStatusResponse.ServiceHealth;
import com.myshop.model.response.SystemStatusResponse.ServiceStatus;
import com.myshop.model.response.SystemStatusResponse.ServicesHealth;
import com.myshop.model.response.SystemStatusResponse.SystemResourceMetric;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/** Best-effort, admin-only host status aggregation with per-probe isolation. */
@Service
public class SystemStatusService {

    private final NodeExporterClient nodeExporterClient;
    private final NodeExporterMetricsParser parser;
    private final DashboardHealthProbe healthProbe;
    private final Clock clock;
    private CpuSample previousCpuSample;

    @Autowired
    public SystemStatusService(NodeExporterClient nodeExporterClient,
                               DashboardHealthProbe healthProbe) {
        this(nodeExporterClient, new NodeExporterMetricsParser(), healthProbe, Clock.systemUTC());
    }

    SystemStatusService(NodeExporterClient nodeExporterClient,
                        NodeExporterMetricsParser parser,
                        DashboardHealthProbe healthProbe,
                        Clock clock) {
        this.nodeExporterClient = nodeExporterClient;
        this.parser = parser;
        this.healthProbe = healthProbe;
        this.clock = clock;
    }

    public SystemStatusResponse getStatus() {
        String sampledAt = Instant.now(clock).toString();
        Optional<String> exposition = scrapeSafely();
        boolean available = exposition.isPresent();
        var snapshot = available ? parseSafely(exposition.get()) : null;

        CpuMetric cpu = snapshot == null ? null : cpuMetric(snapshot);
        SystemResourceMetric memory = snapshot == null ? null : memoryMetric(snapshot);
        SystemResourceMetric disk = snapshot == null ? null : diskMetric(snapshot);
        Long uptimeSeconds = snapshot == null ? null : uptime(snapshot);
        Double load1m = snapshot == null ? null : snapshot.load1();

        ServicesHealth services = new ServicesHealth(
                new ServiceHealth(ServiceStatus.UP),
                probe(healthProbe::probeWeb),
                probe(healthProbe::probeDatabase),
                probe(healthProbe::probeCaddy));

        return new SystemStatusResponse(
                available, sampledAt, cpu, memory, disk, uptimeSeconds, load1m, services);
    }

    private Optional<String> scrapeSafely() {
        try {
            Optional<String> result = nodeExporterClient.scrape();
            return result == null ? Optional.empty() : result.filter(value -> !value.isBlank());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private NodeExporterMetricsSnapshot parseSafely(String exposition) {
        try {
            return parser.parse(exposition);
        } catch (RuntimeException e) {
            return new NodeExporterMetricsSnapshot(null, null, null, null, null, null, null, null);
        }
    }

    private CpuMetric cpuMetric(NodeExporterMetricsSnapshot snapshot) {
        return new CpuMetric(calculateCpuUsage(snapshot.cpuTotalSeconds(), snapshot.cpuIdleSeconds()),
                snapshot.cpuCores());
    }

    /**
     * Calculate usage from deltas of the cumulative counters. The first sample
     * intentionally has a null percentage because no valid interval exists.
     */
    Double calculateCpuUsage(Double totalSeconds, Double idleSeconds) {
        if (!validCounter(totalSeconds) || !validCounter(idleSeconds)) {
            return null;
        }
        synchronized (this) {
            CpuSample current = new CpuSample(totalSeconds, idleSeconds);
            CpuSample previous = previousCpuSample;
            previousCpuSample = current;
            if (previous == null) {
                return null;
            }
            double totalDelta = current.totalSeconds() - previous.totalSeconds();
            double idleDelta = current.idleSeconds() - previous.idleSeconds();
            if (totalDelta <= 0 || idleDelta < 0 || idleDelta > totalDelta) {
                return null;
            }
            double usage = (1 - idleDelta / totalDelta) * 100;
            return Math.max(0, Math.min(100, usage));
        }
    }

    private SystemResourceMetric memoryMetric(NodeExporterMetricsSnapshot snapshot) {
        return resourceMetric(snapshot.memoryTotalBytes(), snapshot.memoryAvailableBytes());
    }

    private SystemResourceMetric diskMetric(NodeExporterMetricsSnapshot snapshot) {
        if (snapshot.rootFilesystem() == null) {
            return null;
        }
        return resourceMetric(snapshot.rootFilesystem().sizeBytes(),
                snapshot.rootFilesystem().availableBytes());
    }

    private SystemResourceMetric resourceMetric(Double totalBytes, Double availableBytes) {
        if (!validNonNegative(totalBytes) || !validNonNegative(availableBytes)
                || availableBytes > totalBytes) {
            return null;
        }
        long total = Math.round(totalBytes);
        long available = Math.round(availableBytes);
        long used = Math.max(0, total - available);
        double usage = total == 0 ? 0 : (double) used / total * 100;
        return new SystemResourceMetric(total, used, available, Math.max(0, Math.min(100, usage)));
    }

    private Long uptime(NodeExporterMetricsSnapshot snapshot) {
        if (!validNonNegative(snapshot.bootTimeSeconds())) {
            return null;
        }
        long boot = Math.round(snapshot.bootTimeSeconds());
        long now = Instant.now(clock).getEpochSecond();
        return boot <= now ? now - boot : null;
    }

    private ServiceHealth probe(Supplier<ServiceHealth> action) {
        try {
            ServiceHealth health = action.get();
            return health == null ? new ServiceHealth(ServiceStatus.UNKNOWN) : health;
        } catch (RuntimeException e) {
            return new ServiceHealth(ServiceStatus.DOWN);
        }
    }

    private boolean validCounter(Double value) {
        return validNonNegative(value);
    }

    private boolean validNonNegative(Double value) {
        return value != null && Double.isFinite(value) && value >= 0;
    }

    private record CpuSample(double totalSeconds, double idleSeconds) { }
}
