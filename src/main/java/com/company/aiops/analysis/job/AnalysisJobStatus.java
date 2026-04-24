package com.company.aiops.analysis.job;

/**
 * States defined by the analysis job state machine in the design document.
 */
public enum AnalysisJobStatus {
    PENDING,
    RUNNING,
    COLLECTING_EVIDENCE,
    RULE_ANALYZING,
    COMPRESSING_EVIDENCE,
    LLM_ANALYZING,
    RULE_FALLBACK_ANALYZING,
    GENERATING_RESULT,
    SUCCESS,
    FAILED,
    DEGRADED_SUCCESS,
    SKIPPED_DUPLICATE;

    /**
     * Terminal states must reject any further transitions so duplicate workers cannot roll a task
     * back into an earlier lifecycle step.
     */
    public boolean isTerminal() {
        return switch (this) {
            case SUCCESS, FAILED, DEGRADED_SUCCESS, SKIPPED_DUPLICATE -> true;
            default -> false;
        };
    }
}
