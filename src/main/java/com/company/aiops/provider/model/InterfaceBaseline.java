package com.company.aiops.provider.model;

import java.util.List;

/**
 * Historical baseline for the same interface in a comparable time window.
 */
public record InterfaceBaseline(
        Double requestCountAvg,
        Double requestCountP99,
        Double requestCountMax,
        Double failRateAvg,
        Double failRateP99,
        Double failRateMax,
        Long avgLatencyAvgMs,
        Long avgLatencyP99Ms,
        Long avgLatencyMaxMs,
        Long p99LatencyAvgMs,
        Long p99LatencyP99Ms,
        Long p99LatencyMaxMs,
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
                null,
                null,
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
