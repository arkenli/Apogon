package com.company.aiops.analysis.rule.log;

import com.company.aiops.alert.model.AlertEvent;
import com.company.aiops.analysis.evidence.ProviderCollectionResult;
import com.company.aiops.analysis.evidence.RuntimeLogEvidence;
import com.company.aiops.analysis.evidence.TraceRecord;
import com.company.aiops.analysis.result.EvidenceReference;
import com.company.aiops.analysis.rule.model.CandidateRootCause;
import com.company.aiops.analysis.rule.model.RuleAnalysisEvidence;
import com.company.aiops.analysis.rule.model.RuleFinding;
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
 * Deterministic phase-5 analyzer for service runtime error-log alerts.
 */
@Component
public class DefaultErrorLogRuleAnalyzer implements ErrorLogRuleAnalyzer {

    private static final String CURRENT_SERVICE_RUNTIME_EXCEPTION = "CURRENT_SERVICE_RUNTIME_EXCEPTION";
    private static final String SINGLE_INSTANCE_ABNORMAL = "SINGLE_INSTANCE_ABNORMAL";
    private static final String UNKNOWN = "UNKNOWN";
    private static final String NO_MATCHED_ERROR_RUNTIME_LOGS =
            "No matched ERROR runtime logs were available for service error-log analysis.";

    @Override
    public RuleAnalysisEvidence analyze(ProviderCollectionResult collectionResult) {
        RuntimeLogEvidence runtimeLogEvidence = collectionResult.runtimeLogEvidence();
        List<EvidenceReference> evidenceRefs = new ArrayList<>();
        List<String> queryAttemptIds = addQueryAttemptRefs(runtimeLogEvidence, evidenceRefs);

        if (!runtimeLogEvidence.collected() || runtimeLogEvidence.entries().isEmpty()) {
            return unknownForMissingRuntimeLogs(runtimeLogEvidence, evidenceRefs, queryAttemptIds);
        }

        List<IndexedRuntimeLog> indexedLogs = indexRuntimeLogs(runtimeLogEvidence.entries(), evidenceRefs);
        if (indexedLogs.isEmpty()) {
            return unknownForMissingRuntimeLogs(runtimeLogEvidence, evidenceRefs, queryAttemptIds);
        }
        List<IndexedRuntimeLog> errorLogs = indexedLogs.stream()
                .filter(indexedLog -> isErrorLevel(indexedLog.entry()))
                .toList();
        List<TraceFailure> currentServiceTraceFailures = indexCurrentServiceTraceFailures(collectionResult, evidenceRefs);

        List<RuleFinding> ruleFindings = new ArrayList<>();
        List<CandidateRootCause> candidateRootCauses = new ArrayList<>();
        Set<String> insufficientEvidence = new LinkedHashSet<>();
        if (!collectionResult.traceEvidence().collected()) {
            insufficientEvidence.add(defaultIfBlank(
                    collectionResult.traceEvidence().missingReason(),
                    "Trace evidence is unavailable, so current-service failure confirmation is weaker."
            ));
        }

        CandidateRootCause currentServiceException = scoreCurrentServiceRuntimeException(errorLogs, currentServiceTraceFailures);
        if (currentServiceException.score() > 0) {
            candidateRootCauses.add(currentServiceException);
            ruleFindings.add(new RuleFinding(
                    CURRENT_SERVICE_RUNTIME_EXCEPTION,
                    currentServiceException.confidence(),
                    currentServiceException.reasoning(),
                    currentServiceException.evidenceIds()
            ));
        }

        CandidateRootCause singleInstance = scoreSingleInstanceAbnormal(errorLogs, evidenceRefs);
        if (singleInstance.score() > 0) {
            candidateRootCauses.add(singleInstance);
            ruleFindings.add(new RuleFinding(
                    SINGLE_INSTANCE_ABNORMAL,
                    singleInstance.confidence(),
                    singleInstance.reasoning(),
                    singleInstance.evidenceIds()
            ));
        }

        if (candidateRootCauses.isEmpty()) {
            if (errorLogs.isEmpty()) {
                insufficientEvidence.add(NO_MATCHED_ERROR_RUNTIME_LOGS);
                CandidateRootCause unknown = candidate(
                        UNKNOWN,
                        10,
                        List.of(indexedLogs.get(0).evidenceId()),
                        NO_MATCHED_ERROR_RUNTIME_LOGS
                );
                candidateRootCauses.add(unknown);
                ruleFindings.add(new RuleFinding(
                        UNKNOWN,
                        unknown.confidence(),
                        unknown.reasoning(),
                        unknown.evidenceIds()
                ));
            } else {
                CandidateRootCause unknown = candidate(
                        UNKNOWN,
                        10,
                        List.of(indexedLogs.get(0).evidenceId()),
                        "Matched ERROR runtime logs were found, but no high-confidence deterministic rule matched."
                );
                candidateRootCauses.add(unknown);
                ruleFindings.add(new RuleFinding(
                        UNKNOWN,
                        unknown.confidence(),
                        unknown.reasoning(),
                        unknown.evidenceIds()
                ));
            }
        }

        candidateRootCauses = candidateRootCauses.stream()
                .sorted(this::compareCandidates)
                .toList();

        return new RuleAnalysisEvidence(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                deduplicateFindings(ruleFindings),
                candidateRootCauses,
                deduplicateEvidenceRefs(evidenceRefs),
                List.copyOf(insufficientEvidence)
        );
    }

