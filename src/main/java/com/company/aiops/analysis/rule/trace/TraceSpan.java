package com.company.aiops.analysis.rule.trace;

import java.time.Instant;

/**
 * Normalized span extracted from interface-log records so rule analyzers can reason about parent
 * and child relationships without depending on provider DTO details.
 */
public record TraceSpan(
        String spanId,
        String parentSpanId,
        String serviceName,
        String interfaceName,
        boolean success,
        String errorCode,
        String errorMsg,
        long latencyMs,
        Instant timestamp
) {
}
