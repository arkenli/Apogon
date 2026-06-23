package com.company.aiops.analysis.bundle;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.analysis.result.EvidenceReference;
import com.company.aiops.analysis.result.MissingEvidence;
import com.company.aiops.analysis.rule.model.CandidateRootCause;
import com.company.aiops.analysis.rule.model.DownstreamObservation;
import com.company.aiops.analysis.rule.model.ErrorCodeAggregate;
import com.company.aiops.analysis.rule.model.MetricAnomaly;
import com.company.aiops.analysis.rule.model.RuleFinding;
import com.company.aiops.analysis.rule.model.TraceObservation;
import com.company.aiops.analysis.rule.model.UidAggregate;
import com.company.aiops.provider.model.ContainerMetrics;
import com.company.aiops.provider.model.CseMetrics;
import com.company.aiops.provider.model.DatabaseMetrics;
import com.company.aiops.provider.model.DcsMetrics;
import com.company.aiops.provider.model.InterfaceBaseline;
import com.company.aiops.provider.model.MqMetrics;
import com.company.aiops.provider.model.ResourceMapping;

import java.time.Instant;
import java.util.List;

/**
 * Structured phase-6 bundle persisted into {@code aiops_evidence_bundle}.
 *
 * <p>The bundle keeps only high-signal summaries and bounded samples so later LLM/fallback stages
 * can reuse the same data without touching the raw provider payload again.</p>
 */
