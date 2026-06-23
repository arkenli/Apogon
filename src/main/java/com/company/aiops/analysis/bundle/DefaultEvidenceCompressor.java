package com.company.aiops.analysis.bundle;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.alert.model.AlertEvent;
import com.company.aiops.alert.model.AlertTriggerValues;
import com.company.aiops.analysis.context.AnalysisOptions;
import com.company.aiops.analysis.evidence.ProviderCollectionResult;
import com.company.aiops.analysis.evidence.RuntimeLogEvidence;
import com.company.aiops.analysis.result.EvidenceReference;
import com.company.aiops.analysis.rule.model.CandidateRootCause;
import com.company.aiops.analysis.rule.model.DownstreamObservation;
import com.company.aiops.analysis.rule.model.ErrorCodeAggregate;
import com.company.aiops.analysis.rule.model.MetricAnomaly;
import com.company.aiops.analysis.rule.model.RuleAnalysisEvidence;
import com.company.aiops.analysis.rule.model.RuleFinding;
import com.company.aiops.analysis.rule.model.TraceObservation;
import com.company.aiops.analysis.rule.model.UidAggregate;
import com.company.aiops.provider.model.InterfaceLogEntry;
import com.company.aiops.provider.model.RuntimeLogEntry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Default phase-6 compressor.
 *
 * <p>The compressor keeps deterministic evidence ids stable by preserving source ordering whenever
 * possible, while trimming large collections according to {@link AnalysisOptions}. Referenced rule
 * evidence is preferred during trimming so candidate conclusions still point to materialized
 * evidence items.</p>
 */
@Component
public class DefaultEvidenceCompressor implements EvidenceCompressor {

    private static final String ALERT_REQUEST_COUNT_EVIDENCE_ID = "alert.request.count";
    private static final String ALERT_FAIL_RATE_EVIDENCE_ID = "alert.fail.rate";
    private static final String ALERT_AVG_LATENCY_EVIDENCE_ID = "alert.avg.latency";
    private static final String ALERT_P99_LATENCY_EVIDENCE_ID = "alert.p99.latency";
    private static final String ALERT_FAILURE_COUNT_EVIDENCE_ID = "alert.failure.count";
    private static final String ALERT_LOG_LEVEL_EVIDENCE_ID = "alert.log.level";
    private static final String ALERT_LOG_MESSAGE_EVIDENCE_ID = "alert.log.message";
    private static final String ALERT_NORMALIZED_LOG_QUERY_EVIDENCE_ID = "alert.log.normalized_query";
    private static final String BASELINE_REQUEST_COUNT_EVIDENCE_ID = "baseline.request.count";
    private static final String BASELINE_FAIL_RATE_EVIDENCE_ID = "baseline.fail.rate";
    private static final String BASELINE_AVG_LATENCY_EVIDENCE_ID = "baseline.avg.latency";
    private static final String BASELINE_P99_LATENCY_EVIDENCE_ID = "baseline.p99.latency";
    private static final String INTERFACE_REQUEST_COUNT_EVIDENCE_ID = "interface.request.count";
    private static final String INTERFACE_FAILURE_RATE_EVIDENCE_ID = "interface.failure.rate";
    private static final String INTERFACE_LATENCY_PROFILE_EVIDENCE_ID = "interface.latency.profile";

