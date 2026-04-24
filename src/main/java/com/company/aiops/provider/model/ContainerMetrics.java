package com.company.aiops.provider.model;

import java.util.List;

/**
 * Container/resource metrics for the alerting service itself.
 */
public record ContainerMetrics(
        double averageCpuUsage,
        double maxCpuUsage,
        double averageMemoryUsage,
        double maxMemoryUsage,
        int startingInstanceCount,
        int failedInstanceCount,
        long youngGcCount,
        long fullGcCount,
        List<InstanceMetrics> instances
) {

    public static ContainerMetrics empty() {
        return new ContainerMetrics(0.0, 0.0, 0.0, 0.0, 0, 0, 0L, 0L, List.of());
    }

    /**
     * Per-instance snapshot used to spot a single noisy instance.
     */
    public record InstanceMetrics(
            String instanceId,
            double cpuUsage,
            double memoryUsage,
            boolean starting,
            boolean failed
    ) {
    }
}
