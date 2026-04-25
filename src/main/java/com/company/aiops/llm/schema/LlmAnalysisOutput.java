package com.company.aiops.llm.schema;

import com.company.aiops.analysis.result.ImpactAssessment;
import com.company.aiops.analysis.result.Recommendation;

import java.util.List;
import java.util.Objects;

/**
 * Validated raw LLM payload before the outer analysis-result envelope is added.
 */
public record LlmAnalysisOutput(
        String summary,
        ImpactAssessment impact,
        List<LlmRootCauseOutput> rootCauses,
        List<Recommendation> recommendations,
        double overallConfidence,
        List<String> insufficientEvidence
) {

    public LlmAnalysisOutput {
        summary = Objects.requireNonNull(summary, "summary");
        impact = Objects.requireNonNull(impact, "impact");
        rootCauses = List.copyOf(Objects.requireNonNull(rootCauses, "rootCauses"));
        recommendations = List.copyOf(Objects.requireNonNull(recommendations, "recommendations"));
        insufficientEvidence = List.copyOf(Objects.requireNonNull(insufficientEvidence, "insufficientEvidence"));
    }

    /**
     * Raw root-cause item emitted by the model before the service adds source metadata.
     */
    public record LlmRootCauseOutput(
            String causeType,
            String description,
            double confidence,
            List<String> evidenceIds,
            String reasoning
    ) {

        public LlmRootCauseOutput {
            causeType = Objects.requireNonNull(causeType, "causeType");
            description = Objects.requireNonNull(description, "description");
            evidenceIds = List.copyOf(Objects.requireNonNull(evidenceIds, "evidenceIds"));
            reasoning = Objects.requireNonNull(reasoning, "reasoning");
        }
    }
}
