package com.company.aiops.analysis.rule.latency;

import com.company.aiops.alert.model.AlertEvent;
import com.company.aiops.analysis.evidence.ProviderCollectionResult;
import com.company.aiops.analysis.evidence.TraceRecord;
import com.company.aiops.analysis.result.EvidenceReference;
import com.company.aiops.analysis.rule.metric.MetricAnomalyDetector;
import com.company.aiops.analysis.rule.model.CandidateRootCause;
import com.company.aiops.analysis.rule.model.DownstreamObservation;
import com.company.aiops.analysis.rule.model.MetricAnomaly;
import com.company.aiops.analysis.rule.model.RuleAnalysisEvidence;
import com.company.aiops.analysis.rule.model.RuleFinding;
import com.company.aiops.analysis.rule.model.TraceObservation;
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
 * Deterministic phase-5 analyzer for RPC latency alerts.
 */
@Component
public class DefaultLatencyRuleAnalyzer implements LatencyRuleAnalyzer {

    private static final List<String> SQL_KEYWORDS = List.of("sql", "dao", "db", "database", "jdbc", "connection");
    private static final List<String> DCS_KEYWORDS = List.of("redis", "dcs", "cache", "timeout");

    private final AiopsProperties aiopsProperties;
    private final MetricAnomalyDetector metricAnomalyDetector;
    private final TraceTopologyAnalyzer traceTopologyAnalyzer;

    public DefaultLatencyRuleAnalyzer(
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
        List<InterfaceLogEntry> interfaceEntries = collectionResult.interfaceLogEvidence().entries();
        List<MetricAnomaly> metricAnomalies = metricAnomalyDetector.detect(collectionResult);
        TraceAnalysis traceAnalysis = analyzeSlowTraces(collectionResult.traceEvidence().traces(), alert, interfaceEntries);

        List<EvidenceReference> evidenceRefs = new ArrayList<>();
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

        Set<String> insufficientEvidence = new LinkedHashSet<>();
        if (!collectionResult.traceEvidence().collected()) {
            insufficientEvidence.add(defaultIfBlank(
                    collectionResult.traceEvidence().missingReason(),
                    "Trace evidence is unavailable, so downstream slow-point confirmation is weaker."
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
                    "Historical baseline is unavailable for same-period latency comparison."
            ));
        }
        if (interfaceEntries.isEmpty()) {
            insufficientEvidence.add("No interface-log latency samples were collected for the latency alert window.");
        }

        List<RuleFinding> ruleFindings = new ArrayList<>();
        addLatencyProfileFinding(collectionResult.baselineEvidence().baseline(), alert, interfaceEntries, ruleFindings, evidenceRefs);

        CandidateRootCause downstreamCandidate = scoreDownstreamLatency(traceAnalysis, alert, interfaceEntries);
        CandidateRootCause databaseCandidate = scoreDatabaseLatency(metricAnomalies, collectionResult.runtimeLogEvidence().entries());
        CandidateRootCause dcsCandidate = scoreDcsLatency(metricAnomalies, collectionResult.runtimeLogEvidence().entries());
        CandidateRootCause containerCpuCandidate = scoreContainerCpu(metricAnomalies);
        CandidateRootCause memoryCandidate = scoreContainerMemory(metricAnomalies);
        CandidateRootCause gcCandidate = scoreGc(metricAnomalies);
        CandidateRootCause instanceCandidate = scoreInstanceState(metricAnomalies);
        CandidateRootCause trafficSpikeCandidate = scoreTrafficSpike(collectionResult.baselineEvidence().baseline(), alert, traceAnalysis, metricAnomalies);

        List<CandidateRootCause> candidateRootCauses = new ArrayList<>();
        addCandidate(candidateRootCauses, downstreamCandidate);
        addCandidate(candidateRootCauses, databaseCandidate);
        addCandidate(candidateRootCauses, dcsCandidate);
        addCandidate(candidateRootCauses, containerCpuCandidate);
        addCandidate(candidateRootCauses, memoryCandidate);
        addCandidate(candidateRootCauses, gcCandidate);
        addCandidate(candidateRootCauses, instanceCandidate);
        addCandidate(candidateRootCauses, trafficSpikeCandidate);

        candidateRootCauses = candidateRootCauses.stream()
                .sorted(this::compareCandidates)
                .toList();

        if (downstreamCandidate.score() >= 40) {
            ruleFindings.add(new RuleFinding(
                    "DOWNSTREAM_RPC_LATENCY",
                    downstreamCandidate.confidence(),
                    "The same downstream interface appears as the slowest span across sampled slow traces.",
                    downstreamCandidate.evidenceIds()
            ));
        }
        if (traceAnalysis.currentServiceInternalSlow()) {
            String evidenceId = "latency.internal.1";
            evidenceRefs.add(new EvidenceReference(
                    evidenceId,
                    "RULE_CONTEXT",
                    "Current interface latency is high while child spans do not dominate the latency budget."
            ));
            ruleFindings.add(new RuleFinding(
                    "CURRENT_SERVICE_INTERNAL_LATENCY",
                    0.55,
                    "Current-service latency appears higher than downstream child-span latency.",
                    List.of(evidenceId)
            ));
        }
        if (trafficSpikeCandidate.score() >= 25) {
            ruleFindings.add(new RuleFinding(
                    "TRAFFIC_SPIKE",
                    trafficSpikeCandidate.confidence(),
                    "Current request count is significantly higher than the baseline request-count P99.",
                    trafficSpikeCandidate.evidenceIds()
            ));
        }

        candidateRootCauses.stream()
                .filter(candidate -> candidate.score() >= 20)
                .filter(candidate -> !"DOWNSTREAM_RPC_LATENCY".equals(candidate.causeType()))
                .filter(candidate -> !"TRAFFIC_SPIKE".equals(candidate.causeType()))
                .forEach(candidate -> ruleFindings.add(new RuleFinding(
                        candidate.causeType(),
                        candidate.confidence(),
                        candidate.reasoning(),
                        candidate.evidenceIds()
                )));

        return new RuleAnalysisEvidence(
                List.of(),
                List.of(),
                traceAnalysis.traceObservations(),
                traceAnalysis.downstreamObservations(),
                metricAnomalies,
                deduplicateFindings(ruleFindings),
                candidateRootCauses,
                deduplicateEvidenceRefs(evidenceRefs),
                List.copyOf(insufficientEvidence)
        );
    }

