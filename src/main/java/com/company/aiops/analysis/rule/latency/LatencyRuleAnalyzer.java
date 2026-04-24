package com.company.aiops.analysis.rule.latency;

import com.company.aiops.analysis.evidence.ProviderCollectionResult;
import com.company.aiops.analysis.rule.model.RuleAnalysisEvidence;

/**
 * Rule analyzer for RPC latency alerts.
 */
public interface LatencyRuleAnalyzer {

    /**
     * Produces deterministic phase-5 evidence for latency alerts.
     */
    RuleAnalysisEvidence analyze(ProviderCollectionResult collectionResult);
}
