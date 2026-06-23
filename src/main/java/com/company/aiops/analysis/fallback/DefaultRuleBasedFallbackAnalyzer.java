package com.company.aiops.analysis.fallback;

import com.company.aiops.alert.model.AlertEvent;
import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.alert.model.AlertTriggerValues;
import com.company.aiops.analysis.bundle.CompressedEvidenceBundle;
import com.company.aiops.analysis.evidence.ProviderCollectionResult;
import com.company.aiops.analysis.result.AnalysisMode;
import com.company.aiops.analysis.result.AnalysisResult;
import com.company.aiops.analysis.result.EvidenceReference;
import com.company.aiops.analysis.result.ImpactAssessment;
import com.company.aiops.analysis.result.MissingEvidence;
import com.company.aiops.analysis.result.Recommendation;
import com.company.aiops.analysis.result.RootCauseHypothesis;
import com.company.aiops.analysis.rule.model.CandidateRootCause;
import com.company.aiops.analysis.rule.model.DownstreamObservation;
import com.company.aiops.analysis.rule.model.ErrorCodeAggregate;
import com.company.aiops.analysis.rule.model.RuleAnalysisEvidence;
import com.company.aiops.analysis.rule.model.RuleFinding;
import com.company.aiops.common.config.AiopsProperties;
import com.company.aiops.provider.model.ResourceMapping;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic stage-7 implementation that turns rule evidence into a readable, auditable final
 * result whenever LLM is disabled or unavailable.
 *
 * <p>The class intentionally centralizes confidence adjustment, summary wording, evidence
 * backfilling, and recommendation mapping so those rules do not leak into the worker.</p>
 */
@Service
public class DefaultRuleBasedFallbackAnalyzer implements RuleBasedFallbackAnalyzer {

    private static final int MAX_ROOT_CAUSES = 5;

    private final AiopsProperties aiopsProperties;

    public DefaultRuleBasedFallbackAnalyzer(AiopsProperties aiopsProperties) {
        this.aiopsProperties = aiopsProperties;
    }

    @Override
    public AnalysisResult analyze(RuleBasedAnalysisRequest request) {
        ProviderCollectionResult collection = request.collection();
        AlertEvent alert = collection.context().alert();
        RuleAnalysisEvidence ruleAnalysis = request.ruleAnalysis() == null
                ? RuleAnalysisEvidence.empty()
                : request.ruleAnalysis();

        List<EvidenceReference> evidenceRefs = buildEvidenceReferences(
                collection,
                ruleAnalysis,
                request.compressedEvidence()
        );
        boolean degradedByProviderFailure = collection.degradedByProviderFailure();
        boolean degraded = request.degradedMode() || degradedByProviderFailure;
        String degradedReason = resolveDegradedReason(request, degraded);
        List<String> insufficientEvidence = buildInsufficientEvidence(request, ruleAnalysis, degradedReason);
        List<RootCauseHypothesis> rootCauses = buildRootCauses(
                request,
                ruleAnalysis,
                evidenceRefs,
                insufficientEvidence
        );
        ImpactAssessment impact = buildImpactAssessment(
                alert,
                request,
                rootCauses,
                degraded,
                degradedReason
        );
        List<Recommendation> recommendations = buildRecommendations(
                alert,
                request,
                ruleAnalysis,
                rootCauses,
                impact
        );
        double overallConfidence = calculateOverallConfidence(
                rootCauses,
                request,
                degradedByProviderFailure,
                insufficientEvidence
        );

        return new AnalysisResult(
                request.taskId(),
                alert.alertId(),
                alert.category(),
                request.analysisMode(),
                buildSummary(alert, request, rootCauses, impact, degradedReason),
                impact,
                rootCauses,
                recommendations,
                evidenceRefs,
                resolveMissingEvidence(request),
                insufficientEvidence,
                null,
                false,
                request.llmStatus(),
                degraded,
                degradedByProviderFailure,
                degradedReason,
                aiopsProperties.llm().fallbackRuleEngineVersion(),
                overallConfidence,
                Instant.now()
        );
    }