    @Override
    public CompressedEvidenceBundle compress(
            ProviderCollectionResult collectionResult,
            RuleAnalysisEvidence ruleAnalysisEvidence
    ) {
        ProviderCollectionResult collection = java.util.Objects.requireNonNull(collectionResult, "collectionResult");
        RuleAnalysisEvidence ruleAnalysis = ruleAnalysisEvidence == null
                ? RuleAnalysisEvidence.empty()
                : ruleAnalysisEvidence;
        AnalysisOptions options = collection.context().options();
        AlertEvent alert = collection.context().alert();
        Set<String> referencedEvidenceIds = referencedEvidenceIds(ruleAnalysis);
        List<String> insufficientEvidence = new ArrayList<>(ruleAnalysis.insufficientEvidence());

        List<ErrorCodeAggregate> topErrorCodes = limitWithReferencedPriority(
                ruleAnalysis.errorCodeDistribution(),
                options.maxErrorCodeSize(),
                ErrorCodeAggregate::evidenceId,
                referencedEvidenceIds,
                "errorCode distribution",
                insufficientEvidence
        );
        List<UidAggregate> topUids = limitWithReferencedPriority(
                ruleAnalysis.uidDistribution(),
                options.maxUidSize(),
                UidAggregate::evidenceId,
                referencedEvidenceIds,
                "uid distribution",
                insufficientEvidence
        );

        int traceLimit = traceLimit(alert.category(), options);
        List<TraceObservation> traceObservations = limitWithReferencedPriority(
                ruleAnalysis.traceObservations(),
                traceLimit,
                TraceObservation::evidenceId,
                referencedEvidenceIds,
                "trace observations",
                insufficientEvidence
        );
        List<String> sampledTraceIds = sampledTraceIds(collection, traceObservations, traceLimit, insufficientEvidence);

        List<CompressedEvidenceBundle.RuntimeLogSample> runtimeLogs = runtimeLogSamples(
                collection.runtimeLogEvidence().entries(),
                options.maxRuntimeLogSize(),
                options.runtimeLogMessageMaxChars(),
                insufficientEvidence
        );

        CompressedEvidenceBundle.AlertSnapshot alertSnapshot = alertSnapshot(alert, collection.interfaceLogEvidence().entries());
        CompressedEvidenceBundle.BaselineSnapshot baselineSnapshot = baselineSnapshot(collection);
        CompressedEvidenceBundle.InterfaceLogSnapshot interfaceLogSnapshot = interfaceLogSnapshot(
                collection,
                topErrorCodes,
                topUids,
                sampledTraceIds
        );
        CompressedEvidenceBundle.TraceSnapshot traceSnapshot = new CompressedEvidenceBundle.TraceSnapshot(
                collection.traceEvidence().collected(),
                collection.traceEvidence().missingReason(),
                sampledTraceIds,
                traceObservations,
                List.copyOf(ruleAnalysis.downstreamObservations())
        );
        CompressedEvidenceBundle.RuntimeLogSnapshot runtimeLogSnapshot = new CompressedEvidenceBundle.RuntimeLogSnapshot(
                collection.runtimeLogEvidence().collected(),
                collection.runtimeLogEvidence().missingReason(),
                runtimeLogs,
                collection.runtimeLogEvidence().messageQueryMatched(),
                collection.runtimeLogEvidence().matchedLogQuery(),
                runtimeLogQueryAttempts(collection.runtimeLogEvidence().queryAttempts()),
                collection.runtimeLogEvidence().sampledTraceIds()
        );
        CompressedEvidenceBundle.MetricSnapshot metricSnapshot = new CompressedEvidenceBundle.MetricSnapshot(
                collection.metricEvidence().collected(),
                collection.metricEvidence().missingReason(),
                collection.metricEvidence().containerMetrics(),
                collection.metricEvidence().cseMetrics(),
                collection.metricEvidence().databaseMetrics(),
                collection.metricEvidence().dcsMetrics(),
                collection.metricEvidence().mqMetrics(),
                List.copyOf(ruleAnalysis.metricAnomalies())
        );
        CompressedEvidenceBundle.RuleSnapshot ruleSnapshot = new CompressedEvidenceBundle.RuleSnapshot(
                ruleAnalysis.ruleFindings(),
                ruleAnalysis.candidateRootCauses()
        );

        Set<String> includedEvidenceIds = includedEvidenceIds(
                alertSnapshot,
                baselineSnapshot,
                interfaceLogSnapshot,
                traceSnapshot,
                runtimeLogSnapshot,
                metricSnapshot,
                ruleSnapshot
        );
        List<EvidenceReference> evidenceRefs = buildEvidenceReferences(
                ruleAnalysis,
                runtimeLogSnapshot.samples(),
                runtimeLogSnapshot.queryAttempts(),
                includedEvidenceIds
        );

        return new CompressedEvidenceBundle(
                alertSnapshot,
                baselineSnapshot,
                interfaceLogSnapshot,
                traceSnapshot,
                runtimeLogSnapshot,
                metricSnapshot,
                collection.context().resourceMapping(),
                ruleSnapshot,
                evidenceRefs,
                collection.missingEvidence(),
                deduplicateStrings(insufficientEvidence)
        );
    }

