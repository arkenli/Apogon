package com.company.aiops.provider.model;

import java.time.Instant;

/**
 * Runtime log sample associated with a trace or service during the alert window.
 */
public record RuntimeLogEntry(
        String serviceName,
        String traceId,
        String level,
        String message,
        Instant timestamp
) {
}
