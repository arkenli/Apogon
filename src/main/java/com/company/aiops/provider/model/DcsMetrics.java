package com.company.aiops.provider.model;

import java.util.List;

/**
 * Redis/DCS metrics for cache instances related to the service.
 */
public record DcsMetrics(List<InstanceMetrics> instances) {

    public static DcsMetrics empty() {
        return new DcsMetrics(List.of());
    }

    /**
     * Per-instance DCS snapshot.
     */
    public record InstanceMetrics(
            String instanceId,
            double cpuUsage,
            double memoryUsage,
            double tpsPeak,
            double slowQueryRatio,
            long p99LatencyMs,
            long avgLatencyMs,
            long clientFailureCount
    ) {
    }
}
