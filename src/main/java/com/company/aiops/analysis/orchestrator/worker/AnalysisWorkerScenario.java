package com.company.aiops.analysis.orchestrator.worker;

/**
 * Worker scenarios used to validate lifecycle branches before provider, rule, and LLM integrations
 * land in later phases.
 */
public enum AnalysisWorkerScenario {
    RULE_BASED_ONLY,
    LLM_ASSISTED_SUCCESS,
    LLM_FALLBACK_TIMEOUT
}
