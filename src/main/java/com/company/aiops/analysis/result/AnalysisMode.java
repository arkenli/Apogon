package com.company.aiops.analysis.result;

/**
 * Indicates which analysis path produced the final conclusion.
 */
public enum AnalysisMode {
    LLM_ASSISTED,
    RULE_BASED_DEGRADED,
    RULE_BASED_ONLY
}