    private List<EvidenceReference> buildEvidenceReferences(
            ProviderCollectionResult collection,
            RuleAnalysisEvidence ruleAnalysis,
            CompressedEvidenceBundle compressedEvidence
    ) {
        Map<String, EvidenceReference> references = new LinkedHashMap<>();
        if (compressedEvidence != null) {
            for (EvidenceReference evidenceRef : compressedEvidence.evidenceRefs()) {
                references.putIfAbsent(evidenceRef.evidenceId(), evidenceRef);
            }
        } else {
            addCollectionEvidenceReferences(references, collection);
            for (EvidenceReference evidenceRef : ruleAnalysis.evidenceRefs()) {
                references.putIfAbsent(evidenceRef.evidenceId(), evidenceRef);
            }
        }

        int missingIndex = 1;
        for (MissingEvidence missingEvidence : collection.missingEvidence()) {
            String evidenceId = "missing.provider." + safeIdSegment(missingEvidence.providerName()) + "." + missingIndex++;
            references.putIfAbsent(evidenceId, new EvidenceReference(
                    evidenceId,
                    "MISSING_EVIDENCE",
                    "Missing provider evidence: %s (%s).".formatted(
                            defaultIfBlank(missingEvidence.providerName(), "unknown-provider"),
                            defaultIfBlank(missingEvidence.reason(), "no reason")
                    )
            ));
        }

        if (references.isEmpty()) {
            references.put(
                    "alert.context.1",
                    new EvidenceReference(
                            "alert.context.1",
                            "ALERT",
                            "Current alert context is available even though no detailed provider evidence was collected."
                    )
            );
        }
        return List.copyOf(references.values());
    }

    private void addCollectionEvidenceReferences(
            Map<String, EvidenceReference> references,
            ProviderCollectionResult collection
    ) {
        references.put("provider.collection.window", new EvidenceReference(
                "provider.collection.window",
                "PROVIDER_COLLECTION",
                "Provider collection executed for the configured alert window."
        ));
        if (collection.baselineEvidence().collected()) {
            references.put("provider.collection.baseline", new EvidenceReference(
                    "provider.collection.baseline",
                    "BASELINE",
                    "Historical baseline data was collected for the comparable time window."
            ));
        }
        if (collection.interfaceLogEvidence().collected()) {
            references.put("provider.collection.interfaceLogs", new EvidenceReference(
                    "provider.collection.interfaceLogs",
                    "INTERFACE_LOG",
                    "Interface-log samples were collected for the alerting interface."
            ));
        }
        if (collection.traceEvidence().collected()) {
            references.put("provider.collection.traces", new EvidenceReference(
                    "provider.collection.traces",
                    "TRACE",
                    "Trace samples were collected for the current alert."
            ));
        }
        if (collection.runtimeLogEvidence().collected()) {
            references.put("provider.collection.runtimeLogs", new EvidenceReference(
                    "provider.collection.runtimeLogs",
                    "RUNTIME_LOG",
                    "Runtime WARN/ERROR samples were collected."
            ));
        }
        if (collection.metricEvidence().collected()) {
            references.put("provider.collection.metrics", new EvidenceReference(
                    "provider.collection.metrics",
                    "METRIC",
                    "Service and infrastructure metrics were collected."
            ));
        }
    }

    private List<MissingEvidence> resolveMissingEvidence(RuleBasedAnalysisRequest request) {
        if (request.compressedEvidence() != null && !request.compressedEvidence().missingEvidence().isEmpty()) {
            return request.compressedEvidence().missingEvidence();
        }
        return request.collection().missingEvidence();
    }

    private String resolveDegradedReason(RuleBasedAnalysisRequest request, boolean degraded) {
        if (!degraded) {
            return null;
        }
        if (request.degradedReason() != null && !request.degradedReason().isBlank()) {
            return request.degradedReason();
        }
        if (request.collection().degradedReason() != null && !request.collection().degradedReason().isBlank()) {
            return request.collection().degradedReason();
        }
        MissingEvidence criticalMissing = request.collection().missingEvidence().stream()
                .filter(MissingEvidence::critical)
                .findFirst()
                .orElse(null);
        if (criticalMissing != null) {
            return "Critical provider evidence is unavailable: %s (%s).".formatted(
                    defaultIfBlank(criticalMissing.providerName(), "unknown-provider"),
                    defaultIfBlank(criticalMissing.reason(), "no reason")
            );
        }
        MissingEvidence firstMissing = request.collection().missingEvidence().stream().findFirst().orElse(null);
        if (firstMissing != null) {
            return "Provider evidence is incomplete: %s (%s).".formatted(
                    defaultIfBlank(firstMissing.providerName(), "unknown-provider"),
                    defaultIfBlank(firstMissing.reason(), "no reason")
            );
        }
        if (request.degradedMode()) {
            return "LLM is unavailable, so the final result was generated by the deterministic fallback rules.";
        }
        return null;
    }

