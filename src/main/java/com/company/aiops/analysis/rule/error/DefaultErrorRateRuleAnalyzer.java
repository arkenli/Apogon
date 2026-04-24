package com.company.aiops.analysis.rule.error;

import com.company.aiops.alert.model.AlertEvent;
import com.company.aiops.analysis.evidence.ProviderCollectionResult;
import com.company.aiops.analysis.evidence.TraceRecord;
import com.company.aiops.analysis.result.EvidenceReference;
import com.company.aiops.analysis.rule.metric.MetricAnomalyDetector;
import com.company.aiops.analysis.rule.model.CandidateRootCause;
import com.company.aiops.analysis.rule.model.DownstreamObservation;
import com.company.aiops.analysis.rule.model.ErrorCodeAggregate;
import com.company.aiops.analysis.rule.model.MetricAnomaly;
import com.company.aiops.analysis.rule.model.RuleAnalysisEvidence;
import com.company.aiops.analysis.rule.model.RuleFinding;
import com.company.aiops.analysis.rule.model.TraceObservation;
import com.company.aiops.analysis.rule.model.UidAggregate;
import com.company.aiops.analysis.rule.trace.TraceSpan;
import com.company.aiops.analysis.rule.trace.TraceTopologyAnalyzer;
import com.company.aiops.analysis.rule.trace.TraceTree;
import com.company.aiops.common.config.AiopsProperties;
import com.company.aiops.provider.model.InterfaceBaseline;
import com.company.aiops.provider.model.InterfaceLogEntry;
import com.company.aiops.provider.model.RuntimeLogEntry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic phase-5 analyzer for RPC error-rate alerts.
 */
@Component
public class DefaultErrorRateRuleAnalyzer implements ErrorRateRuleAnalyzer {

    private static final List<String> BUSINESS_ERROR_KEYWORDS = List.of(
            "参数错误",
            "资格不满足",
            "活动结束",
            "库存不足",
            "重复领取",
            "用户状态不满足",
            "校验失败",
            "validation failed",
            "not eligible",
            "duplicate",
            "already",
            "out of stock",
            "invalid",
            "business rule"
    );
    private static final List<String> SQL_KEYWORDS = List.of("sql", "dao", "db", "database", "jdbc", "connection");
    private static final List<String> DCS_KEYWORDS = List.of("redis", "dcs", "cache timeout", "cache");
    private static final List<String> RESOURCE_CAUSE_TYPES = List.of(
            "DATABASE_RESOURCE_PRESSURE",
            "DCS_LATENCY_OR_FAILURE",
            "CONTAINER_CPU_PRESSURE",
            "GC_PRESSURE"
    );

    private final AiopsProperties aiopsProperties;
    private final MetricAnomalyDetector metricAnomalyDetector;
    private final TraceTopologyAnalyzer traceTopologyAnalyzer;

    public DefaultErrorRateRuleAnalyzer(
            AiopsProperties aiopsProperties,
            MetricAnomalyDetector metricAnomalyDetector,
            TraceTopologyAnalyzer traceTopologyAnalyzer
    ) {
        this.aiopsProperties = aiopsProperties;
        this.metricAnomalyDetector = metricAnomalyDetector;
        this.traceTopologyAnalyzer = traceTopologyAnalyzer;
    }

