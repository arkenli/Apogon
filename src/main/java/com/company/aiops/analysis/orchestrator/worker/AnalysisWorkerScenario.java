package com.company.aiops.analysis.orchestrator.worker;

/**
 * Worker scenarios used by tests to switch between the rule-only path and the real stage-8 LLM
 * path after provider collection has finished.
 */
public enum AnalysisWorkerScenario {
    RULE_BASED_ONLY,
    USE_CONFIGURED_LLM
}