    private List<String> buildInsufficientEvidence(
            RuleBasedAnalysisRequest request,
            RuleAnalysisEvidence ruleAnalysis,
            String degradedReason
    ) {
        Set<String> insufficientEvidence = new LinkedHashSet<>();
        if (request.analysisMode() == AnalysisMode.RULE_BASED_ONLY) {
            insufficientEvidence.add("LLM is disabled by configuration; the result is generated only from deterministic rule evidence.");
        } else {
            insufficientEvidence.add("LLM status %s triggered the stage-7 rule-based fallback path."
                    .formatted(defaultIfBlank(request.llmStatus(), "UNKNOWN")));
        }
        if (degradedReason != null && !degradedReason.isBlank()) {
            insufficientEvidence.add(degradedReason);
        }
        if (request.compressedEvidence() != null) {
            insufficientEvidence.addAll(request.compressedEvidence().insufficientEvidence());
        } else {
            insufficientEvidence.addAll(ruleAnalysis.insufficientEvidence());
        }
        if (request.collection().ruleFallbackRequired()) {
            insufficientEvidence.add("Critical provider evidence is missing, so the fallback conclusion is bounded by the remaining signals.");
        }
        return List.copyOf(insufficientEvidence);
    }

    private List<RootCauseHypothesis> buildRootCauses(
            RuleBasedAnalysisRequest request,
            RuleAnalysisEvidence ruleAnalysis,
            List<EvidenceReference> evidenceRefs,
            List<String> insufficientEvidence
    ) {
        List<RootCauseHypothesis> rootCauses = new ArrayList<>();
        for (CandidateRootCause candidate : ruleAnalysis.candidateRootCauses().stream()
                .limit(MAX_ROOT_CAUSES)
                .toList()) {
            rootCauses.add(new RootCauseHypothesis(
                    candidate.causeType(),
                    describeCandidate(candidate, ruleAnalysis),
                    adjustCandidateConfidence(candidate.confidence(), request),
                    resolveCandidateEvidenceIds(candidate, ruleAnalysis, evidenceRefs),
                    "RULE_ENGINE",
                    buildCandidateReasoning(candidate, request)
            ));
        }

        if (!rootCauses.isEmpty()) {
            return List.copyOf(rootCauses);
        }

        List<String> fallbackEvidenceIds = fallbackEvidenceIds(evidenceRefs);
        if (request.collection().context().alert().category() == AlertCategory.SERVICE_ERROR_LOG
                && request.collection().ruleFallbackRequired()) {
            return List.of(new RootCauseHypothesis(
                    "UNKNOWN",
                    "Could not locate ERROR runtime log context from the alert message queries.",
                    0.20,
                    fallbackEvidenceIds,
                    "RULE_ENGINE",
                    defaultIfBlank(
                            request.degradedReason(),
                            request.collection().degradedReason() != null
                                    ? request.collection().degradedReason()
                                    : firstLine(insufficientEvidence)
                    )
            ));
        }
        if (request.collection().ruleFallbackRequired()) {
            return List.of(new RootCauseHypothesis(
                    "INSUFFICIENT_CRITICAL_EVIDENCE",
                    "Critical provider evidence is missing, so the current conclusion is limited to evidence sufficiency assessment.",
                    0.20,
                    fallbackEvidenceIds,
                    "RULE_ENGINE",
                    defaultIfBlank(
                            request.degradedReason(),
                            request.collection().degradedReason() != null
                                    ? request.collection().degradedReason()
                                    : firstLine(insufficientEvidence)
                    )
            ));
        }
        return List.of(new RootCauseHypothesis(
                "NO_DOMINANT_RULE_SIGNAL",
                "Rule analysis did not find a single dominant root cause in the collected evidence.",
                request.analysisMode() == AnalysisMode.RULE_BASED_ONLY ? 0.35 : 0.25,
                fallbackEvidenceIds,
                "RULE_ENGINE",
                firstLine(insufficientEvidence)
        ));
    }