    private List<String> addQueryAttemptRefs(
            RuntimeLogEvidence runtimeLogEvidence,
            List<EvidenceReference> evidenceRefs
    ) {
        List<String> evidenceIds = new ArrayList<>();
        int index = 1;
        for (RuntimeLogEvidence.QueryAttempt attempt : runtimeLogEvidence.queryAttempts()) {
            if (attempt == null) {
                continue;
            }
            String evidenceId = defaultIfBlank(attempt.evidenceId(), "runtime.query.attempt." + index);
            evidenceRefs.add(new EvidenceReference(
                    evidenceId,
                    "RUNTIME_LOG_QUERY",
                    "Runtime log query attempt level=%s hitCount=%d selected=%s."
                            .formatted(defaultIfBlank(attempt.level(), "UNKNOWN"), attempt.hitCount(), attempt.selected())
            ));
            evidenceIds.add(evidenceId);
            index++;
        }
        return evidenceIds;
    }

    private RuleAnalysisEvidence unknownForMissingRuntimeLogs(
            RuntimeLogEvidence runtimeLogEvidence,
            List<EvidenceReference> evidenceRefs,
            List<String> queryAttemptIds
    ) {
        String reason = defaultIfBlank(
                runtimeLogEvidence.missingReason(),
                NO_MATCHED_ERROR_RUNTIME_LOGS
        );
        String missingEvidenceId = "runtime.query.missing";
        evidenceRefs.add(new EvidenceReference(
                missingEvidenceId,
                "RUNTIME_LOG_QUERY",
                reason
        ));
        List<String> evidenceIds = new ArrayList<>(queryAttemptIds);
        evidenceIds.add(missingEvidenceId);

        CandidateRootCause candidate = candidate(
                UNKNOWN,
                10,
                evidenceIds,
                reason
        );
        RuleFinding finding = new RuleFinding(
                UNKNOWN,
                candidate.confidence(),
                reason,
                candidate.evidenceIds()
        );
        return new RuleAnalysisEvidence(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(finding),
                List.of(candidate),
                deduplicateEvidenceRefs(evidenceRefs),
                List.of(reason)
        );
    }

    private List<IndexedRuntimeLog> indexRuntimeLogs(
            List<RuntimeLogEntry> entries,
            List<EvidenceReference> evidenceRefs
    ) {
        List<RuntimeLogEntry> orderedEntries = entries.stream()
                .filter(Objects::nonNull)
                .sorted(runtimeLogComparator())
                .toList();
        List<IndexedRuntimeLog> indexedLogs = new ArrayList<>(orderedEntries.size());
        int index = 1;
        for (RuntimeLogEntry entry : orderedEntries) {
            String evidenceId = "runtime.log." + index++;
            indexedLogs.add(new IndexedRuntimeLog(evidenceId, entry));
            evidenceRefs.add(new EvidenceReference(
                    evidenceId,
                    "RUNTIME_LOG",
                    "Matched runtime log level=%s instance=%s traceId=%s."
                            .formatted(
                                    defaultIfBlank(entry.level(), "UNKNOWN"),
                                    defaultIfBlank(entry.instanceId(), "UNKNOWN"),
                                    defaultIfBlank(entry.traceId(), "UNKNOWN")
                            )
            ));
        }
        return indexedLogs;
    }

    private List<TraceFailure> indexCurrentServiceTraceFailures(
            ProviderCollectionResult collectionResult,
            List<EvidenceReference> evidenceRefs
    ) {
        AlertEvent alert = collectionResult.context().alert();
        List<TraceFailure> failures = new ArrayList<>();
        int index = 1;
        for (TraceRecord trace : collectionResult.traceEvidence().traces()) {
            if (trace == null) {
                continue;
            }
            for (InterfaceLogEntry entry : trace.entries()) {
                if (entry == null) {
                    continue;
                }
                if (entry.success() || !Objects.equals(alert.serviceName(), entry.serviceName())) {
                    continue;
                }
                String evidenceId = "trace.failure." + index++;
                failures.add(new TraceFailure(evidenceId, trace.traceId(), entry));
                evidenceRefs.add(new EvidenceReference(
                        evidenceId,
                        "TRACE",
                        "Current service trace span failed for service=%s interface=%s traceId=%s."
                                .formatted(
                                        defaultIfBlank(entry.serviceName(), "UNKNOWN"),
                                        defaultIfBlank(entry.interfaceName(), "UNKNOWN"),
                                        defaultIfBlank(defaultIfBlank(trace.traceId(), entry.traceId()), "UNKNOWN")
                                )
                ));
            }
        }
        return failures;
    }

