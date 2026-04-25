package com.company.aiops.llm.validator;

import com.company.aiops.analysis.bundle.CompressedEvidenceBundle;
import com.company.aiops.llm.schema.LlmAnalysisOutput;

/**
 * Parses and validates one raw model response.
 */
public interface LlmOutputValidator {

    /**
     * Returns the validated payload or throws when the model output cannot be trusted.
     */
    LlmAnalysisOutput validate(String rawContent, CompressedEvidenceBundle compressedEvidence);
}
