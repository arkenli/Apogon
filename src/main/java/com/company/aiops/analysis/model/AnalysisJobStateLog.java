package com.company.aiops.analysis.model;

import java.time.Instant;

/**
 * Immutable audit record written after one successful state-machine transition.
 */
public record AnalysisJobStateLog(
        String taskId,
        String alertId,
        AnalysisJobStatus fromStatus,
        AnalysisJobStatus toStatus,
        String stepName,
        String reason,
        long stateVersion,
        Instant createdAt
) {
}