    private CandidateRootCause scoreCurrentServiceRuntimeException(
            List<IndexedRuntimeLog> errorLogs,
            List<TraceFailure> currentServiceTraceFailures
    ) {
        if (errorLogs.isEmpty()) {
            return candidate(
                    CURRENT_SERVICE_RUNTIME_EXCEPTION,
                    0,
                    List.of(),
                    "No matched ERROR runtime logs were available."
            );
        }

        boolean hasExceptionType = errorLogs.stream()
                .map(IndexedRuntimeLog::entry)
                .map(RuntimeLogEntry::exceptionType)
                .anyMatch(this::hasText);
        boolean hasCurrentServiceTraceFailure = !currentServiceTraceFailures.isEmpty();
        if (!hasExceptionType && !hasCurrentServiceTraceFailure) {
            return candidate(
                    CURRENT_SERVICE_RUNTIME_EXCEPTION,
                    0,
                    List.of(),
                    "No runtime exception type or current-service trace failure was observed."
            );
        }

        List<String> evidenceIds = new ArrayList<>();
        evidenceIds.add(errorLogs.get(0).evidenceId());
        errorLogs.stream()
                .filter(indexedLog -> hasText(indexedLog.entry().exceptionType()))
                .map(IndexedRuntimeLog::evidenceId)
                .forEach(evidenceIds::add);
        currentServiceTraceFailures.stream()
                .map(TraceFailure::evidenceId)
                .forEach(evidenceIds::add);

        int score = hasExceptionType && hasCurrentServiceTraceFailure ? 90 : 70;
        List<String> reasons = new ArrayList<>();
        if (hasExceptionType) {
            reasons.add("matched ERROR runtime logs carry exceptionType");
        }
        if (hasCurrentServiceTraceFailure) {
            reasons.add("sampled traces show the current service span failed");
        }
        return candidate(
                CURRENT_SERVICE_RUNTIME_EXCEPTION,
                score,
                evidenceIds,
                String.join("; ", reasons) + "."
        );
    }

    private CandidateRootCause scoreSingleInstanceAbnormal(
            List<IndexedRuntimeLog> errorLogs,
            List<EvidenceReference> evidenceRefs
    ) {
        if (errorLogs.size() <= 1) {
            return candidate(
                    SINGLE_INSTANCE_ABNORMAL,
                    0,
                    List.of(),
                    "Only one matched ERROR runtime log was available, so instance concentration is inconclusive."
            );
        }

        Map<String, Long> countsByInstance = new LinkedHashMap<>();
        for (IndexedRuntimeLog indexedLog : errorLogs) {
            String instanceId = indexedLog.entry().instanceId();
            if (!hasText(instanceId)) {
                continue;
            }
            countsByInstance.merge(instanceId, 1L, Long::sum);
        }
        if (countsByInstance.size() != 1 || countsByInstance.values().iterator().next() != errorLogs.size()) {
            return candidate(
                    SINGLE_INSTANCE_ABNORMAL,
                    0,
                    List.of(),
                    "Matched ERROR runtime logs were not concentrated on a single instance."
            );
        }

        String instanceId = countsByInstance.keySet().iterator().next();
        String evidenceId = "runtime.instance.1";
        evidenceRefs.add(new EvidenceReference(
                evidenceId,
                "RUNTIME_LOG",
                "All matched ERROR runtime logs are concentrated on instance=%s.".formatted(instanceId)
        ));
        return candidate(
                SINGLE_INSTANCE_ABNORMAL,
                60,
                List.of(evidenceId),
                "All matched ERROR runtime logs are concentrated on one instance."
        );
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
        int byPriority = Integer.compare(candidatePriority(left.causeType()), candidatePriority(right.causeType()));
        if (byPriority != 0) {
            return byPriority;
        }
        return left.causeType().compareTo(right.causeType());
    }

    private int candidatePriority(String causeType) {
        return switch (causeType) {
            case CURRENT_SERVICE_RUNTIME_EXCEPTION -> 0;
            case SINGLE_INSTANCE_ABNORMAL -> 1;
            case UNKNOWN -> 99;
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

    private boolean isErrorLevel(RuntimeLogEntry entry) {
        return "ERROR".equals(defaultIfBlank(entry.level(), "").toUpperCase(Locale.ROOT));
    }

    private Comparator<RuntimeLogEntry> runtimeLogComparator() {
        return Comparator
                .comparingInt((RuntimeLogEntry entry) -> severityRank(entry.level()))
                .thenComparing(RuntimeLogEntry::timestamp, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(entry -> valueOrBlank(entry.serviceName()))
                .thenComparing(entry -> valueOrBlank(entry.traceId()))
                .thenComparing(entry -> valueOrBlank(entry.message()));
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

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private String valueOrBlank(String value) {
        return value == null ? "" : value;
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

    private record IndexedRuntimeLog(String evidenceId, RuntimeLogEntry entry) {
    }

    private record TraceFailure(String evidenceId, String traceId, InterfaceLogEntry entry) {
    }
}
