package com.company.aiops.alert;

public record AlertTriggerValues(
        Double requestCount,
        Double failRate,
        Long avgLatencyMs,
        Long p99LatencyMs,
        Long maxLatencyMs,
        String thresholdExpression,
        String rawMetricValueText
) {
}
