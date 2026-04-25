package com.company.aiops.analysis.result;

import com.company.aiops.alert.model.AlertCategory;

import java.time.Instant;
import java.util.List;

/**
 * Final structured output returned to callers and persisted for later lookup.
 */
public record AnalysisResult(
        String taskId,
        String alertId,
        AlertCategory category,
        AnalysisMode analysisMode,
        String summary,
        ImpactAssessment impact,
        List<RootCauseHypothesis> rootCauses,
        List<Recommendation> recommendations,
        List<EvidenceReference> evidenceRefs,
        List<MissingEvidence> missingEvidence,
        List<String> insufficientEvidence,
        HistoricalReference historicalReference,
        boolean llmUsed,
        String llmStatus,
        boolean degraded,
        boolean degradedByProviderFailure,
        String degradedReason,
        String ruleEngineVersion,
        double overallConfidence,
        Instant generatedAt
) {
}
