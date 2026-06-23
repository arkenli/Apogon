package com.company.aiops.alert.model;

/**
 * 告警文本中提取出的触发指标值。
 */
public record AlertTriggerValues(
        Double requestCount,
        Double failRate,
        Long avgLatencyMs,
        Long p99LatencyMs,
        Long maxLatencyMs,
        Double failureCount,
        String logLevel,
        String logMessage,
        String normalizedLogQuery,
        String thresholdExpression,
        String rawMetricValueText
) {
}
