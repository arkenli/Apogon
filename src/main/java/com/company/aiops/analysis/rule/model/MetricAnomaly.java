package com.company.aiops.analysis.rule.model;

/**
 * One deterministic metric anomaly detected from provider snapshots.
 */
public record MetricAnomaly(
        String evidenceId,
        String resourceType,
        String resourceId,
        String metric,
        double value,
        Double referenceValue,
        String severity,
        String description
) {
}
