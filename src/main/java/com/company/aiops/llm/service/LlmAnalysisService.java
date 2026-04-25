package com.company.aiops.llm.service;

import com.company.aiops.analysis.bundle.CompressedEvidenceBundle;
import com.company.aiops.analysis.evidence.ProviderCollectionResult;
import com.company.aiops.analysis.result.AnalysisResult;

/**
 * Executes the complete stage-8 LLM branch and returns the final LLM-assisted result.
 */
public interface LlmAnalysisService {

    /**
     * Builds the prompt, calls the configured client, validates the output, and assembles the
     * persisted/result-facing {@link AnalysisResult}.
     */
    AnalysisResult analyze(String taskId, ProviderCollectionResult collection, CompressedEvidenceBundle compressedEvidence);
}
