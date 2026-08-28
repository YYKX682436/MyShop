package com.myshop.service.system;

import com.myshop.model.response.SystemStatusResponse;
import com.myshop.model.response.SystemStatusResponse.CpuMetric;
import com.myshop.model.response.SystemStatusResponse.ServiceHealth;
import com.myshop.model.response.SystemStatusResponse.ServiceStatus;
import com.myshop.model.response.SystemStatusResponse.ServicesHealth;
import com.myshop.model.response.SystemStatusResponse.SystemResourceMetric;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/** Best-effort, admin-only host status aggregation with per-probe isolation. */
@Service
public class SystemStatusService {

    private static final long INITIAL_CPU_SAMPLE_DELAY_MILLIS = 250;

    private final NodeExporterClient nodeExporterClient;
    private final NodeExporterMetricsParser parser;
    private final DashboardHealthProbe healthProbe;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Object cpuSampleLock = new Object();
    private CpuSample previousCpuSample;

    @Autowired
    public SystemStatusService(NodeExporterClient nodeExporterClient,
                               DashboardHealthProbe healthProbe,
                               @Value("${dashboard.data-mountpoint:/data}") String dataMountpoint) {
        this(nodeExporterClient, new NodeExporterMetricsParser(dataMountpoint), healthProbe,
                Clock.systemUTC(), Thread::sleep);
    }

    SystemStatusService(NodeExporterClient nodeExporterClient,
                        NodeExporterMetricsParser parser,
                        DashboardHealthProbe healthProbe,
                        Clock clock) {
        this(nodeExporterClient, parser, healthProbe, clock, Thread::sleep);
    }

    SystemStatusService(NodeExporterClient nodeExporterClient,
                        NodeExporterMetricsParser parser,
                        DashboardHealthProbe healthProbe,
                        Clock clock,
                        Sleeper sleeper) {
        this.nodeExporterClient = nodeExporterClient;
        this.parser = parser;
        this.healthProbe = healthProbe;
        this.clock = clock;
        this.sleeper = sleeper == null ? Thread::sleep : sleeper;
    }

    public SystemStatusResponse getStatus() {
        String sampledAt = Instant.now(clock).toString();
        HostMetrics hostMetrics = scrapeHostMetrics();

        ServicesHealth services = new ServicesHealth(
                new ServiceHealth(ServiceStatus.UP),
                probe(healthProbe::probeWeb),
                probe(healthProbe::probeDatabase),
                probe(healthProbe::probeCaddy));

        return new SystemStatusResponse(
                hostMetrics.available(), sampledAt, hostMetrics.cpu(), hostMetrics.memory(),
                hostMetrics.disk(), hostMetrics.dataDisk(), hostMetrics.uptimeSeconds(),
                hostMetrics.load1m(), services);
    }

    private HostMetrics scrapeHostMetrics() {
        // Keep the first scrape, the optional wait, and the cache update under
        // one lock. Concurrent first requests must not race with each other's
        // counter samples or make one request compare against stale data.
        synchronized (cpuSampleLock) {
            Optional<String> exposition = scrapeSafely();
            if (exposition.isEmpty()) {
                return HostMetrics.unavailable();
            }
            NodeExporterMetricsSnapshot snapshot = parseSafely(exposition.get());
            CpuMetric cpu = cpuMetric(snapshot);
            return new HostMetrics(
                    true,
                    cpu,
                    memoryMetric(snapshot),
                    diskMetric(snapshot),
                    dataDiskMetric(snapshot),
                    uptime(snapshot),
                    snapshot.load1());
        }
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
            return new NodeExporterMetricsSnapshot(null, null, null, null, null, null, null, null, null);
        }
    }

    private CpuMetric cpuMetric(NodeExporterMetricsSnapshot snapshot) {
        CpuSample current = cpuSample(snapshot);
        if (current == null) {
            return new CpuMetric(null, snapshot.cpuCores());
        }
        if (previousCpuSample == null) {
            return initialCpuMetric(snapshot, current);
        }
        return new CpuMetric(recordCpuSample(current), snapshot.cpuCores());
    }

    private CpuMetric initialCpuMetric(NodeExporterMetricsSnapshot firstSnapshot,
                                       CpuSample firstSample) {
        // Retain the first valid sample even if the follow-up scrape fails;
        // the next request can then calculate a normal delta without sleeping.
        previousCpuSample = firstSample;
        if (!waitForSecondCpuSample()) {
            return new CpuMetric(null, firstSnapshot.cpuCores());
        }

        Optional<String> secondExposition = scrapeSafely();
        if (secondExposition.isEmpty()) {
            return new CpuMetric(null, firstSnapshot.cpuCores());
        }
        NodeExporterMetricsSnapshot secondSnapshot = parseSafely(secondExposition.get());
        CpuSample secondSample = cpuSample(secondSnapshot);
        if (secondSample == null) {
            return new CpuMetric(null, firstSnapshot.cpuCores());
        }

        previousCpuSample = secondSample;
        Integer cores = secondSnapshot.cpuCores() != null
                ? secondSnapshot.cpuCores()
                : firstSnapshot.cpuCores();
        return new CpuMetric(cpuUsage(firstSample, secondSample), cores);
    }

    private boolean waitForSecondCpuSample() {
        try {
            sleeper.sleep(INITIAL_CPU_SAMPLE_DELAY_MILLIS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Calculate usage from deltas of the cumulative counters. */
    Double calculateCpuUsage(Double totalSeconds, Double idleSeconds) {
        CpuSample current = cpuSample(totalSeconds, idleSeconds);
        if (current == null) {
            return null;
        }
        synchronized (cpuSampleLock) {
            return recordCpuSample(current);
        }
    }

    private CpuSample cpuSample(NodeExporterMetricsSnapshot snapshot) {
        return snapshot == null
                ? null
                : cpuSample(snapshot.cpuTotalSeconds(), snapshot.cpuIdleSeconds());
    }

    private CpuSample cpuSample(Double totalSeconds, Double idleSeconds) {
        if (!validCounter(totalSeconds) || !validCounter(idleSeconds)
                || idleSeconds > totalSeconds) {
            return null;
        }
        return new CpuSample(totalSeconds, idleSeconds);
    }

    private Double recordCpuSample(CpuSample current) {
        CpuSample previous = previousCpuSample;
        previousCpuSample = current;
        return cpuUsage(previous, current);
    }

    private Double cpuUsage(CpuSample previous, CpuSample current) {
        if (previous == null) {
            return null;
        }
        double totalDelta = current.totalSeconds() - previous.totalSeconds();
        double idleDelta = current.idleSeconds() - previous.idleSeconds();
        if (totalDelta <= 0 || idleDelta < 0 || idleDelta > totalDelta) {
            return null;
        }
        double usage = (1 - idleDelta / totalDelta) * 100;
        return Double.isFinite(usage) ? Math.max(0, Math.min(100, usage)) : null;
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

    private SystemResourceMetric dataDiskMetric(NodeExporterMetricsSnapshot snapshot) {
        if (snapshot.dataFilesystem() == null) {
            return null;
        }
        return resourceMetric(snapshot.dataFilesystem().sizeBytes(),
                snapshot.dataFilesystem().availableBytes());
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

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private record HostMetrics(boolean available,
                               CpuMetric cpu,
                               SystemResourceMetric memory,
                               SystemResourceMetric disk,
                               SystemResourceMetric dataDisk,
                               Long uptimeSeconds,
                               Double load1m) {
        private static HostMetrics unavailable() {
            return new HostMetrics(false, null, null, null, null, null, null);
        }
    }

    private record CpuSample(double totalSeconds, double idleSeconds) { }
}
