package com.company.aiops.analysis.orchestrator.worker;

import com.company.aiops.alert.model.AlertEvent;
import com.company.aiops.analysis.bundle.CompressedEvidenceBundle;
import com.company.aiops.analysis.bundle.EvidenceCompressor;
import com.company.aiops.analysis.bundle.StoredEvidenceBundle;
import com.company.aiops.analysis.context.AnalysisContext;
import com.company.aiops.analysis.context.AnalysisContextFactory;
import com.company.aiops.analysis.evidence.ProviderCollectionResult;
import com.company.aiops.analysis.fallback.RuleBasedAnalysisRequest;
import com.company.aiops.analysis.fallback.RuleBasedFallbackAnalyzer;
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
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Async entrypoint that drives one task through the explicit lifecycle graph.
 *
 * <p>The worker performs real provider collection, deterministic rule analysis, phase-6 evidence
 * compression, and stage-7 rule-based result generation. Only the simulated
 * {@code LLM_ASSISTED_SUCCESS} branch remains a test-only placeholder until stage 8 lands.</p>
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
    private final RuleBasedFallbackAnalyzer ruleBasedFallbackAnalyzer;

    public AnalysisJobWorker(
            AiopsProperties aiopsProperties,
            AnalysisJobStateMachine analysisJobStateMachine,
            AnalysisJobCompletionService analysisJobCompletionService,
            AlertEventRepository alertEventRepository,
            AnalysisContextFactory analysisContextFactory,
            ProviderOrchestrator providerOrchestrator,
            RuleAnalysisService ruleAnalysisService,
            EvidenceCompressor evidenceCompressor,
            EvidenceBundleRepository evidenceBundleRepository,
            RuleBasedFallbackAnalyzer ruleBasedFallbackAnalyzer
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
        this.ruleBasedFallbackAnalyzer = ruleBasedFallbackAnalyzer;
    }

    /**
     * Safe runtime default used immediately after webhook ingestion.
     */
    @Async("analysisJobExecutor")
    public CompletableFuture<Void> submit(String taskId) {
        AnalysisWorkerScenario scenario = aiopsProperties.llm().enabled()
                ? AnalysisWorkerScenario.LLM_FALLBACK_TIMEOUT
                : AnalysisWorkerScenario.RULE_BASED_ONLY;
        processTask(taskId, scenario);
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
                "generate a rule-only result because LLM is disabled"
        );
        analysisJobCompletionService.complete(taskId, ruleBasedFallbackAnalyzer.analyze(new RuleBasedAnalysisRequest(
                taskId,
                collection,
                ruleAnalysis,
                compressedEvidence,
                AnalysisMode.RULE_BASED_ONLY,
                "DISABLED",
                collection.degradedReason()
        )));
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
                "generate a simulated LLM-assisted result for lifecycle tests"
        );
        analysisJobCompletionService.complete(taskId, buildSimulatedLlmAssistedResult(
                taskId,
                collection,
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
                "generate degraded rule-based result after LLM timeout"
        );
        analysisJobCompletionService.complete(taskId, ruleBasedFallbackAnalyzer.analyze(new RuleBasedAnalysisRequest(
                taskId,
                collection,
                ruleAnalysis,
                compressedEvidence,
                AnalysisMode.RULE_BASED_DEGRADED,
                "TIMEOUT",
                "LLM timed out, so the final result was generated by the stage-7 deterministic fallback rules."
        )));
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
                "generate a degraded rule-based result because critical provider evidence is missing"
        );
        analysisJobCompletionService.complete(taskId, ruleBasedFallbackAnalyzer.analyze(new RuleBasedAnalysisRequest(
                taskId,
                collection,
                null,
                null,
                AnalysisMode.RULE_BASED_DEGRADED,
                "SKIPPED_BY_PROVIDER_FAILURE",
                collection.degradedReason()
        )));
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

    /**
     * Stage-8 LLM integration is not implemented yet, but lifecycle tests still need a successful
     * {@code LLM_ASSISTED} branch. The branch stays explicitly marked as simulated so it cannot be
     * mistaken for a real model-backed conclusion.
     */
    private AnalysisResult buildSimulatedLlmAssistedResult(
            String taskId,
            ProviderCollectionResult collection,
            RuleAnalysisEvidence ruleAnalysis,
            CompressedEvidenceBundle compressedEvidence
    ) {
        AlertEvent alert = collection.context().alert();
        CandidateRootCause topCandidate = ruleAnalysis.topCandidateRootCause().orElse(null);
        List<String> evidenceIds = topCandidate != null && !topCandidate.evidenceIds().isEmpty()
                ? topCandidate.evidenceIds()
                : compressedEvidence.evidenceRefs().stream()
                .map(EvidenceReference::evidenceId)
                .limit(2)
                .toList();
        if (evidenceIds.isEmpty()) {
            evidenceIds = List.of("alert.context.1");
        }
        boolean degraded = collection.degradedByProviderFailure();
        String degradedReason = collection.degradedReason();

        return new AnalysisResult(
                taskId,
                alert.alertId(),
                alert.category(),
                AnalysisMode.LLM_ASSISTED,
                topCandidate == null
                        ? "Simulated LLM-assisted branch completed for lifecycle tests, but no dominant deterministic cause was scored."
                        : "Simulated LLM-assisted branch merged deterministic evidence around %s for lifecycle tests."
                        .formatted(topCandidate.causeType()),
                new ImpactAssessment(
                        "UNKNOWN",
                        "This branch remains a stage-8 simulation and is only used to validate the success lifecycle."
                ),
                List.of(new RootCauseHypothesis(
                        topCandidate == null ? "SIMULATED_LLM_RESULT" : topCandidate.causeType(),
                        topCandidate == null
                                ? "No dominant deterministic cause was available, so the simulated branch keeps the result generic."
                                : "Simulated LLM-assisted branch reused deterministic evidence around %s."
                                .formatted(topCandidate.causeType()),
                        topCandidate == null ? 0.40 : topCandidate.confidence(),
                        evidenceIds,
                        "MERGED",
                        topCandidate == null
                                ? "Stage-8 LLM integration is not implemented yet; this lifecycle branch only proves the happy-path state transition."
                                : defaultIfBlank(
                                topCandidate.reasoning(),
                                "Stage-8 LLM integration is not implemented yet; this lifecycle branch only proves the happy-path state transition."
                        )
                )),
                List.of(new Recommendation(
                        "P2",
                        "MANUAL_CONFIRMATION",
                        "Treat this result as a lifecycle-test artifact until stage-8 LLM integration is implemented.",
                        alert.interfaceName()
                )),
                compressedEvidence.evidenceRefs(),
                compressedEvidence.missingEvidence(),
                compressedEvidence.insufficientEvidence(),
                null,
                true,
                "SUCCESS",
                degraded,
                degraded,
                degradedReason,
                null,
                degraded ? 0.35 : topCandidate == null ? 0.45 : topCandidate.confidence(),
                Instant.now()
        );
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
