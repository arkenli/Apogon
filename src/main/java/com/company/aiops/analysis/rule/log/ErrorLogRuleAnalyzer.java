package com.company.aiops.analysis.rule.log;

import com.company.aiops.analysis.evidence.ProviderCollectionResult;
import com.company.aiops.analysis.rule.model.RuleAnalysisEvidence;

/**
 * Rule analyzer for service runtime error-log alerts.
 */
public interface ErrorLogRuleAnalyzer {

    /**
     * Produces deterministic phase-5 evidence for service error-log alerts.
     */
    RuleAnalysisEvidence analyze(ProviderCollectionResult collectionResult);
}