    private void addLatencyProfileFinding(
            InterfaceBaseline baseline,
            AlertEvent alert,
            List<InterfaceLogEntry> interfaceEntries,
            List<RuleFinding> ruleFindings,
            List<EvidenceReference> evidenceRefs
    ) {
        long currentAvgLatency = resolveCurrentAverageLatency(alert, interfaceEntries);
        long currentP99Latency = resolveCurrentP99Latency(alert, interfaceEntries);
        Long baselineAvgP99 = baseline == null ? null : baseline.avgLatencyP99Ms();
        Long baselineP99 = baseline == null ? null : baseline.p99LatencyP99Ms();

        String evidenceId = "latency.profile.1";
        evidenceRefs.add(new EvidenceReference(
                evidenceId,
                "LATENCY_PROFILE",
                "Current average and P99 latency compared with baseline P99 levels."
        ));

        if (baselineAvgP99 != null && baselineP99 != null
                && currentAvgLatency > baselineAvgP99
                && currentP99Latency > baselineP99) {
            ruleFindings.add(new RuleFinding(
                    "BROAD_LATENCY_DEGRADATION",
                    0.75,
                    "Average latency and P99 latency both exceed the comparable baseline P99 window.",
                    List.of(evidenceId)
            ));
            return;
        }
        if (baselineP99 != null && currentP99Latency > baselineP99) {
            ruleFindings.add(new RuleFinding(
                    "TAIL_LATENCY_DEGRADATION",
                    0.55,
                    "P99 latency exceeds the baseline while average latency is not broadly elevated.",
                    List.of(evidenceId)
            ));
        }
    }

