package com.company.aiops.analysis;

import com.company.aiops.alert.AlertCategory;

import java.time.Instant;
import java.util.List;

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
        double overallConfidence,
        Instant generatedAt
) {
}