    @Override
    public RuleAnalysisEvidence analyze(ProviderCollectionResult collectionResult) {
        AlertEvent alert = collectionResult.context().alert();
        List<InterfaceLogEntry> failedEntries = collectionResult.interfaceLogEvidence().entries().stream()
                .filter(entry -> !entry.success())
                .toList();
        List<MetricAnomaly> metricAnomalies = metricAnomalyDetector.detect(collectionResult);
        List<ErrorCodeAggregate> errorCodeDistribution = buildErrorCodeDistribution(
                failedEntries,
                collectionResult.baselineEvidence().baseline(),
                collectionResult.context().options().maxErrorCodeSize()
        );
        List<UidAggregate> uidDistribution = buildUidDistribution(
                failedEntries,
                collectionResult.context().options().maxUidSize()
        );

        TraceAnalysis traceAnalysis = analyzeTraceFailures(collectionResult.traceEvidence().traces(), alert);

        List<EvidenceReference> evidenceRefs = new ArrayList<>();
        errorCodeDistribution.forEach(aggregate -> evidenceRefs.add(new EvidenceReference(
                aggregate.evidenceId(),
                "ERROR_CODE",
                "Failed requests aggregated by error code."
        )));
        uidDistribution.forEach(aggregate -> evidenceRefs.add(new EvidenceReference(
                aggregate.evidenceId(),
                "UID",
                "Failed requests aggregated by uid."
        )));
        traceAnalysis.traceObservations().forEach(observation -> evidenceRefs.add(new EvidenceReference(
                observation.evidenceId(),
                "TRACE",
                observation.description()
        )));
        traceAnalysis.downstreamObservations().forEach(observation -> evidenceRefs.add(new EvidenceReference(
                observation.evidenceId(),
                "DOWNSTREAM",
                observation.description()
        )));
        metricAnomalies.forEach(anomaly -> evidenceRefs.add(new EvidenceReference(
                anomaly.evidenceId(),
                "METRIC",
                anomaly.description()
        )));

        List<RuleFinding> ruleFindings = new ArrayList<>();
        List<CandidateRootCause> candidateRootCauses = new ArrayList<>();
        Set<String> insufficientEvidence = new LinkedHashSet<>();

        if (failedEntries.isEmpty()) {
            insufficientEvidence.add("No failed interface-log samples were collected for the error-rate alert window.");
        }
        if (!collectionResult.traceEvidence().collected()) {
            insufficientEvidence.add(defaultIfBlank(
                    collectionResult.traceEvidence().missingReason(),
                    "Trace evidence is unavailable, so downstream-failure confirmation is weaker."
            ));
        } else {
            insufficientEvidence.add("Trace topology is inferred from spanId/parentSpanId instead of a dedicated call-tree API.");
            if (traceAnalysis.hasUnresolvedParents()) {
                insufficientEvidence.add("Some sampled traces contain spans whose parentSpanId cannot be resolved.");
            }
        }
        if (!collectionResult.baselineEvidence().collected()) {
            insufficientEvidence.add(defaultIfBlank(
                    collectionResult.baselineEvidence().missingReason(),
                    "Historical baseline is unavailable for same-period comparison."
            ));
        }

        double requestCount = resolveRequestCount(alert, collectionResult.interfaceLogEvidence().entries());
        if (requestCount < aiopsProperties.analysis().limitedImpactRequestThreshold()) {
            String evidenceId = "impact.request.1";
            evidenceRefs.add(new EvidenceReference(
                    evidenceId,
                    "RULE_CONTEXT",
                    "Current request count is below the limited-impact threshold, but the alert still requires full analysis."
            ));
            ruleFindings.add(new RuleFinding(
                    "LOW_TRAFFIC_LIMITED_IMPACT",
                    0.85,
                    "requestCount=%s is below threshold=%s, so the current impact scope may be limited."
                            .formatted(formatDecimal(requestCount), aiopsProperties.analysis().limitedImpactRequestThreshold()),
                    List.of(evidenceId)
            ));
        }

        ErrorCodeAggregate topError = errorCodeDistribution.isEmpty() ? null : errorCodeDistribution.get(0);
        UidAggregate topUid = uidDistribution.isEmpty() ? null : uidDistribution.get(0);
        double topUidShare = topUid == null ? 0.0 : topUid.share();
        boolean hasResourcePressure = metricAnomalies.stream()
                .map(MetricAnomaly::resourceType)
                .anyMatch(resourceType -> !"MQ".equals(resourceType));
        boolean hasDownstreamFailure = !traceAnalysis.downstreamObservations().isEmpty();

        CandidateRootCause businessCandidate = scoreBusinessErrorCode(
                topError,
                topUid,
                topUidShare,
                hasDownstreamFailure,
                hasResourcePressure
        );
        if (businessCandidate.score() > 0) {
            candidateRootCauses.add(businessCandidate);
            if (businessCandidate.score() >= 40) {
                ruleFindings.add(new RuleFinding(
                        "BUSINESS_ERROR_CODE_NOT_MASKED",
                        businessCandidate.confidence(),
                        "Top errorCode is concentrated and its representative errorMsg shows business-validation semantics.",
                        businessCandidate.evidenceIds()
                ));
            }
        }

        CandidateRootCause downstreamCandidate = scoreDownstreamFailure(traceAnalysis, topError);
        if (downstreamCandidate.score() > 0) {
            candidateRootCauses.add(downstreamCandidate);
            ruleFindings.add(new RuleFinding(
                    "DOWNSTREAM_RPC_FAILURE",
                    downstreamCandidate.confidence(),
                    "Sampled traces repeatedly fail at the same downstream service/interface.",
                    downstreamCandidate.evidenceIds()
            ));
        }

        CandidateRootCause databaseCandidate = scoreDatabasePressure(metricAnomalies, collectionResult.runtimeLogEvidence().entries());
        if (databaseCandidate.score() > 0) {
            candidateRootCauses.add(databaseCandidate);
        }

        CandidateRootCause dcsCandidate = scoreDcsPressure(metricAnomalies, collectionResult.runtimeLogEvidence().entries());
        if (dcsCandidate.score() > 0) {
            candidateRootCauses.add(dcsCandidate);
        }

        CandidateRootCause containerCandidate = scoreContainerCpuPressure(metricAnomalies);
        if (containerCandidate.score() > 0) {
            candidateRootCauses.add(containerCandidate);
        }

        CandidateRootCause gcCandidate = scoreGcPressure(metricAnomalies);
        if (gcCandidate.score() > 0) {
            candidateRootCauses.add(gcCandidate);
        }

        CandidateRootCause lowTrafficCandidate = scoreLowTraffic(requestCount, failedEntries, uidDistribution);
        if (lowTrafficCandidate.score() > 0) {
            candidateRootCauses.add(lowTrafficCandidate);
        }

        candidateRootCauses = candidateRootCauses.stream()
                .sorted(this::compareCandidates)
                .toList();

        candidateRootCauses.stream()
                .filter(candidate -> RESOURCE_CAUSE_TYPES.contains(candidate.causeType()))
                .filter(candidate -> candidate.score() >= 20)
                .forEach(candidate -> ruleFindings.add(new RuleFinding(
                        candidate.causeType(),
                        candidate.confidence(),
                        candidate.reasoning(),
                        candidate.evidenceIds()
                )));

        return new RuleAnalysisEvidence(
                errorCodeDistribution,
                uidDistribution,
                traceAnalysis.traceObservations(),
                traceAnalysis.downstreamObservations(),
                metricAnomalies,
                deduplicateFindings(ruleFindings),
                candidateRootCauses,
                deduplicateEvidenceRefs(evidenceRefs),
                List.copyOf(insufficientEvidence)
        );
    }

