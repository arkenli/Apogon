package com.company.aiops.analysis.rule.model;

import com.company.aiops.analysis.result.EvidenceReference;

import java.util.List;
import java.util.Optional;

/**
 * Structured phase-5 output that captures deterministic findings and reusable evidence indexes.
 *
 * <p>Later phases can reuse this object for evidence compression, rule-only result generation, and
 * LLM fallback without re-running provider queries.</p>
 */
public record RuleAnalysisEvidence(
        List<ErrorCodeAggregate> errorCodeDistribution,
        List<UidAggregate> uidDistribution,
        List<TraceObservation> traceObservations,
        List<DownstreamObservation> downstreamObservations,
        List<MetricAnomaly> metricAnomalies,
        List<RuleFinding> ruleFindings,
        List<CandidateRootCause> candidateRootCauses,
        List<EvidenceReference> evidenceRefs,
        List<String> insufficientEvidence
) {

    public RuleAnalysisEvidence {
        errorCodeDistribution = errorCodeDistribution == null ? List.of() : List.copyOf(errorCodeDistribution);
        uidDistribution = uidDistribution == null ? List.of() : List.copyOf(uidDistribution);
        traceObservations = traceObservations == null ? List.of() : List.copyOf(traceObservations);
        downstreamObservations = downstreamObservations == null ? List.of() : List.copyOf(downstreamObservations);
        metricAnomalies = metricAnomalies == null ? List.of() : List.copyOf(metricAnomalies);
        ruleFindings = ruleFindings == null ? List.of() : List.copyOf(ruleFindings);
        candidateRootCauses = candidateRootCauses == null ? List.of() : List.copyOf(candidateRootCauses);
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        insufficientEvidence = insufficientEvidence == null ? List.of() : List.copyOf(insufficientEvidence);
    }

    public static RuleAnalysisEvidence empty() {
        return new RuleAnalysisEvidence(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    public Optional<CandidateRootCause> topCandidateRootCause() {
        return candidateRootCauses.stream().findFirst();
    }

    public List<String> topFindingTypes(int limit) {
        return ruleFindings.stream()
                .map(RuleFinding::type)
                .distinct()
                .limit(limit)
                .toList();
    }
}