    private String describeCandidate(
            CandidateRootCause candidate,
            RuleAnalysisEvidence ruleAnalysis
    ) {
        return switch (candidate.causeType()) {
            case "BUSINESS_ERROR_CODE_NOT_MASKED" -> {
                ErrorCodeAggregate topError = ruleAnalysis.errorCodeDistribution().stream().findFirst().orElse(null);
                if (topError == null) {
                    yield "Failures are concentrated on business-semantic error messages, suggesting an unmasked business error code.";
                }
                yield "Failures are concentrated on errorCode %s and its error messages look like business-validation failures, suggesting an unmasked business error code."
                        .formatted(defaultIfBlank(topError.errorCode(), "UNKNOWN"));
            }
            case "DOWNSTREAM_RPC_FAILURE" -> {
                DownstreamObservation downstream = ruleAnalysis.downstreamObservations().stream().findFirst().orElse(null);
                if (downstream == null) {
                    yield "Sampled traces repeatedly fail at the same downstream call.";
                }
                yield "Sampled traces repeatedly fail at downstream %s/%s."
                        .formatted(
                                defaultIfBlank(downstream.serviceName(), "UNKNOWN"),
                                defaultIfBlank(downstream.interfaceName(), "UNKNOWN")
                        );
            }
            case "DOWNSTREAM_RPC_LATENCY" -> {
                DownstreamObservation downstream = ruleAnalysis.downstreamObservations().stream().findFirst().orElse(null);
                if (downstream == null) {
                    yield "Sampled slow traces repeatedly point to the same downstream latency hotspot.";
                }
                yield "Sampled slow traces repeatedly point to downstream %s/%s as the main latency hotspot."
                        .formatted(
                                defaultIfBlank(downstream.serviceName(), "UNKNOWN"),
                                defaultIfBlank(downstream.interfaceName(), "UNKNOWN")
                        );
            }
            case "DATABASE_RESOURCE_PRESSURE" -> "Database metrics or runtime logs indicate pressure that can amplify current failures.";
            case "DATABASE_SLOW_QUERY" -> "Database slow-query or connection signals are consistent with the current latency increase.";
            case "DCS_LATENCY_OR_FAILURE" -> "DCS latency or failure signals are strong enough to explain the current alert.";
            case "CONTAINER_CPU_PRESSURE" -> "Container CPU pressure is high enough to explain the current service degradation.";
            case "CONTAINER_MEMORY_PRESSURE" -> "Container memory pressure is high enough to explain the current latency increase.";
            case "GC_PRESSURE" -> "GC pressure overlaps with the current degradation and may be stretching response latency.";
            case "INSTANCE_STARTING_OR_FAILED" -> "An instance-state anomaly suggests part of the service is starting slowly or failing.";
            case "LOW_TRAFFIC_LIMITED_IMPACT" -> "The current request volume is below the limited-impact threshold, so the blast radius may still be small.";
            case "TRAFFIC_SPIKE" -> "Current traffic is materially above baseline and may be amplifying the observed latency.";
            case "CURRENT_SERVICE_RUNTIME_EXCEPTION" -> "Matched ERROR runtime logs and trace evidence point to a current-service runtime exception.";
            case "REPEATED_BUSINESS_LOG_ERROR" -> "The service error log looks like a repeated business action failure that may need alert-rule tuning.";
            case "UNKNOWN" -> "Rule analysis could not identify a confident root cause from the available service error-log evidence.";
            default -> "Rule analysis identified %s as a plausible cause based on the collected evidence."
                    .formatted(humanizeCauseType(candidate.causeType()));
        };
    }

    private double adjustCandidateConfidence(double confidence, RuleBasedAnalysisRequest request) {
        double adjusted = confidence;
        if (request.degradedMode()) {
            adjusted -= 0.10;
        }
        if (request.collection().degradedByProviderFailure()) {
            adjusted -= 0.05;
        }
        return clamp(adjusted, 0.20, 0.90);
    }

    private List<String> resolveCandidateEvidenceIds(
            CandidateRootCause candidate,
            RuleAnalysisEvidence ruleAnalysis,
            List<EvidenceReference> evidenceRefs
    ) {
        if (!candidate.evidenceIds().isEmpty()) {
            return candidate.evidenceIds().stream().filter(Objects::nonNull).distinct().toList();
        }
        for (RuleFinding ruleFinding : ruleAnalysis.ruleFindings()) {
            if (candidate.causeType().equals(ruleFinding.type()) && !ruleFinding.evidenceIds().isEmpty()) {
                return ruleFinding.evidenceIds();
            }
        }
        return fallbackEvidenceIds(evidenceRefs);
    }

    private List<String> fallbackEvidenceIds(List<EvidenceReference> evidenceRefs) {
        return evidenceRefs.stream()
                .map(EvidenceReference::evidenceId)
                .filter(Objects::nonNull)
                .limit(2)
                .toList();
    }

    private String buildCandidateReasoning(CandidateRootCause candidate, RuleBasedAnalysisRequest request) {
        String reasoning = defaultIfBlank(candidate.reasoning(), "No additional deterministic reasoning was recorded.");
        if (request.analysisMode() == AnalysisMode.RULE_BASED_ONLY) {
            return reasoning + " LLM is disabled, so this conclusion is based only on deterministic evidence.";
        }
        return reasoning + " LLM status %s forced the result onto the rule-based fallback path."
                .formatted(defaultIfBlank(request.llmStatus(), "UNKNOWN"));
    }