    private CompressedEvidenceBundle.AlertSnapshot alertSnapshot(AlertEvent alert, List<InterfaceLogEntry> interfaceEntries) {
        double derivedFailureRate = failureRate(interfaceEntries);
        AlertTriggerValues triggerValues = alert.triggerValues();
        return new CompressedEvidenceBundle.AlertSnapshot(
                alert.category(),
                alert.serviceName(),
                alert.interfaceName(),
                alert.cluster(),
                alert.metricOccurTime(),
                ALERT_REQUEST_COUNT_EVIDENCE_ID,
                triggerValues != null && triggerValues.requestCount() != null
                        ? triggerValues.requestCount()
                        : (double) interfaceEntries.size(),
                ALERT_FAIL_RATE_EVIDENCE_ID,
                triggerValues != null && triggerValues.failRate() != null
                        ? triggerValues.failRate()
                        : derivedFailureRate,
                ALERT_AVG_LATENCY_EVIDENCE_ID,
                triggerValues != null && triggerValues.avgLatencyMs() != null
                        ? triggerValues.avgLatencyMs()
                        : averageLatency(interfaceEntries),
                ALERT_P99_LATENCY_EVIDENCE_ID,
                triggerValues != null && triggerValues.p99LatencyMs() != null
                        ? triggerValues.p99LatencyMs()
                        : percentile(interfaceEntries, 0.99),
                ALERT_FAILURE_COUNT_EVIDENCE_ID,
                triggerValues == null ? null : triggerValues.failureCount(),
                ALERT_LOG_LEVEL_EVIDENCE_ID,
                triggerValues == null ? null : triggerValues.logLevel(),
                ALERT_LOG_MESSAGE_EVIDENCE_ID,
                triggerValues == null ? null : triggerValues.logMessage(),
                ALERT_NORMALIZED_LOG_QUERY_EVIDENCE_ID,
                triggerValues == null ? null : triggerValues.normalizedLogQuery()
        );
    }

    private CompressedEvidenceBundle.BaselineSnapshot baselineSnapshot(ProviderCollectionResult collection) {
        return new CompressedEvidenceBundle.BaselineSnapshot(
                collection.baselineEvidence().collected(),
                collection.baselineEvidence().missingReason(),
                BASELINE_REQUEST_COUNT_EVIDENCE_ID,
                BASELINE_FAIL_RATE_EVIDENCE_ID,
                BASELINE_AVG_LATENCY_EVIDENCE_ID,
                BASELINE_P99_LATENCY_EVIDENCE_ID,
                collection.baselineEvidence().baseline()
        );
    }

    private CompressedEvidenceBundle.InterfaceLogSnapshot interfaceLogSnapshot(
            ProviderCollectionResult collection,
            List<ErrorCodeAggregate> topErrorCodes,
            List<UidAggregate> topUids,
            List<String> sampledTraceIds
    ) {
        List<InterfaceLogEntry> entries = collection.interfaceLogEvidence().entries();
        long totalCount = entries.size();
        long failedCount = entries.stream().filter(entry -> !entry.success()).count();
        return new CompressedEvidenceBundle.InterfaceLogSnapshot(
                collection.interfaceLogEvidence().collected(),
                collection.interfaceLogEvidence().missingReason(),
                INTERFACE_REQUEST_COUNT_EVIDENCE_ID,
                totalCount,
                INTERFACE_FAILURE_RATE_EVIDENCE_ID,
                failedCount,
                totalCount == 0 ? 0.0 : (double) failedCount / totalCount,
                INTERFACE_LATENCY_PROFILE_EVIDENCE_ID,
                averageLatency(entries),
                percentile(entries, 0.95),
                percentile(entries, 0.99),
                maxLatency(entries),
                sampledTraceIds,
                topErrorCodes,
                topUids
        );
    }

