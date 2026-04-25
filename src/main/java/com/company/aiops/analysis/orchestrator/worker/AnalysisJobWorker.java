package com.company.aiops.analysis.orchestrator.worker;

import com.company.aiops.alert.model.AlertEvent;
import com.company.aiops.analysis.bundle.CompressedEvidenceBundle;
import com.company.aiops.analysis.bundle.EvidenceCompressor;
import com.company.aiops.analysis.bundle.StoredEvidenceBundle;
import com.company.aiops.analysis.context.AnalysisContext;
import com.company.aiops.analysis.context.AnalysisContextFactory;
import com.company.aiops.analysis.evidence.ProviderCollectionResult;
import com.company.aiops.analysis.job.AnalysisJob;
import com.company.aiops.analysis.job.AnalysisJobStatus;
import com.company.aiops.analysis.orchestrator.state.AnalysisJobStateMachine;
import com.company.aiops.analysis.rule.RuleAnalysisService;
import com.company.aiops.analysis.rule.model.CandidateRootCause;
import com.company.aiops.analysis.rule.model.RuleAnalysisEvidence;
import com.company.aiops.analysis.result.AnalysisMode;
import com.company.aiops.analysis.result.AnalysisResult;
import com.company.aiops.analysis.result.EvidenceReference;
import com.company.aiops.analysis.result.ImpactAssessment;
import com.company.aiops.analysis.result.Recommendation;
import com.company.aiops.analysis.result.RootCauseHypothesis;
import com.company.aiops.common.config.AiopsProperties;
import com.company.aiops.common.exception.AiopsException;
import com.company.aiops.common.exception.ErrorCode;
import com.company.aiops.persistence.repository.AlertEventRepository;
import com.company.aiops.persistence.repository.EvidenceBundleRepository;
import com.company.aiops.provider.orchestrator.ProviderOrchestrator;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Async entrypoint that drives one task through the explicit lifecycle graph.
 *
 * <p>The worker now performs real provider collection, deterministic rule analysis, and phase-6
 * evidence compression. Only the later LLM and rule-fallback result-generation branches still use
 * placeholder outputs.</p>
 */
@Service
public class AnalysisJobWorker {

    private static final String STEP_INITIALIZE = "analysis.initialize-context";
    private static final String STEP_COLLECT = "analysis.collect-evidence";
    private static final String STEP_RULE = "analysis.rule-analyzing";
    private static final String STEP_COMPRESS = "analysis.compress-evidence";
    private static final String STEP_LLM = "analysis.llm-analyzing";
    private static final String STEP_RULE_FALLBACK = "analysis.rule-fallback";
    private static final String STEP_GENERATE = "analysis.generate-result";
    private static final String STEP_FAILED = "analysis.failed";

    private final AiopsProperties aiopsProperties;
    private final AnalysisJobStateMachine analysisJobStateMachine;
    private final AnalysisJobCompletionService analysisJobCompletionService;
    private final AlertEventRepository alertEventRepository;
    private final AnalysisContextFactory analysisContextFactory;
    private final ProviderOrchestrator providerOrchestrator;
    private final RuleAnalysisService ruleAnalysisService;
    private final EvidenceCompressor evidenceCompressor;
    private final EvidenceBundleRepository evidenceBundleRepository;

    public AnalysisJobWorker(
            AiopsProperties aiopsProperties,
            AnalysisJobStateMachine analysisJobStateMachine,
            AnalysisJobCompletionService analysisJobCompletionService,
            AlertEventRepository alertEventRepository,
            AnalysisContextFactory analysisContextFactory,
            ProviderOrchestrator providerOrchestrator,
            RuleAnalysisService ruleAnalysisService,
            EvidenceCompressor evidenceCompressor,
            EvidenceBundleRepository evidenceBundleRepository
    ) {
        this.aiopsProperties = aiopsProperties;
        this.analysisJobStateMachine = analysisJobStateMachine;
        this.analysisJobCompletionService = analysisJobCompletionService;
        this.alertEventRepository = alertEventRepository;
        this.analysisContextFactory = analysisContextFactory;
        this.providerOrchestrator = providerOrchestrator;
        this.ruleAnalysisService = ruleAnalysisService;
        this.evidenceCompressor = evidenceCompressor;
        this.evidenceBundleRepository = evidenceBundleRepository;
    }

