package com.company.aiops.analysis.bundle;

import com.company.aiops.analysis.evidence.ProviderCollectionResult;
import com.company.aiops.analysis.rule.model.RuleAnalysisEvidence;

/**
 * Compresses raw provider evidence and deterministic rule output into a bounded LLM input bundle.
 */
public interface EvidenceCompressor {

    /**
     * Produces a structured, size-bounded bundle that can be persisted and later passed to LLM or
     * rule-fallback stages without re-querying providers.
     */
    CompressedEvidenceBundle compress(
            ProviderCollectionResult collectionResult,
            RuleAnalysisEvidence ruleAnalysisEvidence
    );
}