    private List<String> sampledTraceIds(
            ProviderCollectionResult collection,
            List<TraceObservation> traceObservations,
            int traceLimit,
            List<String> insufficientEvidence
    ) {
        LinkedHashSet<String> sampledTraceIds = new LinkedHashSet<>();
        traceObservations.stream()
                .map(TraceObservation::traceId)
                .filter(this::hasText)
                .forEach(sampledTraceIds::add);
        collection.interfaceLogEvidence().sampledTraceIds().stream()
                .filter(this::hasText)
                .forEach(sampledTraceIds::add);

        List<String> orderedTraceIds = sampledTraceIds.stream().toList();
        if (orderedTraceIds.size() > traceLimit) {
            insufficientEvidence.add("sampled trace ids were truncated to the configured trace limit=%s.".formatted(traceLimit));
            return orderedTraceIds.subList(0, traceLimit);
        }
        return orderedTraceIds;
    }

    private List<CompressedEvidenceBundle.RuntimeLogSample> runtimeLogSamples(
            List<RuntimeLogEntry> runtimeLogs,
            int maxRuntimeLogSize,
            int runtimeLogMessageMaxChars,
            List<String> insufficientEvidence
    ) {
        List<RuntimeLogEntry> ordered = runtimeLogs.stream()
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator
                        .comparingInt((RuntimeLogEntry entry) -> severityRank(entry.level()))
                        .thenComparing(RuntimeLogEntry::timestamp, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(entry -> valueOrBlank(entry.serviceName()))
                        .thenComparing(entry -> valueOrBlank(entry.traceId()))
                        .thenComparing(entry -> valueOrBlank(entry.message())))
                .toList();

        if (ordered.size() > maxRuntimeLogSize) {
            insufficientEvidence.add("runtime log samples were truncated to the configured max-runtime-log-size=%s.".formatted(maxRuntimeLogSize));
        }

        boolean anyTruncatedMessage = false;
        List<CompressedEvidenceBundle.RuntimeLogSample> samples = new ArrayList<>();
        int index = 1;
        for (RuntimeLogEntry entry : ordered.stream().limit(maxRuntimeLogSize).toList()) {
            String truncatedMessage = truncate(entry.message(), runtimeLogMessageMaxChars);
            anyTruncatedMessage = anyTruncatedMessage || !java.util.Objects.equals(truncatedMessage, entry.message());
            samples.add(new CompressedEvidenceBundle.RuntimeLogSample(
                    "runtime.log." + index++,
                    entry.serviceName(),
                    entry.traceId(),
                    entry.level(),
                    truncatedMessage,
                    entry.timestamp()
            ));
        }
        if (anyTruncatedMessage) {
            insufficientEvidence.add("runtime log messages were truncated to the configured runtime-log-message-max-chars=%s.".formatted(runtimeLogMessageMaxChars));
        }
        return List.copyOf(samples);
    }

    private List<CompressedEvidenceBundle.RuntimeLogQueryAttempt> runtimeLogQueryAttempts(
            List<RuntimeLogEvidence.QueryAttempt> queryAttempts
    ) {
        return queryAttempts.stream()
                .filter(java.util.Objects::nonNull)
                .map(attempt -> new CompressedEvidenceBundle.RuntimeLogQueryAttempt(
                        attempt.evidenceId(),
                        attempt.query(),
                        attempt.level(),
                        attempt.hitCount(),
                        attempt.selected()
                ))
                .toList();
    }

