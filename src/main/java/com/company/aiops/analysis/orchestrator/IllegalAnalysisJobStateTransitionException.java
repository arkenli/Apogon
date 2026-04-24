package com.company.aiops.analysis.orchestrator;

import com.company.aiops.analysis.model.AnalysisJobStatus;

/**
 * Raised when code attempts to bypass the lifecycle graph described in the design document.
 */
public class IllegalAnalysisJobStateTransitionException extends IllegalStateException {

    public IllegalAnalysisJobStateTransitionException(
            String taskId,
            AnalysisJobStatus fromStatus,
            AnalysisJobStatus toStatus
    ) {
        super("Illegal analysis job transition for task %s: %s -> %s"
                .formatted(taskId, fromStatus, toStatus));
    }
}
