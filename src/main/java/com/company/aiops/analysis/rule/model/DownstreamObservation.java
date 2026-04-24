package com.company.aiops.analysis.rule.model;

/**
 * Aggregated downstream observation shared by error-rate and latency analyzers.
 */
public record DownstreamObservation(
        String evidenceId,
        String serviceName,
        String interfaceName,
        long traceCount,
        double share,
        boolean failureRelated,
        long averageLatencyMs,
        String description
) {
}