    private List<EvidenceReference> buildEvidenceReferences(
            RuleAnalysisEvidence ruleAnalysis,
            List<CompressedEvidenceBundle.RuntimeLogSample> runtimeLogs,
            List<CompressedEvidenceBundle.RuntimeLogQueryAttempt> queryAttempts,
            Set<String> includedEvidenceIds
    ) {
        List<EvidenceReference> references = new ArrayList<>();
        addReferenceIfIncluded(references, includedEvidenceIds, ALERT_REQUEST_COUNT_EVIDENCE_ID, "ALERT", "Current alert request count extracted from the alert trigger or interface logs.");
        addReferenceIfIncluded(references, includedEvidenceIds, ALERT_FAIL_RATE_EVIDENCE_ID, "ALERT", "Current alert failure rate extracted from the alert trigger or interface logs.");
        addReferenceIfIncluded(references, includedEvidenceIds, ALERT_AVG_LATENCY_EVIDENCE_ID, "ALERT", "Current alert average latency extracted from the alert trigger or interface logs.");
        addReferenceIfIncluded(references, includedEvidenceIds, ALERT_P99_LATENCY_EVIDENCE_ID, "ALERT", "Current alert P99 latency extracted from the alert trigger or interface logs.");
        addReferenceIfIncluded(references, includedEvidenceIds, ALERT_FAILURE_COUNT_EVIDENCE_ID, "ALERT", "Current service-error-log failure count extracted from the alert trigger.");
        addReferenceIfIncluded(references, includedEvidenceIds, ALERT_LOG_LEVEL_EVIDENCE_ID, "ALERT", "Current service-error-log level extracted from the alert trigger.");
        addReferenceIfIncluded(references, includedEvidenceIds, ALERT_LOG_MESSAGE_EVIDENCE_ID, "ALERT", "Current service-error-log message extracted from the alert trigger.");
        addReferenceIfIncluded(references, includedEvidenceIds, ALERT_NORMALIZED_LOG_QUERY_EVIDENCE_ID, "ALERT", "Normalized service-error-log query extracted from the alert trigger.");
        addReferenceIfIncluded(references, includedEvidenceIds, BASELINE_REQUEST_COUNT_EVIDENCE_ID, "BASELINE", "Historical request-count baseline for the comparable time window.");
        addReferenceIfIncluded(references, includedEvidenceIds, BASELINE_FAIL_RATE_EVIDENCE_ID, "BASELINE", "Historical fail-rate baseline for the comparable time window.");
        addReferenceIfIncluded(references, includedEvidenceIds, BASELINE_AVG_LATENCY_EVIDENCE_ID, "BASELINE", "Historical average-latency baseline for the comparable time window.");
        addReferenceIfIncluded(references, includedEvidenceIds, BASELINE_P99_LATENCY_EVIDENCE_ID, "BASELINE", "Historical P99-latency baseline for the comparable time window.");
        addReferenceIfIncluded(references, includedEvidenceIds, INTERFACE_REQUEST_COUNT_EVIDENCE_ID, "INTERFACE_LOG", "Interface-log request count summary for the alert window.");
        addReferenceIfIncluded(references, includedEvidenceIds, INTERFACE_FAILURE_RATE_EVIDENCE_ID, "INTERFACE_LOG", "Interface-log failure-rate summary for the alert window.");
        addReferenceIfIncluded(references, includedEvidenceIds, INTERFACE_LATENCY_PROFILE_EVIDENCE_ID, "INTERFACE_LOG", "Interface-log latency profile summary for the alert window.");

        for (CompressedEvidenceBundle.RuntimeLogSample runtimeLog : runtimeLogs) {
            addReference(
                    references,
                    runtimeLog.evidenceId(),
                    "RUNTIME_LOG",
                    "Runtime log sample [%s] for traceId=%s.".formatted(
                            valueOrBlank(runtimeLog.level()),
                            valueOrBlank(runtimeLog.traceId())
                    )
            );
        }

        for (CompressedEvidenceBundle.RuntimeLogQueryAttempt queryAttempt : queryAttempts) {
            addReferenceIfIncluded(
                    references,
                    includedEvidenceIds,
                    queryAttempt.evidenceId(),
                    "RUNTIME_LOG_QUERY",
                    "Runtime log query attempt level=%s query=%s hitCount=%s selected=%s.".formatted(
                            valueOrBlank(queryAttempt.level()),
                            valueOrBlank(queryAttempt.query()),
                            queryAttempt.hitCount(),
                            queryAttempt.selected()
                    )
            );
        }

        ruleAnalysis.evidenceRefs().stream()
                .filter(reference -> includedEvidenceIds.contains(reference.evidenceId()))
                .forEach(references::add);

        for (String referencedId : referencedEvidenceIds(ruleAnalysis)) {
            if (!includedEvidenceIds.contains(referencedId)) {
                continue;
            }
            if (references.stream().noneMatch(reference -> reference.evidenceId().equals(referencedId))) {
                addReference(references, referencedId, "RULE_REFERENCE", "Rule analysis references this evidence id.");
            }
        }
        return deduplicateReferences(references);
    }

