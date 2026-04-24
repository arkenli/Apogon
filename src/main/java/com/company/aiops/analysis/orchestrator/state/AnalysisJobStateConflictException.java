package com.company.aiops.analysis.orchestrator.state;

/**
 * Raised when a concurrent worker has already advanced the task state version.
 */
public class AnalysisJobStateConflictException extends IllegalStateException {

    public AnalysisJobStateConflictException(String taskId, long expectedStateVersion) {
        super("Optimistic lock failed for task %s at stateVersion %d"
                .formatted(taskId, expectedStateVersion));
    }
}