    private ImpactAssessment buildImpactAssessment(
            AlertEvent alert,
            RuleBasedAnalysisRequest request,
            List<RootCauseHypothesis> rootCauses,
            boolean degraded,
            String degradedReason
    ) {
        if (degraded && request.collection().ruleFallbackRequired()) {
            return new ImpactAssessment(
                    "UNKNOWN",
                    defaultIfBlank(
                            degradedReason,
                            "Critical provider evidence is missing, so the current impact cannot be quantified reliably."
                    )
            );
        }

        return switch (alert.category()) {
            case RPC_ERROR_RATE -> errorRateImpact(alert, request.compressedEvidence(), rootCauses, degraded, degradedReason);
            case RPC_LATENCY -> latencyImpact(alert, request.compressedEvidence(), degraded, degradedReason);
            case SERVICE_ERROR_LOG -> serviceErrorLogImpact(alert, request.compressedEvidence(), degraded, degradedReason);
            case UNKNOWN -> new ImpactAssessment(
                    "UNKNOWN",
                    degraded
                            ? defaultIfBlank(degradedReason, "Alert category is unknown and the result is degraded.")
                            : "Alert category is unknown, so only a bounded rule-based impact assessment is available."
            );
        };
    }

    private ImpactAssessment errorRateImpact(
            AlertEvent alert,
            CompressedEvidenceBundle compressedEvidence,
            List<RootCauseHypothesis> rootCauses,
            boolean degraded,
            String degradedReason
    ) {
        double requestCount = resolveRequestCount(alert, compressedEvidence);
        double failRate = resolveFailRate(alert, compressedEvidence);
        boolean lowTraffic = rootCauses.stream()
                .anyMatch(rootCause -> "LOW_TRAFFIC_LIMITED_IMPACT".equals(rootCause.causeType()));
        String level;
        if (lowTraffic || requestCount < aiopsProperties.analysis().limitedImpactRequestThreshold()) {
            level = "LOW";
        } else if (failRate >= 0.90 && requestCount >= aiopsProperties.analysis().limitedImpactRequestThreshold() * 3L) {
            level = "HIGH";
        } else if (failRate >= 0.70) {
            level = "MEDIUM";
        } else {
            level = "LOW";
        }

        String description = "requestCount=%s, failRate=%s."
                .formatted(formatNumber(requestCount), formatPercentage(failRate));
        if (lowTraffic) {
            description += " The current blast radius may still be limited because request volume is low.";
        }
        if (degraded && degradedReason != null && !degradedReason.isBlank()) {
            description += " " + degradedReason;
        }
        return new ImpactAssessment(level, description);
    }

    private ImpactAssessment serviceErrorLogImpact(
            AlertEvent alert,
            CompressedEvidenceBundle compressedEvidence,
            boolean degraded,
            String degradedReason
    ) {
        Double failureCount = resolveFailureCount(alert, compressedEvidence);
        String level = failureCount != null && failureCount <= 3.0 ? "LOW" : "MEDIUM";
        String description = failureCount != null && failureCount <= 3.0
                ? "failureCount=%s, so the current impact scope may be limited; still confirm the matched ERROR log traceId and instance distribution."
                .formatted(formatNumber(failureCount))
                : "Service ERROR runtime logs triggered the alert; confirm matched log count, traceId, and instance distribution.";
        if (degraded && degradedReason != null && !degradedReason.isBlank()) {
            description += " " + degradedReason;
        }
        return new ImpactAssessment(level, description);
    }

    private ImpactAssessment latencyImpact(
            AlertEvent alert,
            CompressedEvidenceBundle compressedEvidence,
            boolean degraded,
            String degradedReason
    ) {
        long avgLatencyMs = resolveAvgLatency(alert, compressedEvidence);
        long p99LatencyMs = resolveP99Latency(alert, compressedEvidence);
        double requestCount = resolveRequestCount(alert, compressedEvidence);
        String level;
        if (avgLatencyMs >= 5000 || p99LatencyMs >= 8000) {
            level = "HIGH";
        } else if (avgLatencyMs >= 3000 || p99LatencyMs >= 5000) {
            level = "MEDIUM";
        } else {
            level = requestCount < aiopsProperties.analysis().limitedImpactRequestThreshold() ? "LOW" : "MEDIUM";
        }

        String description = "requestCount=%s, avgLatencyMs=%s, p99LatencyMs=%s."
                .formatted(formatNumber(requestCount), avgLatencyMs, p99LatencyMs);
        if (degraded && degradedReason != null && !degradedReason.isBlank()) {
            description += " " + degradedReason;
        }
        return new ImpactAssessment(level, description);
    }