    private Set<String> includedEvidenceIds(
            CompressedEvidenceBundle.AlertSnapshot alert,
            CompressedEvidenceBundle.BaselineSnapshot baseline,
            CompressedEvidenceBundle.InterfaceLogSnapshot interfaceLogs,
            CompressedEvidenceBundle.TraceSnapshot traces,
            CompressedEvidenceBundle.RuntimeLogSnapshot runtimeLogs,
            CompressedEvidenceBundle.MetricSnapshot metrics,
            CompressedEvidenceBundle.RuleSnapshot ruleAnalysis
    ) {
        LinkedHashSet<String> included = new LinkedHashSet<>();
        if (alert.requestCount() != null) {
            addIfText(included, alert.requestCountEvidenceId());
        }
        if (alert.failRate() != null) {
            addIfText(included, alert.failRateEvidenceId());
        }
        if (alert.avgLatencyMs() != null) {
            addIfText(included, alert.avgLatencyEvidenceId());
        }
        if (alert.p99LatencyMs() != null) {
            addIfText(included, alert.p99LatencyEvidenceId());
        }
        if (alert.failureCount() != null) {
            addIfText(included, alert.failureCountEvidenceId());
        }
        if (alert.logLevel() != null) {
            addIfText(included, alert.logLevelEvidenceId());
        }
        if (alert.logMessage() != null) {
            addIfText(included, alert.logMessageEvidenceId());
        }
        if (alert.normalizedLogQuery() != null) {
            addIfText(included, alert.normalizedLogQueryEvidenceId());
        }
        if (baseline.collected()) {
            addIfText(included, baseline.requestCountEvidenceId());
            addIfText(included, baseline.failRateEvidenceId());
            addIfText(included, baseline.avgLatencyEvidenceId());
            addIfText(included, baseline.p99LatencyEvidenceId());
        }
        if (interfaceLogs.collected()) {
            addIfText(included, interfaceLogs.requestCountEvidenceId());
            addIfText(included, interfaceLogs.failureRateEvidenceId());
            addIfText(included, interfaceLogs.latencyEvidenceId());
        }
        interfaceLogs.topErrorCodes().stream().map(ErrorCodeAggregate::evidenceId).forEach(included::add);
        interfaceLogs.topUids().stream().map(UidAggregate::evidenceId).forEach(included::add);
        traces.observations().stream().map(TraceObservation::evidenceId).forEach(included::add);
        traces.downstreamObservations().stream().map(DownstreamObservation::evidenceId).forEach(included::add);
        runtimeLogs.samples().stream().map(CompressedEvidenceBundle.RuntimeLogSample::evidenceId).forEach(included::add);
        runtimeLogs.queryAttempts().stream()
                .map(CompressedEvidenceBundle.RuntimeLogQueryAttempt::evidenceId)
                .filter(this::hasText)
                .forEach(included::add);
        metrics.anomalies().stream().map(MetricAnomaly::evidenceId).forEach(included::add);
        ruleAnalysis.findings().stream()
                .map(RuleFinding::evidenceIds)
                .flatMap(List::stream)
                .filter(this::hasText)
                .forEach(included::add);
        ruleAnalysis.candidateRootCauses().stream()
                .map(CandidateRootCause::evidenceIds)
                .flatMap(List::stream)
                .filter(this::hasText)
                .forEach(included::add);
        return included;
    }

    private Set<String> referencedEvidenceIds(RuleAnalysisEvidence ruleAnalysis) {
        LinkedHashSet<String> referenced = new LinkedHashSet<>();
        ruleAnalysis.ruleFindings().stream()
                .map(RuleFinding::evidenceIds)
                .flatMap(List::stream)
                .filter(this::hasText)
                .forEach(referenced::add);
        ruleAnalysis.candidateRootCauses().stream()
                .map(CandidateRootCause::evidenceIds)
                .flatMap(List::stream)
                .filter(this::hasText)
                .forEach(referenced::add);
        return referenced;
    }

