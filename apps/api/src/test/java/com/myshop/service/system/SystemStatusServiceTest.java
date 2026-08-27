package com.myshop.service.system;

import com.myshop.model.response.SystemStatusResponse;
import com.myshop.model.response.SystemStatusResponse.ServiceHealth;
import com.myshop.model.response.SystemStatusResponse.ServiceStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
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

    private static ServiceHealth up() {
        return new ServiceHealth(ServiceStatus.UP);
    }
}
