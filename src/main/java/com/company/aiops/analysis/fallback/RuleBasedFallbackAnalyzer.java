package com.company.aiops.analysis.fallback;

import com.company.aiops.analysis.result.AnalysisResult;

/**
 * Builds the final structured output for stage-7 rule-only and degraded fallback paths.
 */
public interface RuleBasedFallbackAnalyzer {

    /**
     * Converts collected evidence into a final {@link AnalysisResult} without relying on an LLM
     * response.
     */
    AnalysisResult analyze(RuleBasedAnalysisRequest request);
}
