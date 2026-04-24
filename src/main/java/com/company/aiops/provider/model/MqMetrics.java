package com.company.aiops.provider.model;

import java.util.List;

/**
 * MQ pressure metrics for queues or topics related to the alerting service.
 */
public record MqMetrics(List<InstanceMetrics> instances) {

    public static MqMetrics empty() {
        return new MqMetrics(List.of());
    }

    /**
     * Per-instance MQ snapshot.
     */
    public record InstanceMetrics(
            String instanceId,
            long backlogSize,
            long publishLatencyMs,
            long consumeLatencyMs,
            long failedRequestCount
    ) {
    }
}
