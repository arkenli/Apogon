package com.company.aiops.analysis.query.model;

import com.company.aiops.analysis.result.AnalysisMode;
import com.company.aiops.analysis.result.AnalysisResult;
import com.company.aiops.analysis.result.EvidenceReference;
import com.company.aiops.analysis.result.HistoricalReference;
import com.company.aiops.analysis.result.ImpactAssessment;
import com.company.aiops.analysis.result.MissingEvidence;
import com.company.aiops.analysis.result.Recommendation;
import com.company.aiops.analysis.result.RootCauseHypothesis;

import java.time.Instant;
import java.util.List;

/**
 * Stable result payload returned by query APIs.
 *
 * <p>The view is intentionally separate from {@link AnalysisResult} so query-time enrichment can
 * override specific fields such as {@code historicalReference} without mutating the persisted
 * domain record.</p>
 */
public record AnalysisResultView(
        AnalysisMode analysisMode,
        boolean degraded,
        String degradedReason,
        boolean degradedByProviderFailure,
        List<MissingEvidence> missingEvidence,
        HistoricalReference historicalReference,
        List<RootCauseHypothesis> rootCauses,
        List<Recommendation> recommendations,
        String summary,
        ImpactAssessment impact,
        List<EvidenceReference> evidenceRefs,
        List<String> insufficientEvidence,
        boolean llmUsed,
        String llmStatus,
        String ruleEngineVersion,
        double overallConfidence,
        Instant generatedAt
) {

    /**
     * Creates the API payload and applies the computed historical reference when the stored result
     * does not already contain one.
     */
    public static AnalysisResultView from(AnalysisResult analysisResult, HistoricalReference historicalReference) {
        return new AnalysisResultView(
                analysisResult.analysisMode(),
                analysisResult.degraded(),
                analysisResult.degradedReason(),
                analysisResult.degradedByProviderFailure(),
                analysisResult.missingEvidence(),
                historicalReference,
                analysisResult.rootCauses(),
                analysisResult.recommendations(),
                analysisResult.summary(),
                analysisResult.impact(),
                analysisResult.evidenceRefs(),
                analysisResult.insufficientEvidence(),
                analysisResult.llmUsed(),
                analysisResult.llmStatus(),
                analysisResult.ruleEngineVersion(),
                analysisResult.overallConfidence(),
                analysisResult.generatedAt()
        );
    }
}