    private <T> List<T> limitWithReferencedPriority(
            List<T> items,
            int limit,
            Function<T, String> evidenceIdExtractor,
            Set<String> referencedEvidenceIds,
            String label,
            List<String> insufficientEvidence
    ) {
        if (items.size() <= limit) {
            return List.copyOf(items);
        }

        LinkedHashMap<String, T> selected = new LinkedHashMap<>();
        for (T item : items) {
            String evidenceId = evidenceIdExtractor.apply(item);
            if (referencedEvidenceIds.contains(evidenceId)) {
                selected.putIfAbsent(evidenceId, item);
                if (selected.size() >= limit) {
                    break;
                }
            }
        }
        for (T item : items) {
            if (selected.size() >= limit) {
                break;
            }
            selected.putIfAbsent(evidenceIdExtractor.apply(item), item);
        }
        insufficientEvidence.add("%s were truncated to the configured limit=%s.".formatted(label, limit));
        return List.copyOf(selected.values());
    }

    private List<EvidenceReference> deduplicateReferences(List<EvidenceReference> references) {
        Map<String, EvidenceReference> deduplicated = new LinkedHashMap<>();
        for (EvidenceReference reference : references) {
            deduplicated.putIfAbsent(reference.evidenceId(), reference);
        }
        return List.copyOf(deduplicated.values());
    }

    private List<String> deduplicateStrings(List<String> values) {
        return values.stream()
                .filter(this::hasText)
                .distinct()
                .toList();
    }

    private long averageLatency(List<InterfaceLogEntry> entries) {
        return entries.isEmpty()
                ? 0L
                : Math.round(entries.stream().mapToLong(InterfaceLogEntry::latencyMs).average().orElse(0.0));
    }

    private long maxLatency(List<InterfaceLogEntry> entries) {
        return entries.stream().mapToLong(InterfaceLogEntry::latencyMs).max().orElse(0L);
    }

    private double failureRate(List<InterfaceLogEntry> entries) {
        if (entries.isEmpty()) {
            return 0.0;
        }
        long failed = entries.stream().filter(entry -> !entry.success()).count();
        return (double) failed / entries.size();
    }

    private long percentile(List<InterfaceLogEntry> entries, double percentile) {
        if (entries.isEmpty()) {
            return 0L;
        }
        List<Long> latencies = entries.stream()
                .map(InterfaceLogEntry::latencyMs)
                .sorted()
                .toList();
        int index = Math.max(0, (int) Math.ceil(latencies.size() * percentile) - 1);
        return latencies.get(index);
    }

    private int traceLimit(AlertCategory category, AnalysisOptions options) {
        return switch (category) {
            case RPC_LATENCY -> options.maxSlowTraceSampleSize();
            case RPC_ERROR_RATE, SERVICE_ERROR_LOG, UNKNOWN -> options.maxTraceSampleSize();
        };
    }

    private String truncate(String message, int maxChars) {
        if (message == null || message.length() <= maxChars) {
            return message;
        }
        if (maxChars <= 3) {
            return message.substring(0, maxChars);
        }
        return message.substring(0, maxChars - 3) + "...";
    }

    private int severityRank(String level) {
        if ("ERROR".equalsIgnoreCase(level)) {
            return 0;
        }
        if ("WARN".equalsIgnoreCase(level)) {
            return 1;
        }
        return 2;
    }

    private void addReference(List<EvidenceReference> references, String evidenceId, String evidenceType, String description) {
        references.add(new EvidenceReference(evidenceId, evidenceType, description));
    }

    private void addReferenceIfIncluded(
            List<EvidenceReference> references,
            Set<String> includedEvidenceIds,
            String evidenceId,
            String evidenceType,
            String description
    ) {
        if (includedEvidenceIds.contains(evidenceId)) {
            addReference(references, evidenceId, evidenceType, description);
        }
    }

    private void addIfText(Set<String> values, String value) {
        if (hasText(value)) {
            values.add(value);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String valueOrBlank(String value) {
        return value == null ? "" : value;
    }
}