    private TraceAnalysis analyzeSlowTraces(
            List<TraceRecord> traces,
            AlertEvent alert,
            List<InterfaceLogEntry> interfaceEntries
    ) {
        Map<String, Long> interfaceLatencyByTraceId = new LinkedHashMap<>();
        for (InterfaceLogEntry entry : interfaceEntries) {
            if (entry.traceId() == null || entry.traceId().isBlank()) {
                continue;
            }
            interfaceLatencyByTraceId.put(entry.traceId(), entry.latencyMs());
        }

        List<TraceObservation> traceObservations = new ArrayList<>();
        Map<String, DownstreamStats> downstreamStats = new LinkedHashMap<>();
        boolean hasUnresolvedParents = false;
        boolean currentServiceInternalSlow = false;
        int traceIndex = 1;
        for (TraceRecord trace : traces) {
            TraceTree tree = traceTopologyAnalyzer.buildTraceTree(trace.entries());
            hasUnresolvedParents = hasUnresolvedParents || !tree.unresolvedParentSpanIds().isEmpty();
            TraceSpan slowestSpan = tree.slowestSpan().orElse(null);
            if (slowestSpan == null) {
                continue;
            }
            String traceId = defaultIfBlank(trace.traceId(), tree.traceId());
            String evidenceId = "trace.slow." + traceIndex++;
            traceObservations.add(new TraceObservation(
                    evidenceId,
                    traceId,
                    "SLOWEST_SPAN",
                    slowestSpan.spanId(),
                    slowestSpan.serviceName(),
                    slowestSpan.interfaceName(),
                    slowestSpan.errorCode(),
                    slowestSpan.success(),
                    slowestSpan.latencyMs(),
                    "Slow trace observation for the current sample."
            ));

            if (!isCurrentSpan(slowestSpan, alert)) {
                String key = downstreamKey(slowestSpan);
                DownstreamStats stats = downstreamStats.computeIfAbsent(
                        key,
                        ignored -> new DownstreamStats(slowestSpan.serviceName(), slowestSpan.interfaceName())
                );
                stats.traceIds.add(traceId);
                stats.totalLatencyMs += slowestSpan.latencyMs();
            } else {
                currentServiceInternalSlow = true;
            }

            long currentInterfaceLatency = interfaceLatencyByTraceId.getOrDefault(traceId, slowestSpan.latencyMs());
            if (!isCurrentSpan(slowestSpan, alert) && currentInterfaceLatency > slowestSpan.latencyMs() * 2) {
                currentServiceInternalSlow = true;
            }
        }

        long traceCount = traces.size();
        List<DownstreamObservation> downstreamObservations = new ArrayList<>();
        int downstreamIndex = 1;
        for (DownstreamStats stats : downstreamStats.values().stream()
                .sorted(Comparator.comparingInt((DownstreamStats value) -> value.traceIds.size()).reversed()
                        .thenComparing(value -> defaultIfBlank(value.serviceName, ""))
                        .thenComparing(value -> defaultIfBlank(value.interfaceName, "")))
                .toList()) {
            downstreamObservations.add(new DownstreamObservation(
                    "trace.slow.aggregation." + downstreamIndex++,
                    stats.serviceName,
                    stats.interfaceName,
                    stats.traceIds.size(),
                    share(stats.traceIds.size(), traceCount),
                    false,
                    stats.traceIds.isEmpty() ? 0L : Math.round((double) stats.totalLatencyMs / stats.traceIds.size()),
                    "The downstream interface repeatedly appears as the slowest span in sampled traces."
            ));
        }
        return new TraceAnalysis(traceObservations, downstreamObservations, hasUnresolvedParents, currentServiceInternalSlow);
    }