    private List<ErrorCodeAggregate> buildErrorCodeDistribution(
            List<InterfaceLogEntry> failedEntries,
            InterfaceBaseline baseline,
            int maxSize
    ) {
        if (failedEntries.isEmpty()) {
            return List.of();
        }

        Map<String, ErrorCodeStats> statsByCode = new LinkedHashMap<>();
        for (InterfaceLogEntry entry : failedEntries) {
            String errorCode = normalizeErrorCode(entry.errorCode());
            ErrorCodeStats stats = statsByCode.computeIfAbsent(errorCode, ignored -> new ErrorCodeStats(errorCode));
            stats.count++;
            if (entry.uid() != null && !entry.uid().isBlank()) {
                stats.uids.add(entry.uid());
            }
            if (entry.traceId() != null && !entry.traceId().isBlank()) {
                stats.traceIds.add(entry.traceId());
            }
            if (entry.errorMsg() != null && !entry.errorMsg().isBlank()) {
                stats.sampleErrorMsgs.add(entry.errorMsg());
            }
        }

        long totalFailures = failedEntries.size();
        List<ErrorCodeStats> sortedStats = statsByCode.values().stream()
                .sorted(Comparator.comparingLong((ErrorCodeStats stats) -> stats.count).reversed()
                        .thenComparing(stats -> stats.errorCode))
                .limit(maxSize)
                .toList();
        List<ErrorCodeAggregate> aggregates = new ArrayList<>(sortedStats.size());
        int index = 1;
        for (ErrorCodeStats stats : sortedStats) {
            List<String> sampleMsgs = stats.sampleErrorMsgs.stream().distinct().limit(3).toList();
            aggregates.add(new ErrorCodeAggregate(
                    "error.top." + index++,
                    stats.errorCode,
                    stats.count,
                    share(stats.count, totalFailures),
                    stats.uids.size(),
                    stats.traceIds.size(),
                    baselineContainsErrorCode(baseline, stats.errorCode),
                    looksBusinessSemantic(sampleMsgs),
                    sampleMsgs
            ));
        }
        return aggregates;
    }

