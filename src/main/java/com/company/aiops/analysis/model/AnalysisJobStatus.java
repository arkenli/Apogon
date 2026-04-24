package com.company.aiops.analysis.model;

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
    SKIPPED_DUPLICATE
}