public record CompressedEvidenceBundle(
        AlertSnapshot alert,
        BaselineSnapshot baseline,
        InterfaceLogSnapshot interfaceLogs,
        TraceSnapshot traces,
        RuntimeLogSnapshot runtimeLogs,
        MetricSnapshot metrics,
        ResourceMapping resourceMapping,
        RuleSnapshot ruleAnalysis,
        List<EvidenceReference> evidenceRefs,
        List<MissingEvidence> missingEvidence,
        List<String> insufficientEvidence
) {

    public CompressedEvidenceBundle {
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        missingEvidence = missingEvidence == null ? List.of() : List.copyOf(missingEvidence);
        insufficientEvidence = insufficientEvidence == null ? List.of() : List.copyOf(insufficientEvidence);
    }

    /**
     * Core alert context plus the current trigger values that LLM/fallback logic reasons about.
     */
    public record AlertSnapshot(
            AlertCategory category,
            String serviceName,
            String interfaceName,
            String cluster,
            Instant occurTime,
            String requestCountEvidenceId,
            Double requestCount,
            String failRateEvidenceId,
            Double failRate,
            String avgLatencyEvidenceId,
            Long avgLatencyMs,
            String p99LatencyEvidenceId,
            Long p99LatencyMs,
            String failureCountEvidenceId,
            Double failureCount,
            String logLevelEvidenceId,
            String logLevel,
            String logMessageEvidenceId,
            String logMessage,
            String normalizedLogQueryEvidenceId,
            String normalizedLogQuery
    ) {
    }

    /**
     * Comparable-time baseline kept as a single structured object with stable summary evidence ids.
     */
    public record BaselineSnapshot(
            boolean collected,
            String missingReason,
            String requestCountEvidenceId,
            String failRateEvidenceId,
            String avgLatencyEvidenceId,
            String p99LatencyEvidenceId,
            InterfaceBaseline baseline
    ) {

        public BaselineSnapshot {
            baseline = baseline == null ? InterfaceBaseline.empty() : baseline;
        }
    }

    /**
     * Interface-log summary replaces large raw log payloads with counts, latency profile, and TopN
     * distributions.
     */
    public record InterfaceLogSnapshot(
            boolean collected,
            String missingReason,
            String requestCountEvidenceId,
            long totalCount,
            String failureRateEvidenceId,
            long failedCount,
            double failureRate,
            String latencyEvidenceId,
            long averageLatencyMs,
            long p95LatencyMs,
            long p99LatencyMs,
            long maxLatencyMs,
            List<String> sampledTraceIds,
            List<ErrorCodeAggregate> topErrorCodes,
            List<UidAggregate> topUids
    ) {

        public InterfaceLogSnapshot {
            sampledTraceIds = sampledTraceIds == null ? List.of() : List.copyOf(sampledTraceIds);
            topErrorCodes = topErrorCodes == null ? List.of() : List.copyOf(topErrorCodes);
            topUids = topUids == null ? List.of() : List.copyOf(topUids);
        }
    }

    /**
     * Trace observations already normalized by phase-5 rule analysis and trimmed to the current
     * category's sampling bound.
     */
    public record TraceSnapshot(
            boolean collected,
            String missingReason,
            List<String> sampledTraceIds,
            List<TraceObservation> observations,
            List<DownstreamObservation> downstreamObservations
    ) {

        public TraceSnapshot {
            sampledTraceIds = sampledTraceIds == null ? List.of() : List.copyOf(sampledTraceIds);
            observations = observations == null ? List.of() : List.copyOf(observations);
            downstreamObservations = downstreamObservations == null ? List.of() : List.copyOf(downstreamObservations);
        }
    }

    /**
     * Runtime-log samples receive deterministic evidence ids because they are not generated by the
     * phase-5 rule analyzers.
     */
    public record RuntimeLogSnapshot(
            boolean collected,
            String missingReason,
            List<RuntimeLogSample> samples,
            boolean messageQueryMatched,
            String matchedLogQuery,
            List<RuntimeLogQueryAttempt> queryAttempts,
            List<String> sampledTraceIds
    ) {

        public RuntimeLogSnapshot {
            samples = samples == null ? List.of() : List.copyOf(samples);
            queryAttempts = queryAttempts == null ? List.of() : List.copyOf(queryAttempts);
            sampledTraceIds = sampledTraceIds == null ? List.of() : List.copyOf(sampledTraceIds);
        }
    }

    /**
     * One runtime-log message query attempted while collecting service-error-log evidence.
     */
    public record RuntimeLogQueryAttempt(
            String evidenceId,
            String query,
            String level,
            int hitCount,
            boolean selected
    ) {
    }

    /**
     * One truncated runtime-log line retained for later human-readable reasoning.
     */
    public record RuntimeLogSample(
            String evidenceId,
            String serviceName,
            String traceId,
            String level,
            String message,
            Instant timestamp
    ) {
    }

    /**
     * Metric snapshots stay structured, while anomalies expose the concrete evidence ids that rule
     * and LLM stages can cite.
     */
    public record MetricSnapshot(
            boolean collected,
            String missingReason,
            ContainerMetrics containerMetrics,
            CseMetrics cseMetrics,
            DatabaseMetrics databaseMetrics,
            DcsMetrics dcsMetrics,
            MqMetrics mqMetrics,
            List<MetricAnomaly> anomalies
    ) {

        public MetricSnapshot {
            containerMetrics = containerMetrics == null ? ContainerMetrics.empty() : containerMetrics;
            cseMetrics = cseMetrics == null ? CseMetrics.empty() : cseMetrics;
            databaseMetrics = databaseMetrics == null ? DatabaseMetrics.empty() : databaseMetrics;
            dcsMetrics = dcsMetrics == null ? DcsMetrics.empty() : dcsMetrics;
            mqMetrics = mqMetrics == null ? MqMetrics.empty() : mqMetrics;
            anomalies = anomalies == null ? List.of() : List.copyOf(anomalies);
        }
    }

    /**
     * Deterministic phase-5 findings and candidate root causes carried forward into later stages.
     */
    public record RuleSnapshot(
            List<RuleFinding> findings,
            List<CandidateRootCause> candidateRootCauses
    ) {

        public RuleSnapshot {
            findings = findings == null ? List.of() : List.copyOf(findings);
            candidateRootCauses = candidateRootCauses == null ? List.of() : List.copyOf(candidateRootCauses);
        }
    }
}