    private CandidateRootCause scoreDownstreamLatency(
            TraceAnalysis traceAnalysis,
            AlertEvent alert,
            List<InterfaceLogEntry> interfaceEntries
    ) {
        DownstreamObservation dominant = traceAnalysis.downstreamObservations().isEmpty()
                ? null
                : traceAnalysis.downstreamObservations().get(0);
        if (dominant == null) {
            return candidate("DOWNSTREAM_RPC_LATENCY", 0, List.of(), "No downstream slow-span concentration was observed.");
        }

        long currentAvgLatency = resolveCurrentAverageLatency(alert, interfaceEntries);
        int score = 0;
        List<String> evidenceIds = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        if (dominant.share() >= 0.5) {
            score += 35;
            evidenceIds.add(dominant.evidenceId());
            reasons.add("the same downstream interface is the slowest span in >= 50% sampled traces");
        }
        if (currentAvgLatency > 0 && dominant.averageLatencyMs() > currentAvgLatency * 0.5) {
            score += 20;
            evidenceIds.add(dominant.evidenceId());
            reasons.add("the downstream span contributes more than half of the current interface average latency");
        }
        if (dominant.traceCount() >= 2) {
            score += 15;
            evidenceIds.add(dominant.evidenceId());
            reasons.add("multiple traces point to the same downstream interface");
        }
        return candidate(
                "DOWNSTREAM_RPC_LATENCY",
                score,
                evidenceIds,
                reasons.isEmpty() ? "No downstream latency concentration was found." : String.join("; ", reasons) + "."
        );
    }

    private CandidateRootCause scoreDatabaseLatency(
            List<MetricAnomaly> metricAnomalies,
            List<RuntimeLogEntry> runtimeLogs
    ) {
        int score = 0;
        List<String> evidenceIds = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        List<MetricAnomaly> dbAnomalies = metricAnomalies.stream()
                .filter(anomaly -> "DATABASE".equals(anomaly.resourceType()))
                .toList();
        if (dbAnomalies.stream().anyMatch(anomaly -> anomaly.metric().contains("slow"))) {
            score += 25;
            evidenceIds.addAll(dbAnomalies.stream()
                    .filter(anomaly -> anomaly.metric().contains("slow"))
                    .map(MetricAnomaly::evidenceId)
                    .toList());
            reasons.add("database slow SQL / slow log anomalies were detected");
        }
        if (dbAnomalies.stream().anyMatch(anomaly ->
                anomaly.metric().contains("writeLatency")
                        || anomaly.metric().contains("lock")
                        || anomaly.metric().contains("connection"))) {
            score += 20;
            evidenceIds.addAll(dbAnomalies.stream()
                    .filter(anomaly -> anomaly.metric().contains("writeLatency")
                            || anomaly.metric().contains("lock")
                            || anomaly.metric().contains("connection"))
                    .map(MetricAnomaly::evidenceId)
                    .toList());
            reasons.add("database write-latency / lock / connection anomalies were detected");
        }
        if (containsKeyword(runtimeLogs, SQL_KEYWORDS)) {
            score += 20;
            reasons.add("runtime logs contain SQL / DAO / DB timeout keywords");
        }
        return candidate(
                "DATABASE_SLOW_QUERY",
                score,
                evidenceIds,
                reasons.isEmpty() ? "No database latency evidence was found." : String.join("; ", reasons) + "."
        );
    }

    private CandidateRootCause scoreDcsLatency(
            List<MetricAnomaly> metricAnomalies,
            List<RuntimeLogEntry> runtimeLogs
    ) {
        int score = 0;
        List<String> evidenceIds = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        List<MetricAnomaly> dcsAnomalies = metricAnomalies.stream()
                .filter(anomaly -> "DCS".equals(anomaly.resourceType()))
                .toList();
        if (dcsAnomalies.stream().anyMatch(anomaly ->
                anomaly.metric().contains("p99") || anomaly.metric().contains("avgLatency"))) {
            score += 25;
            evidenceIds.addAll(dcsAnomalies.stream()
                    .filter(anomaly -> anomaly.metric().contains("p99") || anomaly.metric().contains("avgLatency"))
                    .map(MetricAnomaly::evidenceId)
                    .toList());
            reasons.add("DCS latency anomalies were detected");
        }
        if (dcsAnomalies.stream().anyMatch(anomaly -> anomaly.metric().contains("slowQuery"))) {
            score += 20;
            evidenceIds.addAll(dcsAnomalies.stream()
                    .filter(anomaly -> anomaly.metric().contains("slowQuery"))
                    .map(MetricAnomaly::evidenceId)
                    .toList());
            reasons.add("DCS slow-query anomalies were detected");
        }
        if (dcsAnomalies.stream().anyMatch(anomaly -> anomaly.metric().contains("clientFailure"))) {
            score += 15;
            evidenceIds.addAll(dcsAnomalies.stream()
                    .filter(anomaly -> anomaly.metric().contains("clientFailure"))
                    .map(MetricAnomaly::evidenceId)
                    .toList());
            reasons.add("DCS client failures were detected");
        }
        if (containsKeyword(runtimeLogs, DCS_KEYWORDS)) {
            score += 15;
            reasons.add("runtime logs contain Redis / DCS / cache timeout keywords");
        }
        return candidate(
                "DCS_LATENCY_OR_FAILURE",
                score,
                evidenceIds,
                reasons.isEmpty() ? "No DCS latency evidence was found." : String.join("; ", reasons) + "."
        );
    }

