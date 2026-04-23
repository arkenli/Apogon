package com.company.aiops.analysis;

import java.time.Instant;

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
