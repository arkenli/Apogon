package com.company.aiops.provider.model;

import java.time.Instant;

/**
 * Runtime log sample associated with a trace or service during the alert window.
 */
public record RuntimeLogEntry(
        String serviceName,
        String cluster,
        String instanceId,
        String traceId,
        String spanId,
        String level,
        String logger,
        String threadName,
        String message,
        String exceptionType,
        String stackTop,
        Instant timestamp
) {
}