    private List<UidAggregate> buildUidDistribution(List<InterfaceLogEntry> failedEntries, int maxSize) {
        if (failedEntries.isEmpty()) {
            return List.of();
        }

        Map<String, Long> counts = new LinkedHashMap<>();
        for (InterfaceLogEntry entry : failedEntries) {
            if (entry.uid() == null || entry.uid().isBlank()) {
                continue;
            }
            counts.merge(entry.uid(), 1L, Long::sum);
        }
        long totalFailures = failedEntries.size();
        List<Map.Entry<String, Long>> sortedCounts = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(maxSize)
                .toList();
        List<UidAggregate> aggregates = new ArrayList<>(sortedCounts.size());
        int index = 1;
        for (Map.Entry<String, Long> entry : sortedCounts) {
            aggregates.add(new UidAggregate(
                    "uid.top." + index++,
                    entry.getKey(),
                    entry.getValue(),
                    share(entry.getValue(), totalFailures)
            ));
        }
        return aggregates;
    }

    private TraceAnalysis analyzeTraceFailures(List<TraceRecord> traces, AlertEvent alert) {
        List<TraceObservation> traceObservations = new ArrayList<>();
        Map<String, DownstreamStats> downstreamStats = new LinkedHashMap<>();
        boolean hasUnresolvedParents = false;
        int traceIndex = 1;
        for (TraceRecord trace : traces) {
            TraceTree tree = traceTopologyAnalyzer.buildTraceTree(trace.entries());
            hasUnresolvedParents = hasUnresolvedParents || !tree.unresolvedParentSpanIds().isEmpty();
            TraceSpan firstFailedSpan = tree.firstFailedSpan().orElse(null);
            if (firstFailedSpan == null) {
                continue;
            }
            String evidenceId = "trace.failure." + traceIndex++;
            boolean downstream = !isCurrentSpan(firstFailedSpan, alert);
            String description = downstream
                    ? "Trace first failed at downstream span %s/%s.".formatted(
                    firstFailedSpan.serviceName(),
                    firstFailedSpan.interfaceName()
            )
                    : "Trace first failed at the alerting interface span.";
            traceObservations.add(new TraceObservation(
                    evidenceId,
                    defaultIfBlank(trace.traceId(), tree.traceId()),
                    "FIRST_FAILED_SPAN",
                    firstFailedSpan.spanId(),
                    firstFailedSpan.serviceName(),
                    firstFailedSpan.interfaceName(),
                    firstFailedSpan.errorCode(),
                    firstFailedSpan.success(),
                    firstFailedSpan.latencyMs(),
                    description
            ));

            if (downstream) {
                String key = downstreamKey(firstFailedSpan);
                DownstreamStats stats = downstreamStats.computeIfAbsent(
                        key,
                        ignored -> new DownstreamStats(firstFailedSpan.serviceName(), firstFailedSpan.interfaceName())
                );
                stats.traceIds.add(defaultIfBlank(trace.traceId(), tree.traceId()));
                stats.totalLatencyMs += firstFailedSpan.latencyMs();
                if (firstFailedSpan.errorCode() != null && !firstFailedSpan.errorCode().isBlank()) {
                    stats.errorCodes.add(firstFailedSpan.errorCode());
                }
            }
        }

        long traceCount = traces.size();
        List<DownstreamStats> sortedDownstreamStats = downstreamStats.values().stream()
                .sorted(Comparator.comparingInt((DownstreamStats stats) -> stats.traceIds.size()).reversed()
                        .thenComparing(stats -> defaultIfBlank(stats.serviceName, ""))
                        .thenComparing(stats -> defaultIfBlank(stats.interfaceName, "")))
                .toList();
        List<DownstreamObservation> downstreamObservations = new ArrayList<>(sortedDownstreamStats.size());
        int downstreamIndex = 1;
        for (DownstreamStats stats : sortedDownstreamStats) {
            downstreamObservations.add(new DownstreamObservation(
                    "trace.downstream.failure." + downstreamIndex++,
                    stats.serviceName,
                    stats.interfaceName,
                    stats.traceIds.size(),
                    share(stats.traceIds.size(), traceCount),
                    true,
                    stats.traceIds.isEmpty() ? 0L : Math.round((double) stats.totalLatencyMs / stats.traceIds.size()),
                    "Sampled traces repeatedly fail at the same downstream span."
            ));
        }
        return new TraceAnalysis(traceObservations, downstreamObservations, hasUnresolvedParents);
    }

