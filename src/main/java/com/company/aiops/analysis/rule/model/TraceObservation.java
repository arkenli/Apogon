package com.company.aiops.analysis.rule.model;

/**
 * Trace-level observation derived from span topology, for example first failed span or slowest
 * span.
 */
public record TraceObservation(
        String evidenceId,
        String traceId,
        String observationType,
        String spanId,
        String serviceName,
        String interfaceName,
        String errorCode,
        boolean success,
        long latencyMs,
        String description
) {
}
