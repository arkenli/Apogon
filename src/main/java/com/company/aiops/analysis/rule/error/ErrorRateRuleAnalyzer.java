package com.company.aiops.analysis.rule.error;

import com.company.aiops.analysis.evidence.ProviderCollectionResult;
import com.company.aiops.analysis.rule.model.RuleAnalysisEvidence;

/**
 * Rule analyzer for RPC error-rate alerts.
 */
public interface ErrorRateRuleAnalyzer {

    /**
     * Produces deterministic phase-5 evidence for error-rate alerts.
     */
    RuleAnalysisEvidence analyze(ProviderCollectionResult collectionResult);
}
