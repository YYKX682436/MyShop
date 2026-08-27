package com.myshop.service.system;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small defensive parser for the Prometheus text exposition format emitted by
 * node-exporter. A bad line is ignored so one malformed metric cannot take
 * down the admin dashboard.
 */
public class NodeExporterMetricsParser {

    private static final Pattern SAMPLE_PATTERN = Pattern.compile(
            "^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\\{([^}]*)})?\\s+([^\\s]+)(?:\\s+\\d+)?\\s*$");
    private static final Pattern LABEL_PATTERN = Pattern.compile(
            "([a-zA-Z_][a-zA-Z0-9_]*)=\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Set<String> PSEUDO_FILESYSTEMS = Set.of(
            "autofs", "cgroup", "cgroup2", "configfs", "debugfs", "devpts",
            "devtmpfs", "fusectl", "hugetlbfs", "mqueue", "overlay", "proc",
            "pstore", "ramfs", "rootfs", "rpc_pipefs", "securityfs", "squashfs", "sysfs",
            "tmpfs", "tracefs");

    public NodeExporterMetricsSnapshot parse(String exposition) {
        Map<String, List<MetricSample>> samples = new HashMap<>();
        if (exposition == null || exposition.isBlank()) {
            return emptySnapshot();
        }

        for (String line : exposition.split("\\R")) {
            MetricSample sample = parseLine(line);
            if (sample != null) {
                samples.computeIfAbsent(sample.name(), ignored -> new ArrayList<>()).add(sample);
            }
        }

        List<MetricSample> cpuSamples = samples.getOrDefault("node_cpu_seconds_total", List.of());
        Double cpuTotal = sumCpu(cpuSamples, false);
        Double cpuIdle = sumCpu(cpuSamples, true);
        Set<String> cpuNames = new HashSet<>();
        for (MetricSample sample : cpuSamples) {
            String cpu = sample.labels().get("cpu");
            if (cpu != null) {
                cpuNames.add(cpu);
            }
        }

        return new NodeExporterMetricsSnapshot(
                cpuTotal,
                cpuIdle,
                cpuNames.isEmpty() ? null : cpuNames.size(),
                scalar(samples, "node_memory_MemTotal_bytes"),
                scalar(samples, "node_memory_MemAvailable_bytes"),
                rootFilesystem(samples),
                scalar(samples, "node_boot_time_seconds"),
                scalar(samples, "node_load1"));
    }

    private MetricSample parseLine(String line) {
        String candidate = line == null ? "" : line.trim();
        if (candidate.isEmpty() || candidate.startsWith("#")) {
            return null;
        }
        Matcher matcher = SAMPLE_PATTERN.matcher(candidate);
        if (!matcher.matches()) {
            return null;
        }
        try {
            double value = Double.parseDouble(matcher.group(3));
            if (!Double.isFinite(value)) {
                return null;
            }
            return new MetricSample(matcher.group(1), parseLabels(matcher.group(2)), value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Map<String, String> parseLabels(String rawLabels) {
        Map<String, String> labels = new HashMap<>();
        if (rawLabels == null || rawLabels.isBlank()) {
            return labels;
        }
        Matcher matcher = LABEL_PATTERN.matcher(rawLabels);
        while (matcher.find()) {
            labels.put(matcher.group(1), unescape(matcher.group(2)));
        }
        return labels;
    }

    private String unescape(String value) {
        return value.replace("\\\\", "\\")
                .replace("\\\"", "\"")
                .replace("\\n", "\n");
    }

    private Double sumCpu(List<MetricSample> samples, boolean idleOnly) {
        double total = 0;
        boolean found = false;
        for (MetricSample sample : samples) {
            if (sample.labels().get("cpu") == null) {
                continue;
            }
            if (idleOnly && !"idle".equals(sample.labels().get("mode"))) {
                continue;
            }
            total += sample.value();
            found = true;
        }
        return found ? total : null;
    }

    private Double scalar(Map<String, List<MetricSample>> samples, String name) {
        List<MetricSample> values = samples.get(name);
        return values == null || values.isEmpty() ? null : values.get(0).value();
    }

    private NodeExporterMetricsSnapshot.RootFilesystem rootFilesystem(
            Map<String, List<MetricSample>> samples) {
        List<MetricSample> sizes = samples.getOrDefault("node_filesystem_size_bytes", List.of());
        List<MetricSample> available = samples.getOrDefault("node_filesystem_avail_bytes", List.of());
        for (MetricSample size : sizes) {
            if (!isRootRealFilesystem(size)) {
                continue;
            }
            for (MetricSample free : available) {
                if (sameFilesystem(size, free)) {
                    return new NodeExporterMetricsSnapshot.RootFilesystem(size.value(), free.value());
                }
            }
        }
        return null;
    }

    private boolean isRootRealFilesystem(MetricSample sample) {
        String mountpoint = sample.labels().get("mountpoint");
        String fstype = sample.labels().get("fstype");
        String device = sample.labels().get("device");
        return "/".equals(mountpoint)
                && fstype != null
                && !PSEUDO_FILESYSTEMS.contains(fstype.toLowerCase())
                && (device == null || !"overlay".equalsIgnoreCase(device));
    }

    private boolean sameFilesystem(MetricSample left, MetricSample right) {
        return left.labels().equals(right.labels());
    }

    private NodeExporterMetricsSnapshot emptySnapshot() {
        return new NodeExporterMetricsSnapshot(null, null, null, null, null, null, null, null);
    }

    private record MetricSample(String name, Map<String, String> labels, double value) { }
}