    private List<Recommendation> buildRecommendations(
            AlertEvent alert,
            RuleBasedAnalysisRequest request,
            RuleAnalysisEvidence ruleAnalysis,
            List<RootCauseHypothesis> rootCauses,
            ImpactAssessment impact
    ) {
        Map<String, Recommendation> recommendations = new LinkedHashMap<>();
        MissingEvidence firstMissing = request.collection().missingEvidence().stream().findFirst().orElse(null);
        if (firstMissing != null) {
            Recommendation recommendation = new Recommendation(
                    "P1",
                    "CHECK_PROVIDER",
                    "Inspect provider call logs and restore the missing evidence source before fully trusting the current result.",
                    defaultIfBlank(firstMissing.providerName(), alert.serviceName())
            );
            recommendations.put(recommendation.actionType() + "|" + recommendation.target(), recommendation);
        }

        for (RootCauseHypothesis rootCause : rootCauses.stream().limit(3).toList()) {
            Recommendation recommendation = recommendationForCause(alert, request, ruleAnalysis, rootCause, impact);
            recommendations.putIfAbsent(
                    recommendation.actionType() + "|" + recommendation.target(),
                    recommendation
            );
            if (alert.category() == AlertCategory.SERVICE_ERROR_LOG
                    && "CURRENT_SERVICE_RUNTIME_EXCEPTION".equals(rootCause.causeType())) {
                Recommendation traceRecommendation = new Recommendation(
                        "P1",
                        "CHECK_TRACE",
                        "Inspect the traceId extracted from the matched ERROR runtime log and identify the first failed span.",
                        firstEvidenceId(rootCause, alert.serviceName())
                );
                recommendations.putIfAbsent(
                        traceRecommendation.actionType() + "|" + traceRecommendation.target(),
                        traceRecommendation
                );
            }
        }

        if (recommendations.isEmpty()) {
            Recommendation recommendation = new Recommendation(
                    "P2",
                    "MANUAL_CONFIRMATION",
                    "Review the available evidence manually because the rule engine did not identify a strong dominant cause.",
                    alert.interfaceName()
            );
            recommendations.put(recommendation.actionType() + "|" + recommendation.target(), recommendation);
        }
        return List.copyOf(recommendations.values());
    }

    private Recommendation recommendationForCause(
            AlertEvent alert,
            RuleBasedAnalysisRequest request,
            RuleAnalysisEvidence ruleAnalysis,
            RootCauseHypothesis rootCause,
            ImpactAssessment impact
    ) {
        if (alert.category() == AlertCategory.SERVICE_ERROR_LOG) {
            if ("CURRENT_SERVICE_RUNTIME_EXCEPTION".equals(rootCause.causeType())) {
                return new Recommendation(
                        "P1",
                        "CHECK_LOG",
                        "Inspect the matchedLogQuery ERROR runtime log context, exceptionType, and stackTop.",
                        firstEvidenceId(rootCause, alert.serviceName())
                );
            }
            if ("REPEATED_BUSINESS_LOG_ERROR".equals(rootCause.causeType())) {
                return new Recommendation(
                        "P2",
                        "CHECK_LOG",
                        "Confirm whether the repeated business action failure is expected and whether the alert rule needs tuning.",
                        firstEvidenceId(rootCause, alert.serviceName())
                );
            }
            if ("UNKNOWN".equals(rootCause.causeType())) {
                return new Recommendation(
                        "P1",
                        "CHECK_LOG",
                        "Manually search runtime logs with normalizedLogQuery and cut matchedLogQuery values at ERROR level.",
                        alert.serviceName()
                );
            }
        }
        return switch (rootCause.causeType()) {
            case "BUSINESS_ERROR_CODE_NOT_MASKED" -> {
                String target = ruleAnalysis.errorCodeDistribution().stream()
                        .findFirst()
                        .map(ErrorCodeAggregate::errorCode)
                        .orElse(alert.interfaceName());
                yield new Recommendation(
                        "P1",
                        "EVALUATE_ERROR_CODE_MASKING",
                        "Confirm whether the dominant business-semantic error code should be masked or reclassified in the upstream alerting logic.",
                        target
                );
            }
            case "DOWNSTREAM_RPC_FAILURE", "DOWNSTREAM_RPC_LATENCY" -> {
                String target = ruleAnalysis.downstreamObservations().stream()
                        .findFirst()
                        .map(observation -> defaultIfBlank(observation.serviceName(), "UNKNOWN")
                                + "/"
                                + defaultIfBlank(observation.interfaceName(), "UNKNOWN"))
                        .orElse(alert.interfaceName());
                yield new Recommendation(
                        impact.level().equals("HIGH") ? "P0" : "P1",
                        "CHECK_DOWNSTREAM",
                        "Inspect the dominant downstream dependency because sampled traces consistently point to it.",
                        target
                );
            }
            case "DATABASE_RESOURCE_PRESSURE", "DATABASE_SLOW_QUERY" -> new Recommendation(
                    "P1",
                    "CHECK_DATABASE",
                    "Inspect slow SQL, lock, and connection signals for the mapped database before concluding the problem is in the current service code.",
                    firstMappedResource(request.compressedEvidence(), alert.serviceName(), "DATABASE")
            );
            case "DCS_LATENCY_OR_FAILURE" -> new Recommendation(
                    "P1",
                    "CHECK_DCS",
                    "Inspect DCS latency, client-failure, and slow-query signals for the mapped cache resource.",
                    firstMappedResource(request.compressedEvidence(), alert.serviceName(), "DCS")
            );
            case "CONTAINER_CPU_PRESSURE", "CONTAINER_MEMORY_PRESSURE", "GC_PRESSURE",
                    "INSTANCE_STARTING_OR_FAILED", "TRAFFIC_SPIKE" -> new Recommendation(
                    "P1",
                    "CHECK_METRIC",
                    "Inspect service-side instance and metric signals because the strongest rule-based evidence points to local resource pressure or traffic changes.",
                    alert.serviceName()
            );
            case "LOW_TRAFFIC_LIMITED_IMPACT" -> new Recommendation(
                    "P2",
                    "MANUAL_CONFIRMATION",
                    "Confirm the actual business impact with recent request samples because the current request volume is still below the configured limited-impact threshold.",
                    alert.interfaceName()
            );
            case "INSUFFICIENT_CRITICAL_EVIDENCE" -> new Recommendation(
                    "P1",
                    "CHECK_PROVIDER",
                    "Restore the critical provider data first; the current fallback result is intentionally conservative because the minimum evidence set is incomplete.",
                    request.collection().missingEvidence().stream()
                            .filter(MissingEvidence::critical)
                            .findFirst()
                            .map(MissingEvidence::providerName)
                            .orElse(alert.serviceName())
            );
            default -> new Recommendation(
                    "P2",
                    "MANUAL_CONFIRMATION",
                    "Review the cited evidence manually before taking action because the rule engine produced only a bounded deterministic conclusion.",
                    alert.interfaceName()
            );
        };
    }

