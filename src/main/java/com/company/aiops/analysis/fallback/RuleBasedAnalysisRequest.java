package com.company.aiops.analysis.fallback;

import com.company.aiops.analysis.bundle.CompressedEvidenceBundle;
import com.company.aiops.analysis.evidence.ProviderCollectionResult;
import com.company.aiops.analysis.result.AnalysisMode;
import com.company.aiops.analysis.rule.model.RuleAnalysisEvidence;

import java.util.Objects;

/**
 * Input contract for one stage-7 rule-based result-generation attempt.
 *
 * <p>{@code ruleAnalysis} and {@code compressedEvidence} are optional because critical provider
 * failures may force fallback before phase-5 and phase-6 complete.</p>
 */
public record RuleBasedAnalysisRequest(
        String taskId,
        ProviderCollectionResult collection,
        RuleAnalysisEvidence ruleAnalysis,
        CompressedEvidenceBundle compressedEvidence,
        AnalysisMode analysisMode,
        String llmStatus,
        String degradedReason
) {

    public RuleBasedAnalysisRequest {
        taskId = Objects.requireNonNull(taskId, "taskId");
        collection = Objects.requireNonNull(collection, "collection");
        analysisMode = Objects.requireNonNull(analysisMode, "analysisMode");
        if (analysisMode == AnalysisMode.LLM_ASSISTED) {
            throw new IllegalArgumentException("RuleBasedFallbackAnalyzer does not build LLM_ASSISTED results.");
        }
    }

    /**
     * Convenience flag for branches that should be marked as degraded because the normal LLM path
     * could not complete.
     */
    public boolean degradedMode() {
        return analysisMode == AnalysisMode.RULE_BASED_DEGRADED;
    }
}