    private CandidateRootCause scoreBusinessErrorCode(
            ErrorCodeAggregate topError,
            UidAggregate topUid,
            double topUidShare,
            boolean hasDownstreamFailure,
            boolean hasResourcePressure
    ) {
        if (topError == null) {
            return candidate("BUSINESS_ERROR_CODE_NOT_MASKED", 0, List.of(), "No dominant errorCode is available.");
        }

        int score = 0;
        List<String> evidenceIds = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        if (topError.share() >= 0.8) {
            score += 30;
            evidenceIds.add(topError.evidenceId());
            reasons.add("top errorCode share >= 80%");
        }
        if (topError.businessSemantic()) {
            score += 25;
            evidenceIds.add(topError.evidenceId());
            reasons.add("representative errorMsg matches business-validation semantics");
        }
        if (topUid != null && topUidShare >= 0.5) {
            score += 10;
            evidenceIds.add(topUid.evidenceId());
            reasons.add("failed requests are concentrated on a small uid set");
        }
        if (!hasDownstreamFailure) {
            score += 15;
            reasons.add("no downstream failure was confirmed in sampled traces");
        }
        if (!hasResourcePressure) {
            score += 10;
            reasons.add("no resource pressure anomalies were detected");
        }
        return candidate(
                "BUSINESS_ERROR_CODE_NOT_MASKED",
                score,
                evidenceIds,
                reasons.isEmpty()
                        ? "No deterministic business-validation pattern was found."
                        : String.join("; ", reasons) + "."
        );
    }

    private CandidateRootCause scoreDownstreamFailure(TraceAnalysis traceAnalysis, ErrorCodeAggregate topError) {
        DownstreamObservation dominant = traceAnalysis.downstreamObservations().isEmpty()
                ? null
                : traceAnalysis.downstreamObservations().get(0);
        if (dominant == null) {
            return candidate("DOWNSTREAM_RPC_FAILURE", 0, List.of(), "No downstream failure pattern was observed.");
        }

        int score = 0;
        List<String> evidenceIds = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        if (dominant.share() >= 0.5 && dominant.traceCount() >= 2) {
            score += 35;
            evidenceIds.add(dominant.evidenceId());
            reasons.add("the same downstream interface is the first failed span in >= 50% sampled traces");
        }
        if (dominant.failureRelated()) {
            score += 25;
            evidenceIds.add(dominant.evidenceId());
            reasons.add("the downstream span itself returned success=false");
        }

        Set<String> dominantErrorCodes = traceAnalysis.downstreamErrorCodes(dominant.serviceName(), dominant.interfaceName());
        if (topError != null && dominantErrorCodes.contains(topError.errorCode())) {
            score += 10;
            evidenceIds.add(dominant.evidenceId());
            reasons.add("the downstream errorCode matches the dominant current-interface errorCode");
        }
        return candidate(
                "DOWNSTREAM_RPC_FAILURE",
                score,
                evidenceIds,
                reasons.isEmpty()
                        ? "Downstream failures were observed, but not strongly concentrated."
                        : String.join("; ", reasons) + "."
        );
    }

    private CandidateRootCause scoreDatabasePressure(
            List<MetricAnomaly> metricAnomalies,
            List<RuntimeLogEntry> runtimeLogs
    ) {
        int score = 0;
        List<String> evidenceIds = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        List<MetricAnomaly> dbAnomalies = metricAnomalies.stream()
                .filter(anomaly -> "DATABASE".equals(anomaly.resourceType()))
                .toList();
        if (dbAnomalies.stream().anyMatch(anomaly -> anomaly.metric().contains("slow") || anomaly.metric().contains("writeLatency"))) {
            score += 25;
            evidenceIds.addAll(dbAnomalies.stream()
                    .filter(anomaly -> anomaly.metric().contains("slow") || anomaly.metric().contains("writeLatency"))
                    .map(MetricAnomaly::evidenceId)
                    .toList());
            reasons.add("database slow SQL or write-latency anomalies were detected");
        }
        if (dbAnomalies.stream().anyMatch(anomaly ->
                anomaly.metric().contains("cpu")
                        || anomaly.metric().contains("connection")
                        || anomaly.metric().contains("lock"))) {
            score += 20;
            evidenceIds.addAll(dbAnomalies.stream()
                    .filter(anomaly -> anomaly.metric().contains("cpu")
                            || anomaly.metric().contains("connection")
                            || anomaly.metric().contains("lock"))
                    .map(MetricAnomaly::evidenceId)
                    .toList());
            reasons.add("database resource-pressure anomalies were detected");
        }
        if (containsKeyword(runtimeLogs, SQL_KEYWORDS)) {
            score += 20;
            reasons.add("runtime logs contain SQL / DB keywords");
        }
        return candidate(
                "DATABASE_RESOURCE_PRESSURE",
                score,
                evidenceIds,
                reasons.isEmpty() ? "No database pressure evidence was found." : String.join("; ", reasons) + "."
        );
    }

