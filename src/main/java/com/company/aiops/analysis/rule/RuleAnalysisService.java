package com.company.aiops.analysis.rule;

import com.company.aiops.analysis.evidence.ProviderCollectionResult;
import com.company.aiops.analysis.rule.model.RuleAnalysisEvidence;

/**
 * Runs deterministic rule pre-analysis on provider-backed evidence.
 */
public interface RuleAnalysisService {

    /**
     * Produces structured rule evidence for later compression, fallback, and debugging stages.
     */
    RuleAnalysisEvidence analyze(ProviderCollectionResult collectionResult);
}