    private String buildSummary(
            AlertEvent alert,
            RuleBasedAnalysisRequest request,
            List<RootCauseHypothesis> rootCauses,
            ImpactAssessment impact,
            String degradedReason
    ) {
        if (alert.category() == AlertCategory.SERVICE_ERROR_LOG) {
            return serviceErrorLogSummary(request, rootCauses, impact, degradedReason);
        }
        RootCauseHypothesis topRootCause = rootCauses.get(0);
        String modePrefix = switch (request.analysisMode()) {
            case RULE_BASED_ONLY -> "LLM is disabled, so this result is generated only from deterministic rule evidence.";
            case RULE_BASED_DEGRADED -> request.collection().ruleFallbackRequired()
                    ? "Critical provider evidence is missing, so the result is generated by the degraded rule-based fallback."
                    : "LLM status %s triggered the degraded rule-based fallback."
                    .formatted(defaultIfBlank(request.llmStatus(), "UNKNOWN"));
            case LLM_ASSISTED -> throw new IllegalStateException("LLM_ASSISTED is unsupported here.");
        };

        String summary = "%s Most likely cause for %s/%s: %s"
                .formatted(
                        modePrefix,
                        defaultIfBlank(alert.serviceName(), "UNKNOWN"),
                        defaultIfBlank(alert.interfaceName(), "UNKNOWN"),
                        topRootCause.description()
                );
        if (impact != null && impact.level() != null) {
            summary += " Impact=%s.".formatted(impact.level());
        }
        if (request.analysisMode() == AnalysisMode.RULE_BASED_DEGRADED
                && degradedReason != null
                && !degradedReason.isBlank()
                && !summary.contains(degradedReason)) {
            summary += " " + degradedReason;
        }
        return summary;
    }

    private String serviceErrorLogSummary(
            RuleBasedAnalysisRequest request,
            List<RootCauseHypothesis> rootCauses,
            ImpactAssessment impact,
            String degradedReason
    ) {
        RootCauseHypothesis topRootCause = rootCauses.get(0);
        String summary;
        if (request.analysisMode() == AnalysisMode.RULE_BASED_DEGRADED
                || request.collection().ruleFallbackRequired()) {
            summary = "Rule-based degraded service error log analysis could not locate ERROR runtime log context from alert msg.";
        } else {
            summary = switch (topRootCause.causeType()) {
                case "CURRENT_SERVICE_RUNTIME_EXCEPTION" ->
                        "Rule-based service error log analysis matched ERROR runtime log context and points to a current-service runtime exception.";
                case "DOWNSTREAM_RPC_FAILURE" ->
                        "Rule-based service error log analysis matched ERROR runtime log context and trace evidence points to downstream failure.";
                case "REPEATED_BUSINESS_LOG_ERROR" ->
                        "Rule-based service error log analysis indicates a repeated business log error or expected business failure pattern.";
                default ->
                        "Rule-based service error log analysis collected evidence but did not identify a confident root cause.";
            };
        }
        if (impact != null && impact.level() != null) {
            summary += " Impact=%s.".formatted(impact.level());
        }
        if (degradedReason != null && !degradedReason.isBlank() && !summary.contains(degradedReason)) {
            summary += " " + degradedReason;
        }
        return summary;
    }