    private CandidateRootCause scoreDcsPressure(
            List<MetricAnomaly> metricAnomalies,
            List<RuntimeLogEntry> runtimeLogs
    ) {
        int score = 0;
        List<String> evidenceIds = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        List<MetricAnomaly> dcsAnomalies = metricAnomalies.stream()
                .filter(anomaly -> "DCS".equals(anomaly.resourceType()))
                .toList();
        if (dcsAnomalies.stream().anyMatch(anomaly -> anomaly.metric().contains("clientFailure"))) {
            score += 25;
            evidenceIds.addAll(dcsAnomalies.stream()
                    .filter(anomaly -> anomaly.metric().contains("clientFailure"))
                    .map(MetricAnomaly::evidenceId)
                    .toList());
            reasons.add("DCS client failures were detected");
        }
        if (dcsAnomalies.stream().anyMatch(anomaly ->
                anomaly.metric().contains("p99") || anomaly.metric().contains("avgLatency"))) {
            score += 20;
            evidenceIds.addAll(dcsAnomalies.stream()
                    .filter(anomaly -> anomaly.metric().contains("p99") || anomaly.metric().contains("avgLatency"))
                    .map(MetricAnomaly::evidenceId)
                    .toList());
            reasons.add("DCS latency anomalies were detected");
        }
        if (containsKeyword(runtimeLogs, DCS_KEYWORDS)) {
            score += 20;
            reasons.add("runtime logs contain Redis / DCS / cache timeout keywords");
        }
        return candidate(
                "DCS_LATENCY_OR_FAILURE",
                score,
                evidenceIds,
                reasons.isEmpty() ? "No DCS pressure evidence was found." : String.join("; ", reasons) + "."
        );
    }

    private CandidateRootCause scoreContainerCpuPressure(List<MetricAnomaly> metricAnomalies) {
        int score = 0;
        List<String> evidenceIds = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        if (metricAnomalies.stream().anyMatch(anomaly ->
                "CONTAINER".equals(anomaly.resourceType()) && anomaly.metric().contains("cpu"))) {
            score += 25;
            evidenceIds.addAll(metricAnomalies.stream()
                    .filter(anomaly -> "CONTAINER".equals(anomaly.resourceType()) && anomaly.metric().contains("cpu"))
                    .map(MetricAnomaly::evidenceId)
                    .toList());
            reasons.add("container CPU usage exceeds the configured threshold");
        }
        if (metricAnomalies.stream().anyMatch(anomaly -> anomaly.evidenceId().contains("instance_cpu_skew"))) {
            score += 15;
            evidenceIds.addAll(metricAnomalies.stream()
                    .filter(anomaly -> anomaly.evidenceId().contains("instance_cpu_skew"))
                    .map(MetricAnomaly::evidenceId)
                    .toList());
            reasons.add("CPU pressure is concentrated on a single instance");
        }
        if (metricAnomalies.stream().anyMatch(anomaly -> "CSE".equals(anomaly.resourceType()))) {
            score += 10;
            evidenceIds.addAll(metricAnomalies.stream()
                    .filter(anomaly -> "CSE".equals(anomaly.resourceType()))
                    .map(MetricAnomaly::evidenceId)
                    .toList());
            reasons.add("CSE usage is also elevated");
        }
        return candidate(
                "CONTAINER_CPU_PRESSURE",
                score,
                evidenceIds,
                reasons.isEmpty() ? "No container CPU pressure evidence was found." : String.join("; ", reasons) + "."
        );
    }

