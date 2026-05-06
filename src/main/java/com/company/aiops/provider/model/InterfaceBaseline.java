package com.company.aiops.provider.model;

import java.util.List;

/**
 * Historical baseline for the same interface in one comparable time window.
 *
 * <p>Request count and fail rate are single aggregated values for that window. Latency fields
 * describe the same window's average, P99 and max latency instead of percentile-of-percentile
 * aggregates.</p>
 */
public record InterfaceBaseline(
        Double requestCount,
        Double failRate,
        Long avgLatencyMs,
        Long p99LatencyMs,
        Long maxLatencyMs,
        List<ErrorCodeBaseline> commonErrorCodes,
        boolean sameAlertOccurred
) {

    public static InterfaceBaseline empty() {
        return new InterfaceBaseline(
                null,
                null,
                null,
                null,
                null,
                List.of(),
                false
        );
    }

    /**
     * Historical distribution for one error code.
     */
    public record ErrorCodeBaseline(
            String errorCode,
            long count,
            double share,
            List<String> sampleErrorMsgs
    ) {
    }
}