    /**
     * Safe runtime default used immediately after webhook ingestion.
     */
    @Async("analysisJobExecutor")
    public CompletableFuture<Void> submit(String taskId) {
        processTask(taskId, AnalysisWorkerScenario.RULE_BASED_ONLY);
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Synchronous execution hook used by tests to exercise individual lifecycle branches.
     */
    public void processTask(String taskId, AnalysisWorkerScenario scenario) {
        try {
            AnalysisJob running = analysisJobStateMachine.transition(
                    taskId,
                    AnalysisJobStatus.RUNNING,
                    STEP_INITIALIZE,
                    "worker claimed task"
            );
            AlertEvent alert = loadAlert(running.alertId());
            AnalysisContext context = analysisContextFactory.create(alert);

            analysisJobStateMachine.transition(
                    taskId,
                    AnalysisJobStatus.COLLECTING_EVIDENCE,
                    STEP_COLLECT,
                    "collect provider evidence for the configured alert window"
            );
            ProviderCollectionResult collection = providerOrchestrator.collect(taskId, context);
            if (collection.ruleFallbackRequired()) {
                completeProviderFallback(taskId, collection);
                return;
            }

            analysisJobStateMachine.transition(
                    taskId,
                    AnalysisJobStatus.RULE_ANALYZING,
                    STEP_RULE,
                    "provider collection completed; run deterministic phase-5 rule analysis"
            );
            RuleAnalysisEvidence ruleAnalysis = ruleAnalysisService.analyze(collection);
            analysisJobStateMachine.transition(
                    taskId,
                    AnalysisJobStatus.COMPRESSING_EVIDENCE,
                    STEP_COMPRESS,
                    "compress rule-backed evidence and persist phase-6 evidence bundles"
            );
            CompressedEvidenceBundle compressedEvidence = evidenceCompressor.compress(collection, ruleAnalysis);
            persistEvidenceBundles(taskId, alert.alertId(), ruleAnalysis, compressedEvidence);

            switch (scenario) {
                case RULE_BASED_ONLY -> completeRuleOnly(taskId, collection, ruleAnalysis, compressedEvidence);
                case LLM_ASSISTED_SUCCESS -> completeLlmAssisted(taskId, collection, ruleAnalysis, compressedEvidence);
                case LLM_FALLBACK_TIMEOUT -> completeLlmFallback(taskId, collection, ruleAnalysis, compressedEvidence);
            }
        } catch (RuntimeException exception) {
            failIfPossible(taskId, exception);
            throw exception;
        }
    }

    private void completeRuleOnly(
            String taskId,
            ProviderCollectionResult collection,
            RuleAnalysisEvidence ruleAnalysis,
            CompressedEvidenceBundle compressedEvidence
    ) {
        analysisJobStateMachine.transition(
                taskId,
                AnalysisJobStatus.GENERATING_RESULT,
                STEP_GENERATE,
                "generate a provider-backed placeholder result"
        );
        analysisJobCompletionService.complete(taskId, buildPlaceholderResult(
                taskId,
                collection,
                AnalysisMode.RULE_BASED_ONLY,
                false,
                "NOT_REQUESTED",
                collection.degradedByProviderFailure(),
                null,
                ruleAnalysis,
                compressedEvidence
        ));
    }

    private void completeLlmAssisted(
            String taskId,
            ProviderCollectionResult collection,
            RuleAnalysisEvidence ruleAnalysis,
            CompressedEvidenceBundle compressedEvidence
    ) {
        analysisJobStateMachine.transition(
                taskId,
                AnalysisJobStatus.LLM_ANALYZING,
                STEP_LLM,
                "simulate successful LLM analysis after provider collection"
        );
        analysisJobStateMachine.transition(
                taskId,
                AnalysisJobStatus.GENERATING_RESULT,
                STEP_GENERATE,
                "generate a simulated LLM-assisted placeholder result"
        );
        analysisJobCompletionService.complete(taskId, buildPlaceholderResult(
                taskId,
                collection,
                AnalysisMode.LLM_ASSISTED,
                true,
                "SUCCESS",
                collection.degradedByProviderFailure(),
                null,
                ruleAnalysis,
                compressedEvidence
        ));
    }

    private void completeLlmFallback(
            String taskId,
            ProviderCollectionResult collection,
            RuleAnalysisEvidence ruleAnalysis,
            CompressedEvidenceBundle compressedEvidence
    ) {
        analysisJobStateMachine.transition(
                taskId,
                AnalysisJobStatus.LLM_ANALYZING,
                STEP_LLM,
                "simulate LLM call before fallback"
        );
        analysisJobStateMachine.transition(
                taskId,
                AnalysisJobStatus.RULE_FALLBACK_ANALYZING,
                STEP_RULE_FALLBACK,
                "simulated LLM timeout triggers rule fallback"
        );
        analysisJobStateMachine.transition(
                taskId,
                AnalysisJobStatus.GENERATING_RESULT,
                STEP_GENERATE,
                "generate degraded placeholder result after LLM timeout"
        );
        analysisJobCompletionService.complete(taskId, buildPlaceholderResult(
                taskId,
                collection,
                AnalysisMode.RULE_BASED_DEGRADED,
                false,
                "TIMEOUT",
                true,
                "LLM integration is not implemented yet, so the worker validated the fallback branch with persisted compressed evidence and a placeholder degraded result.",
                ruleAnalysis,
                compressedEvidence
        ));
    }

    private void completeProviderFallback(String taskId, ProviderCollectionResult collection) {
        analysisJobStateMachine.transition(
                taskId,
                AnalysisJobStatus.RULE_FALLBACK_ANALYZING,
                STEP_RULE_FALLBACK,
                collection.degradedReason()
        );
        analysisJobStateMachine.transition(
                taskId,
                AnalysisJobStatus.GENERATING_RESULT,
                STEP_GENERATE,
                "generate a provider-fallback placeholder result"
        );
        analysisJobCompletionService.complete(taskId, buildPlaceholderResult(
                taskId,
                collection,
                AnalysisMode.RULE_BASED_DEGRADED,
                false,
                "SKIPPED_BY_PROVIDER_FAILURE",
                true,
                collection.degradedReason(),
                null,
                null
        ));
    }

    private AlertEvent loadAlert(String alertId) {
        return alertEventRepository.findByAlertId(alertId)
                .orElseThrow(() -> new AiopsException(
                        ErrorCode.NOT_FOUND,
                        "alert event not found for analysis task: " + alertId
                ));
    }

    private void failIfPossible(String taskId, RuntimeException exception) {
        try {
            analysisJobStateMachine.fail(taskId, STEP_FAILED, summarizeException(exception));
        } catch (RuntimeException ignored) {
            // Keep the original exception as the primary failure signal.
        }
    }

    private String summarizeException(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private void persistEvidenceBundles(
            String taskId,
            String alertId,
            RuleAnalysisEvidence ruleAnalysis,
            CompressedEvidenceBundle compressedEvidence
    ) {
        Instant createdAt = Instant.now();
        Instant expireAt = createdAt.plus(aiopsProperties.analysis().evidenceRetentionDays(), ChronoUnit.DAYS);
        evidenceBundleRepository.save(StoredEvidenceBundle.rule(taskId, alertId, ruleAnalysis, createdAt, expireAt));
        evidenceBundleRepository.save(StoredEvidenceBundle.compressed(taskId, alertId, compressedEvidence, createdAt, expireAt));
    }

    private AnalysisResult buildPlaceholderResult(
            String taskId,
            ProviderCollectionResult collection,
            AnalysisMode analysisMode,
            boolean llmUsed,
            String llmStatus,
            boolean degraded,
            String degradedReason,
            RuleAnalysisEvidence ruleAnalysis,
            CompressedEvidenceBundle compressedEvidence
    ) {
        AlertEvent alert = collection.context().alert();
        boolean providerDegraded = collection.degradedByProviderFailure();
        boolean effectiveDegraded = degraded || providerDegraded;
        String effectiveDegradedReason = degradedReason != null ? degradedReason : collection.degradedReason();
        CandidateRootCause topCandidate = ruleAnalysis == null ? null : ruleAnalysis.topCandidateRootCause().orElse(null);
        List<EvidenceReference> evidenceRefs = compressedEvidence != null
                ? compressedEvidence.evidenceRefs()
                : buildEvidenceReferences(collection, ruleAnalysis);
        List<String> primaryEvidenceIds = topCandidate != null && !topCandidate.evidenceIds().isEmpty()
                ? topCandidate.evidenceIds()
                : compressedEvidence != null && !compressedEvidence.evidenceRefs().isEmpty()
                ? List.of(compressedEvidence.evidenceRefs().get(0).evidenceId())
                : collection.missingEvidence().isEmpty()
                ? List.of("provider.collection.window")
                : List.of("provider.collection.missing");
        String summary = switch (analysisMode) {
            case RULE_BASED_ONLY -> summarizeRuleStage(
                    providerDegraded,
                    ruleAnalysis,
                    "Phase-6 evidence compression completed, but LLM and rule-fallback result generation still emit placeholder output."
            );
            case LLM_ASSISTED -> summarizeRuleStage(
                    providerDegraded,
                    ruleAnalysis,
                    "The worker simulated the later LLM-assisted branch after persisting the phase-6 compressed evidence bundle."
            );
            case RULE_BASED_DEGRADED -> defaultIfBlank(
                    effectiveDegradedReason,
                    "Provider collection could not gather the minimum evidence required for normal analysis, so a degraded placeholder result was generated."
            );
        };

        return new AnalysisResult(
                taskId,
                alert.alertId(),
                alert.category(),
                analysisMode,
                summary,
                new ImpactAssessment(
                        "UNKNOWN",
                        providerDegraded
                                ? defaultIfBlank(
                                effectiveDegradedReason,
                                "Provider-backed placeholder result contains missing evidence and should be treated as degraded."
                        )
                                : ruleAnalysis == null || ruleAnalysis.ruleFindings().isEmpty()
                                ? "Provider collection and phase-6 compression completed, but later LLM/fallback result generation is still placeholder-backed."
                                : "Rule analysis and phase-6 compression completed, but later LLM/fallback result generation is still placeholder-backed."
                ),
                List.of(new RootCauseHypothesis(
                        topCandidate == null ? "UNKNOWN" : topCandidate.causeType(),
                        analysisMode == AnalysisMode.RULE_BASED_DEGRADED
                                ? defaultIfBlank(
                                effectiveDegradedReason,
                                "A degraded placeholder result was generated because key provider evidence was unavailable."
                        )
                                : topCandidate == null
                                ? "Phase-6 compressed evidence was produced for %s/%s, but later phases still use placeholder logic."
                                .formatted(alert.serviceName(), alert.interfaceName())
                                : "Phase-5 rule analysis scored %s with %s confidence, and phase-6 compressed evidence is ready for later phases."
                                .formatted(topCandidate.causeType(), topCandidate.confidence()),
                        effectiveDegraded ? 0.20 : topCandidate == null ? 0.35 : topCandidate.confidence(),
                        primaryEvidenceIds,
                        "RULE_ENGINE",
                        analysisMode == AnalysisMode.RULE_BASED_DEGRADED
                                ? "Critical evidence is missing, so the worker can only emit a degraded placeholder conclusion."
                                : topCandidate == null
                                ? "Provider evidence was collected, compressed, and indexed, but later phases are not implemented yet."
                                : defaultIfBlank(topCandidate.reasoning(), "Phase-5 rule scoring completed and phase-6 compression finished, but later phases are not implemented yet.")
                )),
                buildRecommendations(collection, analysisMode, alert, topCandidate),
                evidenceRefs,
                compressedEvidence != null ? compressedEvidence.missingEvidence() : collection.missingEvidence(),
                buildInsufficientEvidence(collection, ruleAnalysis, compressedEvidence),
                null,
                llmUsed,
                llmStatus,
                effectiveDegraded,
                providerDegraded,
                effectiveDegradedReason,
                effectiveDegraded ? 0.20 : 0.35,
                Instant.now()
        );
    }

    private List<Recommendation> buildRecommendations(
            ProviderCollectionResult collection,
            AnalysisMode analysisMode,
            AlertEvent alert,
            CandidateRootCause topCandidate
    ) {
        List<Recommendation> recommendations = new ArrayList<>();
        if (!collection.missingEvidence().isEmpty()) {
            recommendations.add(new Recommendation(
                    "P1",
                    "CHECK_PROVIDER",
                    "Inspect provider call logs and restore missing evidence sources before trusting the placeholder result.",
                    collection.missingEvidence().get(0).providerName()
            ));
        }
        recommendations.add(new Recommendation(
                analysisMode == AnalysisMode.RULE_BASED_DEGRADED ? "P1" : "P2",
                "FOLLOW_UP",
                topCandidate == null
                        ? "Continue with LLM and rule-fallback result generation to replace the current placeholder conclusion."
                        : "Continue with LLM and rule-fallback result generation after reviewing the highest-scored phase-5 rule evidence.",
                topCandidate == null || topCandidate.evidenceIds().isEmpty()
                        ? alert.serviceName()
                        : topCandidate.evidenceIds().get(0)
        ));
        return recommendations;
    }

    private List<EvidenceReference> buildEvidenceReferences(
            ProviderCollectionResult collection,
            RuleAnalysisEvidence ruleAnalysis
    ) {
        List<EvidenceReference> references = new ArrayList<>();
        references.add(new EvidenceReference(
                "provider.collection.window",
                "PROVIDER_COLLECTION",
                "Provider evidence was collected for the configured alarm window and persisted with call logs."
        ));
        if (collection.baselineEvidence().collected()) {
            references.add(new EvidenceReference(
                    "provider.collection.baseline",
                    "BASELINE",
                    "Historical baseline data was collected for the comparable time window."
            ));
        }
        if (collection.interfaceLogEvidence().collected()) {
            references.add(new EvidenceReference(
                    "provider.collection.interfaceLogs",
                    "INTERFACE_LOG",
                    "Interface-log samples were collected and used to derive trace ids."
            ));
        }
        if (collection.traceEvidence().collected()) {
            references.add(new EvidenceReference(
                    "provider.collection.traces",
                    "TRACE",
                    "Trace details were collected for sampled trace ids."
            ));
        }
        if (collection.runtimeLogEvidence().collected()) {
            references.add(new EvidenceReference(
                    "provider.collection.runtimeLogs",
                    "RUNTIME_LOG",
                    "Runtime WARN/ERROR samples were collected for sampled trace ids."
            ));
        }
        if (collection.metricEvidence().collected()) {
            references.add(new EvidenceReference(
                    "provider.collection.metrics",
                    "METRIC",
                    "Infrastructure metrics were collected for the service and mapped resources."
            ));
        }
        if (!collection.missingEvidence().isEmpty()) {
            references.add(new EvidenceReference(
                    "provider.collection.missing",
                    "MISSING_EVIDENCE",
                    "One or more provider calls failed, timed out, or returned partial evidence."
            ));
        }
        if (ruleAnalysis != null) {
            references.addAll(ruleAnalysis.evidenceRefs());
        }
        return references;
    }

    private List<String> buildInsufficientEvidence(
            ProviderCollectionResult collection,
            RuleAnalysisEvidence ruleAnalysis,
            CompressedEvidenceBundle compressedEvidence
    ) {
        List<String> insufficientEvidence = new ArrayList<>();
        if (collection.degradedReason() != null && !collection.degradedReason().isBlank()) {
            insufficientEvidence.add(collection.degradedReason());
        }
        if (compressedEvidence != null) {
            insufficientEvidence.addAll(compressedEvidence.insufficientEvidence());
        } else if (ruleAnalysis != null) {
            insufficientEvidence.addAll(ruleAnalysis.insufficientEvidence());
        }
        insufficientEvidence.add("LLM and rule-fallback result generation remain placeholder-backed in the current implementation.");
        return insufficientEvidence;
    }

    private String summarizeRuleStage(
            boolean providerDegraded,
            RuleAnalysisEvidence ruleAnalysis,
            String suffix
    ) {
        String prefix = providerDegraded
                ? "Provider collection completed with missing non-critical evidence."
                : "Provider collection completed successfully.";
        if (ruleAnalysis == null || ruleAnalysis.ruleFindings().isEmpty()) {
            return prefix + " " + suffix;
        }
        return prefix
                + " Phase-5 rule analysis generated findings: "
                + String.join(", ", ruleAnalysis.topFindingTypes(3))
                + ". "
                + suffix;
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