    private CandidateRootCause scoreContainerCpu(List<MetricAnomaly> metricAnomalies) {
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
            score += 15;
            evidenceIds.addAll(metricAnomalies.stream()
                    .filter(anomaly -> "CSE".equals(anomaly.resourceType()))
                    .map(MetricAnomaly::evidenceId)
                    .toList());
            reasons.add("CSE usage is elevated");
        }
        return candidate(
                "CONTAINER_CPU_PRESSURE",
                score,
                evidenceIds,
                reasons.isEmpty() ? "No container CPU pressure evidence was found." : String.join("; ", reasons) + "."
        );
    }

    private CandidateRootCause scoreContainerMemory(List<MetricAnomaly> metricAnomalies) {
        int score = 0;
        List<String> evidenceIds = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        if (metricAnomalies.stream().anyMatch(anomaly ->
                "CONTAINER".equals(anomaly.resourceType()) && anomaly.metric().contains("memory"))) {
            score += 20;
            evidenceIds.addAll(metricAnomalies.stream()
                    .filter(anomaly -> "CONTAINER".equals(anomaly.resourceType()) && anomaly.metric().contains("memory"))
                    .map(MetricAnomaly::evidenceId)
                    .toList());
            reasons.add("container memory usage exceeds the configured threshold");
        }
        if (metricAnomalies.stream().anyMatch(anomaly ->
                "CONTAINER".equals(anomaly.resourceType()) && anomaly.metric().contains("fullGc"))) {
            score += 15;
            reasons.add("memory pressure is accompanied by GC pressure");
        }
        return candidate(
                "CONTAINER_MEMORY_PRESSURE",
                score,
                evidenceIds,
                reasons.isEmpty() ? "No container memory-pressure evidence was found." : String.join("; ", reasons) + "."
        );
    }

    private CandidateRootCause scoreGc(List<MetricAnomaly> metricAnomalies) {
        int score = 0;
        List<String> evidenceIds = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        if (metricAnomalies.stream().anyMatch(anomaly -> anomaly.metric().contains("fullGc"))) {
            score += 35;
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
            reasons.add("GC pressure overlaps with elevated memory usage");
        }
        return candidate(
                "GC_PRESSURE",
                score,
                evidenceIds,
                reasons.isEmpty() ? "No GC pressure evidence was found." : String.join("; ", reasons) + "."
        );
    }

    private CandidateRootCause scoreInstanceState(List<MetricAnomaly> metricAnomalies) {
        List<String> evidenceIds = metricAnomalies.stream()
                .filter(anomaly -> anomaly.metric().contains("instanceState"))
                .map(MetricAnomaly::evidenceId)
                .toList();
        if (evidenceIds.isEmpty()) {
            return candidate("INSTANCE_STARTING_OR_FAILED", 0, List.of(), "No instance-state anomaly was found.");
        }
        int score = 30;
        List<String> reasons = new ArrayList<>(List.of("starting or failed instances were detected"));
        if (metricAnomalies.stream().anyMatch(anomaly -> anomaly.evidenceId().contains("instance_cpu_skew"))) {
            score += 20;
            reasons.add("the anomalous instance also shows resource skew");
        }
        return candidate("INSTANCE_STARTING_OR_FAILED", score, evidenceIds, String.join("; ", reasons) + ".");
    }

    private CandidateRootCause scoreTrafficSpike(
            InterfaceBaseline baseline,
            AlertEvent alert,
            TraceAnalysis traceAnalysis,
            List<MetricAnomaly> metricAnomalies
    ) {
        Double requestCount = alert.triggerValues() == null ? null : alert.triggerValues().requestCount();
        Double requestCountP99 = baseline == null ? null : baseline.requestCountP99();
        if (requestCount == null || requestCountP99 == null || requestCount <= requestCountP99 * 2) {
            return candidate("TRAFFIC_SPIKE", 0, List.of(), "Request count is not above baseline spike threshold.");
        }

        List<String> evidenceIds = new ArrayList<>(List.of("latency.traffic.1"));
        int score = 25;
        List<String> reasons = new ArrayList<>(List.of("request count is above baseline P99 * 2"));
        boolean accompanyingPressure = !traceAnalysis.downstreamObservations().isEmpty()
                || metricAnomalies.stream().anyMatch(anomaly ->
                "CONTAINER".equals(anomaly.resourceType()) || "CSE".equals(anomaly.resourceType()));
        if (accompanyingPressure) {
            score += 20;
            reasons.add("traffic spike overlaps with downstream or service-side pressure");
        }
        return candidate("TRAFFIC_SPIKE", score, evidenceIds, String.join("; ", reasons) + ".");
    }

    private void addCandidate(List<CandidateRootCause> target, CandidateRootCause candidate) {
        if (candidate.score() > 0) {
            target.add(candidate);
        }
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
            case "DOWNSTREAM_RPC_LATENCY" -> 0;
            case "DATABASE_SLOW_QUERY" -> 1;
            case "DCS_LATENCY_OR_FAILURE" -> 2;
            case "CONTAINER_CPU_PRESSURE" -> 3;
            case "CONTAINER_MEMORY_PRESSURE" -> 4;
            case "GC_PRESSURE" -> 5;
            case "INSTANCE_STARTING_OR_FAILED" -> 6;
            case "TRAFFIC_SPIKE" -> 7;
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

    private long resolveCurrentAverageLatency(AlertEvent alert, List<InterfaceLogEntry> interfaceEntries) {
        if (alert.triggerValues() != null && alert.triggerValues().avgLatencyMs() != null) {
            return alert.triggerValues().avgLatencyMs();
        }
        if (interfaceEntries.isEmpty()) {
            return 0L;
        }
        return Math.round(interfaceEntries.stream().mapToLong(InterfaceLogEntry::latencyMs).average().orElse(0.0));
    }

    private long resolveCurrentP99Latency(AlertEvent alert, List<InterfaceLogEntry> interfaceEntries) {
        if (alert.triggerValues() != null && alert.triggerValues().p99LatencyMs() != null) {
            return alert.triggerValues().p99LatencyMs();
        }
        if (interfaceEntries.isEmpty()) {
            return 0L;
        }
        List<Long> latencies = interfaceEntries.stream()
                .map(InterfaceLogEntry::latencyMs)
                .sorted()
                .toList();
        int index = Math.max(0, (int) Math.ceil(latencies.size() * 0.99) - 1);
        return latencies.get(index);
    }

    private boolean containsKeyword(List<RuntimeLogEntry> runtimeLogs, List<String> keywords) {
        return runtimeLogs.stream()
                .map(RuntimeLogEntry::message)
                .filter(Objects::nonNull)
                .map(message -> message.toLowerCase(Locale.ROOT))
                .anyMatch(message -> keywords.stream().anyMatch(message::contains));
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

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
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

    private static final class DownstreamStats {
        private final String serviceName;
        private final String interfaceName;
        private final Set<String> traceIds = new LinkedHashSet<>();
        private long totalLatencyMs;

        private DownstreamStats(String serviceName, String interfaceName) {
            this.serviceName = serviceName;
            this.interfaceName = interfaceName;
        }
    }

    private record TraceAnalysis(
            List<TraceObservation> traceObservations,
            List<DownstreamObservation> downstreamObservations,
            boolean hasUnresolvedParents,
            boolean currentServiceInternalSlow
    ) {
    }
}
