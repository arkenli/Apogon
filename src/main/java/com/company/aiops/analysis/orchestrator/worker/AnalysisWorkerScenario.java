package com.company.aiops.analysis.orchestrator.worker;

/**
 * Worker scenarios used by tests to simulate downstream rule/LLM branches after provider
 * collection has finished. The runtime default only uses {@code RULE_BASED_ONLY} and
 * {@code LLM_FALLBACK_TIMEOUT}; {@code LLM_ASSISTED_SUCCESS} remains a stage-8 test hook.
 */
public enum AnalysisWorkerScenario {
    RULE_BASED_ONLY,
    LLM_ASSISTED_SUCCESS,
    LLM_FALLBACK_TIMEOUT
}
