package com.company.aiops.provider.model;

import java.time.Instant;

/**
 * One RPC/interface log line normalized into stable fields for later trace sampling and rule
 * analysis.
 */
public record InterfaceLogEntry(
        String serviceName,
        String interfaceName,
        String traceId,
        String spanId,
        String parentSpanId,
        String uid,
        boolean success,
        String errorCode,
        String errorMsg,
        long latencyMs,
        Instant timestamp
) {
}
