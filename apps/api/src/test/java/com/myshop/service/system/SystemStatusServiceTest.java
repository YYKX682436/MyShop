package com.myshop.service.system;

import com.myshop.model.response.SystemStatusResponse;
import com.myshop.model.response.SystemStatusResponse.ServiceHealth;
import com.myshop.model.response.SystemStatusResponse.ServiceStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SystemStatusServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-27T04:00:00Z"), ZoneOffset.UTC);

    @Test
    void exporterUnavailableReturns200SafePayloadAndStillProbesServices() {
        NodeExporterClient exporter = mock(NodeExporterClient.class);
        DashboardHealthProbe health = mock(DashboardHealthProbe.class);
        when(exporter.scrape()).thenReturn(Optional.empty());
        when(health.probeWeb()).thenReturn(up());
        when(health.probeDatabase()).thenReturn(up());
        when(health.probeCaddy()).thenReturn(up());

        SystemStatusResponse response = new SystemStatusService(
                exporter, new NodeExporterMetricsParser(), health, CLOCK).getStatus();

        assertEquals(false, response.available());
        assertNull(response.cpu());
        assertNull(response.memory());
        assertNull(response.disk());
        assertEquals(ServiceStatus.UP, response.services().api().status());
        assertEquals(ServiceStatus.UP, response.services().database().status());
    }

    @Test
    void firstStatusRequestUsesTwoSamplesToCalculateCpu() {
        NodeExporterClient exporter = mock(NodeExporterClient.class);
        DashboardHealthProbe health = healthyHealthProbe();
        AtomicInteger waits = new AtomicInteger();
        when(exporter.scrape()).thenReturn(
                Optional.of(cpuMetrics(100, 70)),
                Optional.of(cpuMetrics(120, 80)));

        SystemStatusService service = new SystemStatusService(
                exporter, new NodeExporterMetricsParser(), health, CLOCK,
                ignored -> waits.incrementAndGet());

        SystemStatusResponse response = service.getStatus();

        assertEquals(true, response.available());
        assertEquals(50.0, response.cpu().usagePct());
        assertEquals(1, waits.get());
        verify(exporter, times(2)).scrape();
    }

    @Test
    void laterStatusRequestUsesCachedSampleWithoutSleepingAgain() {
        NodeExporterClient exporter = mock(NodeExporterClient.class);
        DashboardHealthProbe health = healthyHealthProbe();
        AtomicInteger waits = new AtomicInteger();
        when(exporter.scrape()).thenReturn(
                Optional.of(cpuMetrics(100, 70)),
                Optional.of(cpuMetrics(120, 80)),
                Optional.of(cpuMetrics(140, 90)));

        SystemStatusService service = new SystemStatusService(
                exporter, new NodeExporterMetricsParser(), health, CLOCK,
                ignored -> waits.incrementAndGet());

        SystemStatusResponse first = service.getStatus();
        SystemStatusResponse second = service.getStatus();

        assertEquals(50.0, first.cpu().usagePct());
        assertEquals(50.0, second.cpu().usagePct());
        assertEquals(1, waits.get());
        verify(exporter, times(3)).scrape();
    }

    @Test
    void oneDownServiceDoesNotSuppressOtherHealthResults() {
        NodeExporterClient exporter = mock(NodeExporterClient.class);
        DashboardHealthProbe health = mock(DashboardHealthProbe.class);
        when(exporter.scrape()).thenReturn(Optional.of("""
                node_cpu_seconds_total{cpu="0",mode="idle"} 50
                node_memory_MemTotal_bytes 100
                node_memory_MemAvailable_bytes 25
                node_filesystem_size_bytes{device="/dev/vda1",fstype="ext4",mountpoint="/"} 1000
                node_filesystem_avail_bytes{device="/dev/vda1",fstype="ext4",mountpoint="/"} 100
                node_boot_time_seconds 1700000000
                node_load1 0.5
                """));
        when(health.probeWeb()).thenReturn(new ServiceHealth(ServiceStatus.DOWN));
        when(health.probeDatabase()).thenReturn(up());
        when(health.probeCaddy()).thenThrow(new IllegalStateException("probe failed"));

        SystemStatusResponse response = new SystemStatusService(
                exporter, new NodeExporterMetricsParser(), health, CLOCK).getStatus();

        assertEquals(true, response.available());
        assertEquals(ServiceStatus.DOWN, response.services().web().status());
        assertEquals(ServiceStatus.UP, response.services().database().status());
        assertEquals(ServiceStatus.DOWN, response.services().caddy().status());
        assertEquals(75.0, response.memory().usagePct());
        assertEquals(90.0, response.disk().usagePct());
    }

    @Test
    void cpuUsageUsesCounterDeltasAndRejectsResets() {
        NodeExporterClient exporter = mock(NodeExporterClient.class);
        DashboardHealthProbe health = mock(DashboardHealthProbe.class);
        SystemStatusService service = new SystemStatusService(
                exporter, new NodeExporterMetricsParser(), health, CLOCK);

        assertNull(service.calculateCpuUsage(100.0, 70.0));
        assertEquals(75.0, service.calculateCpuUsage(120.0, 75.0));
        assertNull(service.calculateCpuUsage(10.0, 8.0));
    }

    @Test
    void invalidCounterPairDoesNotSeedCacheOrProduceCpuValue() {
        SystemStatusService service = new SystemStatusService(
                mock(NodeExporterClient.class), new NodeExporterMetricsParser(),
                healthyHealthProbe(), CLOCK, ignored -> { });

        assertNull(service.calculateCpuUsage(10.0, 11.0));
        assertNull(service.calculateCpuUsage(20.0, 10.0));
        assertEquals(50.0, service.calculateCpuUsage(30.0, 15.0));
    }

    @Test
    void concurrentFirstRequestsSerializeInitializationAndDoNotDoubleSleep() throws Exception {
        NodeExporterClient exporter = mock(NodeExporterClient.class);
        DashboardHealthProbe health = healthyHealthProbe();
        AtomicInteger scrapeCalls = new AtomicInteger();
        AtomicInteger waits = new AtomicInteger();
        CountDownLatch firstWaitEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstWait = new CountDownLatch(1);
        when(exporter.scrape()).thenAnswer(ignored -> Optional.of(
                switch (scrapeCalls.incrementAndGet()) {
                    case 1 -> cpuMetrics(100, 70);
                    case 2 -> cpuMetrics(120, 80);
                    default -> cpuMetrics(140, 90);
                }));

        SystemStatusService service = new SystemStatusService(
                exporter, new NodeExporterMetricsParser(), health, CLOCK, millis -> {
                    if (waits.incrementAndGet() == 1) {
                        firstWaitEntered.countDown();
                        if (!releaseFirstWait.await(2, TimeUnit.SECONDS)) {
                            throw new AssertionError("timed out releasing first CPU sample");
                        }
                    }
                });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<SystemStatusResponse> first = executor.submit(service::getStatus);
            assertTrue(firstWaitEntered.await(2, TimeUnit.SECONDS));
            Future<SystemStatusResponse> second = executor.submit(service::getStatus);
            releaseFirstWait.countDown();

            assertEquals(50.0, first.get(5, TimeUnit.SECONDS).cpu().usagePct());
            assertEquals(50.0, second.get(5, TimeUnit.SECONDS).cpu().usagePct());
            assertEquals(1, waits.get());
            assertEquals(3, scrapeCalls.get());
        } finally {
            executor.shutdownNow();
        }
    }

    private static DashboardHealthProbe healthyHealthProbe() {
        DashboardHealthProbe health = mock(DashboardHealthProbe.class);
        when(health.probeWeb()).thenReturn(up());
        when(health.probeDatabase()).thenReturn(up());
        when(health.probeCaddy()).thenReturn(up());
        return health;
    }

    private static String cpuMetrics(double total, double idle) {
        return """
                node_cpu_seconds_total{cpu="0",mode="user"} %s
                node_cpu_seconds_total{cpu="0",mode="idle"} %s
                """.formatted(total - idle, idle);
    }

    private static ServiceHealth up() {
        return new ServiceHealth(ServiceStatus.UP);
    }
}