    private CandidateRootCause scoreGcPressure(List<MetricAnomaly> metricAnomalies) {
        int score = 0;
        List<String> evidenceIds = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        if (metricAnomalies.stream().anyMatch(anomaly -> anomaly.metric().contains("fullGc"))) {
            score += 30;
            evidenceIds.addAll(metricAnomalies.stream()
                    .filter(anomaly -> anomaly.metric().contains("fullGc"))
                    .map(MetricAnomaly::evidenceId)
                    .toList());
            reasons.add("Full GC occurred in the alert window");
        }
        if (metricAnomalies.stream().anyMatch(anomaly -> anomaly.metric().contains("youngGc"))) {
            score += 15;
            evidenceIds.addAll(metricAnomalies.stream()
                    .filter(anomaly -> anomaly.metric().contains("youngGc"))
                    .map(MetricAnomaly::evidenceId)
                    .toList());
            reasons.add("Young GC count is elevated");
        }
        if (metricAnomalies.stream().anyMatch(anomaly ->
                "CONTAINER".equals(anomaly.resourceType()) && anomaly.metric().contains("memory"))) {
            score += 15;
            evidenceIds.addAll(metricAnomalies.stream()
                    .filter(anomaly -> "CONTAINER".equals(anomaly.resourceType()) && anomaly.metric().contains("memory"))
                    .map(MetricAnomaly::evidenceId)
                    .toList());
            reasons.add("container memory usage is elevated alongside GC");
        }
        return candidate(
                "GC_PRESSURE",
                score,
                evidenceIds,
                reasons.isEmpty() ? "No GC pressure evidence was found." : String.join("; ", reasons) + "."
        );
    }

    private CandidateRootCause scoreLowTraffic(
            double requestCount,
            List<InterfaceLogEntry> failedEntries,
            List<UidAggregate> uidDistribution
    ) {
        if (requestCount >= aiopsProperties.analysis().limitedImpactRequestThreshold()) {
            return candidate("LOW_TRAFFIC_LIMITED_IMPACT", 0, List.of(), "Request count is not low-impact.");
        }

        int score = 30;
        List<String> evidenceIds = new ArrayList<>(List.of("impact.request.1"));
        List<String> reasons = new ArrayList<>(List.of("requestCount is below the limited-impact threshold"));
        long distinctFailedUidCount = uidDistribution.stream().map(UidAggregate::uid).distinct().count();
        if (!failedEntries.isEmpty() && distinctFailedUidCount <= 3) {
            score += 10;
            if (!uidDistribution.isEmpty()) {
                evidenceIds.add(uidDistribution.get(0).evidenceId());
            }
            reasons.add("failed uid count is small");
        }
        return candidate("LOW_TRAFFIC_LIMITED_IMPACT", score, evidenceIds, String.join("; ", reasons) + ".");
    }

    private CandidateRootCause candidate(String causeType, int score, List<String> evidenceIds, String reasoning) {
        return new CandidateRootCause(
                causeType,
                score,
                confidence(score),
                evidenceIds.stream().filter(Objects::nonNull).distinct().toList(),
                reasoning
        );
    }

    private int compareCandidates(CandidateRootCause left, CandidateRootCause right) {
        int byScore = Integer.compare(right.score(), left.score());
        if (byScore != 0) {
            return byScore;
        }
        int leftPriority = candidatePriority(left.causeType());
        int rightPriority = candidatePriority(right.causeType());
        if (leftPriority != rightPriority) {
            return Integer.compare(leftPriority, rightPriority);
        }
        return left.causeType().compareTo(right.causeType());
    }

    private int candidatePriority(String causeType) {
        return switch (causeType) {
            case "DOWNSTREAM_RPC_FAILURE" -> 0;
            case "BUSINESS_ERROR_CODE_NOT_MASKED" -> 1;
            case "DATABASE_RESOURCE_PRESSURE" -> 2;
            case "DCS_LATENCY_OR_FAILURE" -> 3;
            case "CONTAINER_CPU_PRESSURE" -> 4;
            case "GC_PRESSURE" -> 5;
            case "LOW_TRAFFIC_LIMITED_IMPACT" -> 6;
            default -> 100;
        };
    }

    private double confidence(int score) {
        if (score >= 80) {
            return 0.90;
        }
        if (score >= 60) {
            return 0.75;
        }
        if (score >= 40) {
            return 0.55;
        }
        if (score >= 20) {
            return 0.35;
        }
        return 0.20;
    }

