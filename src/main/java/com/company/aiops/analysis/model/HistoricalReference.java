package com.company.aiops.analysis.model;

import java.time.Instant;

/**
 * Summary of the latest matching historical result outside the dedupe window.
 */
public record HistoricalReference(
        boolean matched,
        String alertId,
        String taskId,
        AnalysisMode analysisMode,
        String summary,
        double overallConfidence,
        Instant generatedAt
) {
}
