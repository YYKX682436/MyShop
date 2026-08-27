package com.myshop.service.system;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class NodeExporterMetricsParserTest {

    private final NodeExporterMetricsParser parser = new NodeExporterMetricsParser();

    @Test
    void parsesCpuCountersMemoryRootFilesystemBootAndLoad() {
        NodeExporterMetricsSnapshot snapshot = parser.parse("""
                # HELP node_cpu_seconds_total CPU seconds
                node_cpu_seconds_total{cpu="0",mode="user"} 10
                node_cpu_seconds_total{cpu="0",mode="idle"} 90
                node_cpu_seconds_total{cpu="1",mode="user"} 20
                node_cpu_seconds_total{cpu="1",mode="idle"} 80
                node_memory_MemTotal_bytes 1000
                node_memory_MemAvailable_bytes 400
                node_filesystem_size_bytes{device="/dev/vda1",fstype="ext4",mountpoint="/"} 5000
                node_filesystem_avail_bytes{device="/dev/vda1",fstype="ext4",mountpoint="/"} 1500
                node_filesystem_size_bytes{device="overlay",fstype="overlay",mountpoint="/"} 999999
                node_filesystem_avail_bytes{device="overlay",fstype="overlay",mountpoint="/"} 999998
                node_boot_time_seconds 1700000000
                node_load1 1.25
                """);

        assertEquals(200.0, snapshot.cpuTotalSeconds());
        assertEquals(170.0, snapshot.cpuIdleSeconds());
        assertEquals(2, snapshot.cpuCores());
        assertEquals(1000.0, snapshot.memoryTotalBytes());
        assertEquals(400.0, snapshot.memoryAvailableBytes());
        assertNotNull(snapshot.rootFilesystem());
        assertEquals(5000.0, snapshot.rootFilesystem().sizeBytes());
        assertEquals(1500.0, snapshot.rootFilesystem().availableBytes());
        assertEquals(1700000000.0, snapshot.bootTimeSeconds());
        assertEquals(1.25, snapshot.load1());
    }

    @Test
    void ignoresMalformedLinesAndMissingMetricsSafely() {
        NodeExporterMetricsSnapshot snapshot = parser.parse("""
                this is not prometheus
                node_load1 not-a-number
                node_memory_MemTotal_bytes NaN
                node_filesystem_size_bytes{fstype="tmpfs",mountpoint="/"} 10
                node_filesystem_avail_bytes{fstype="tmpfs",mountpoint="/"} 9
                node_cpu_seconds_total{cpu="0",mode="idle" 10
                """);

        assertNull(snapshot.cpuTotalSeconds());
        assertNull(snapshot.cpuIdleSeconds());
        assertNull(snapshot.memoryTotalBytes());
        assertNull(snapshot.rootFilesystem());
        assertNull(snapshot.load1());
    }

    @Test
    void emptyPayloadProducesAnUnavailableMetricSnapshot() {
        NodeExporterMetricsSnapshot snapshot = parser.parse("");

        assertNull(snapshot.cpuCores());
        assertNull(snapshot.memoryAvailableBytes());
        assertNull(snapshot.bootTimeSeconds());
    }
}
