package com.company.aiops.analysis.orchestrator.worker;

/**
 * Worker scenarios used by tests to simulate downstream rule/LLM branches after provider
 * collection has finished.
 */
public enum AnalysisWorkerScenario {
    RULE_BASED_ONLY,
    LLM_ASSISTED_SUCCESS,
    LLM_FALLBACK_TIMEOUT
}
