package com.company.aiops.analysis.orchestrator;

import com.company.aiops.analysis.model.AnalysisJob;
import com.company.aiops.analysis.model.AnalysisJobStatus;

/**
 * Explicit state machine responsible for validating and persisting analysis task transitions.
 */
public interface AnalysisJobStateMachine {

    /**
     * Moves one task to the requested status after validating the transition graph.
     */
    AnalysisJob transition(String taskId, AnalysisJobStatus targetStatus, String stepName, String reason);

    /**
     * Convenience helper used by the async worker when an unexpected exception aborts execution.
     */
    AnalysisJob fail(String taskId, String stepName, String reason);
}
