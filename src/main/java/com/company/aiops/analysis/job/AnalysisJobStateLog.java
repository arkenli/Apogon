package com.company.aiops.analysis.job;

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
