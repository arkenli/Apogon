package com.company.aiops.provider.model;

import java.util.List;

/**
 * Aggregated database metrics for mapped database instances.
 */
public record DatabaseMetrics(
        List<InstanceMetrics> instances,
        List<SlowSqlSample> slowSqlSamples
) {

    public static DatabaseMetrics empty() {
        return new DatabaseMetrics(List.of(), List.of());
    }

    /**
     * Per-instance database snapshot used by later rule analyzers.
     */
    public record InstanceMetrics(
            String instanceId,
            double cpuUsage,
            double memoryUsage,
            double connectionUsage,
            long activeConnections,
            long slowSqlCount,
            double diskUsage,
            double networkInMbps,
            double networkOutMbps,
            long maxStorageWriteLatencyMs,
            long rowLockCount,
            long rowLockWaitCount,
            long slowLogCount
    ) {
    }

    /**
     * Slow SQL fingerprint retained for debugging and later evidence compression.
     */
    public record SlowSqlSample(
            String instanceId,
            String fingerprint,
            long latencyMs
    ) {
    }
}