    private double calculateOverallConfidence(
            List<RootCauseHypothesis> rootCauses,
            RuleBasedAnalysisRequest request,
            boolean degradedByProviderFailure,
            List<String> insufficientEvidence
    ) {
        double confidence = rootCauses.isEmpty() ? 0.25 : rootCauses.get(0).confidence();
        if (request.degradedMode()) {
            confidence -= 0.10;
        }
        if (degradedByProviderFailure) {
            confidence -= 0.05;
        }
        confidence -= Math.min(0.10, insufficientEvidence.size() * 0.02);
        return clamp(confidence, 0.20, 0.90);
    }

    private String firstMappedResource(
            CompressedEvidenceBundle compressedEvidence,
            String defaultTarget,
            String resourceType
    ) {
        ResourceMapping resourceMapping = compressedEvidence == null
                ? ResourceMapping.empty(defaultTarget, "UNKNOWN")
                : compressedEvidence.resourceMapping();
        return resourceMapping.resourceIds(resourceType).stream().findFirst().orElse(defaultTarget);
    }

    private double resolveRequestCount(AlertEvent alert, CompressedEvidenceBundle compressedEvidence) {
        if (compressedEvidence != null && compressedEvidence.alert() != null && compressedEvidence.alert().requestCount() != null) {
            return compressedEvidence.alert().requestCount();
        }
        AlertTriggerValues triggerValues = alert.triggerValues();
        return triggerValues == null || triggerValues.requestCount() == null ? 0.0 : triggerValues.requestCount();
    }

    private double resolveFailRate(AlertEvent alert, CompressedEvidenceBundle compressedEvidence) {
        if (compressedEvidence != null && compressedEvidence.alert() != null && compressedEvidence.alert().failRate() != null) {
            return compressedEvidence.alert().failRate();
        }
        AlertTriggerValues triggerValues = alert.triggerValues();
        return triggerValues == null || triggerValues.failRate() == null ? 0.0 : triggerValues.failRate();
    }

    private long resolveAvgLatency(AlertEvent alert, CompressedEvidenceBundle compressedEvidence) {
        if (compressedEvidence != null && compressedEvidence.alert() != null && compressedEvidence.alert().avgLatencyMs() != null) {
            return compressedEvidence.alert().avgLatencyMs();
        }
        AlertTriggerValues triggerValues = alert.triggerValues();
        return triggerValues == null || triggerValues.avgLatencyMs() == null ? 0L : triggerValues.avgLatencyMs();
    }

    private long resolveP99Latency(AlertEvent alert, CompressedEvidenceBundle compressedEvidence) {
        if (compressedEvidence != null && compressedEvidence.alert() != null && compressedEvidence.alert().p99LatencyMs() != null) {
            return compressedEvidence.alert().p99LatencyMs();
        }
        AlertTriggerValues triggerValues = alert.triggerValues();
        return triggerValues == null || triggerValues.p99LatencyMs() == null ? 0L : triggerValues.p99LatencyMs();
    }

    private Double resolveFailureCount(AlertEvent alert, CompressedEvidenceBundle compressedEvidence) {
        if (compressedEvidence != null && compressedEvidence.alert() != null && compressedEvidence.alert().failureCount() != null) {
            return compressedEvidence.alert().failureCount();
        }
        AlertTriggerValues triggerValues = alert.triggerValues();
        return triggerValues == null ? null : triggerValues.failureCount();
    }

    private String firstEvidenceId(RootCauseHypothesis rootCause, String defaultTarget) {
        return rootCause.evidenceIds().stream()
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(defaultIfBlank(defaultTarget, "UNKNOWN"));
    }

    private String humanizeCauseType(String causeType) {
        return causeType == null ? "unknown cause" : causeType.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private String safeIdSegment(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
    }

    private String formatPercentage(double ratio) {
        return String.format(Locale.ROOT, "%.1f%%", ratio * 100);
    }

    private String formatNumber(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private String firstLine(List<String> lines) {
        return lines.stream()
                .filter(Objects::nonNull)
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElse("Evidence is insufficient for a stronger deterministic conclusion.");
    }
}
