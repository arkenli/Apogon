package com.company.aiops.analysis.context;

import java.time.Instant;

/**
 * Closed time range used when querying metrics, logs, traces, and baselines.
 */
public record TimeWindow(
        Instant startTime,
        Instant endTime
) {
}
