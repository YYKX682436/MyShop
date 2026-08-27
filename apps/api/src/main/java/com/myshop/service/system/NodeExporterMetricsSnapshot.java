package com.myshop.service.system;

/** Parsed values used by the system status calculator. Missing values are null. */
public record NodeExporterMetricsSnapshot(
        Double cpuTotalSeconds,
        Double cpuIdleSeconds,
        Integer cpuCores,
        Double memoryTotalBytes,
        Double memoryAvailableBytes,
        RootFilesystem rootFilesystem,
        Double bootTimeSeconds,
        Double load1
) {
    public record RootFilesystem(Double sizeBytes, Double availableBytes) { }
}