    private double resolveRequestCount(AlertEvent alert, List<InterfaceLogEntry> interfaceEntries) {
        if (alert.triggerValues() != null && alert.triggerValues().requestCount() != null) {
            return alert.triggerValues().requestCount();
        }
        return interfaceEntries.size();
    }

    private boolean baselineContainsErrorCode(InterfaceBaseline baseline, String errorCode) {
        if (baseline == null || baseline.commonErrorCodes() == null) {
            return false;
        }
        return baseline.commonErrorCodes().stream()
                .map(InterfaceBaseline.ErrorCodeBaseline::errorCode)
                .filter(Objects::nonNull)
                .anyMatch(errorCode::equals);
    }

    private boolean looksBusinessSemantic(List<String> sampleMessages) {
        return sampleMessages.stream().anyMatch(message -> containsKeyword(message, BUSINESS_ERROR_KEYWORDS));
    }

    private boolean containsKeyword(List<RuntimeLogEntry> runtimeLogs, List<String> keywords) {
        return runtimeLogs.stream()
                .map(RuntimeLogEntry::message)
                .filter(Objects::nonNull)
                .anyMatch(message -> containsKeyword(message, keywords));
    }

    private boolean containsKeyword(String message, List<String> keywords) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return keywords.stream().anyMatch(normalized::contains);
    }

    private boolean isCurrentSpan(TraceSpan span, AlertEvent alert) {
        return Objects.equals(alert.serviceName(), span.serviceName())
                && Objects.equals(alert.interfaceName(), span.interfaceName());
    }

    private String downstreamKey(TraceSpan span) {
        return defaultIfBlank(span.serviceName(), "UNKNOWN")
                + "|"
                + defaultIfBlank(span.interfaceName(), "UNKNOWN");
    }

    private double share(long part, long total) {
        if (total <= 0) {
            return 0.0;
        }
        return (double) part / total;
    }

    private String normalizeErrorCode(String errorCode) {
        return errorCode == null || errorCode.isBlank() ? "UNKNOWN" : errorCode;
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private String formatDecimal(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private List<RuleFinding> deduplicateFindings(List<RuleFinding> findings) {
        Map<String, RuleFinding> deduplicated = new LinkedHashMap<>();
        for (RuleFinding finding : findings) {
            deduplicated.putIfAbsent(finding.type(), finding);
        }
        return List.copyOf(deduplicated.values());
    }

    private List<EvidenceReference> deduplicateEvidenceRefs(List<EvidenceReference> evidenceRefs) {
        Map<String, EvidenceReference> deduplicated = new LinkedHashMap<>();
        for (EvidenceReference reference : evidenceRefs) {
            deduplicated.putIfAbsent(reference.evidenceId(), reference);
        }
        return List.copyOf(deduplicated.values());
    }

    private static final class ErrorCodeStats {
        private final String errorCode;
        private long count;
        private final Set<String> uids = new LinkedHashSet<>();
        private final Set<String> traceIds = new LinkedHashSet<>();
        private final List<String> sampleErrorMsgs = new ArrayList<>();

        private ErrorCodeStats(String errorCode) {
            this.errorCode = errorCode;
        }
    }

    private static final class DownstreamStats {
        private final String serviceName;
        private final String interfaceName;
        private final Set<String> traceIds = new LinkedHashSet<>();
        private final Set<String> errorCodes = new LinkedHashSet<>();
        private long totalLatencyMs;

        private DownstreamStats(String serviceName, String interfaceName) {
            this.serviceName = serviceName;
            this.interfaceName = interfaceName;
        }
    }

    private record TraceAnalysis(
            List<TraceObservation> traceObservations,
            List<DownstreamObservation> downstreamObservations,
            boolean hasUnresolvedParents
    ) {
        private Set<String> downstreamErrorCodes(String serviceName, String interfaceName) {
            return downstreamObservations.stream()
                    .filter(observation -> Objects.equals(serviceName, observation.serviceName()))
                    .filter(observation -> Objects.equals(interfaceName, observation.interfaceName()))
                    .flatMap(observation -> traceObservations.stream()
                            .filter(trace -> "FIRST_FAILED_SPAN".equals(trace.observationType()))
                            .filter(trace -> Objects.equals(serviceName, trace.serviceName()))
                            .filter(trace -> Objects.equals(interfaceName, trace.interfaceName()))
                            .map(TraceObservation::errorCode))
                    .filter(Objects::nonNull)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        }
    }
}
