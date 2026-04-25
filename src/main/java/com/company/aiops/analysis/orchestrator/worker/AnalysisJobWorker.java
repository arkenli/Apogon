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
import com.company.aiops.analysis.result.AnalysisMode;
import com.company.aiops.analysis.rule.RuleAnalysisService;
import com.company.aiops.analysis.rule.model.RuleAnalysisEvidence;
import com.company.aiops.common.config.AiopsProperties;
import com.company.aiops.common.exception.AiopsException;
import com.company.aiops.common.exception.ErrorCode;
import com.company.aiops.llm.service.LlmAnalysisException;
import com.company.aiops.llm.service.LlmAnalysisService;
import com.company.aiops.persistence.repository.AlertEventRepository;
import com.company.aiops.persistence.repository.EvidenceBundleRepository;
import com.company.aiops.provider.orchestrator.ProviderOrchestrator;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CompletableFuture;

/**
 * Async entrypoint that drives one task through the explicit lifecycle graph.
 *
 * <p>The worker keeps ownership of lifecycle transitions only. Provider collection, deterministic
 * rule analysis, stage-6 evidence compression, stage-8 LLM orchestration, and stage-7 fallback
 * result generation are delegated to dedicated packages so responsibilities stay isolated.</p>
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
    private final LlmAnalysisService llmAnalysisService;

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
            RuleBasedFallbackAnalyzer ruleBasedFallbackAnalyzer,
            LlmAnalysisService llmAnalysisService
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
        this.llmAnalysisService = llmAnalysisService;
    }

    /**
     * Safe runtime default used immediately after webhook ingestion.
     */
    @Async("analysisJobExecutor")
    public CompletableFuture<Void> submit(String taskId) {
        AnalysisWorkerScenario scenario = aiopsProperties.llm().enabled()
                ? AnalysisWorkerScenario.USE_CONFIGURED_LLM
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
                case USE_CONFIGURED_LLM -> completeConfiguredLlmPath(taskId, collection, ruleAnalysis, compressedEvidence);
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

    private void completeConfiguredLlmPath(
            String taskId,
            ProviderCollectionResult collection,
            RuleAnalysisEvidence ruleAnalysis,
            CompressedEvidenceBundle compressedEvidence
    ) {
        analysisJobStateMachine.transition(
                taskId,
                AnalysisJobStatus.LLM_ANALYZING,
                STEP_LLM,
                "build the prompt, call the configured LLM client, and validate the JSON response"
        );
        try {
            var result = llmAnalysisService.analyze(taskId, collection, compressedEvidence);
            analysisJobStateMachine.transition(
                    taskId,
                    AnalysisJobStatus.GENERATING_RESULT,
                    STEP_GENERATE,
                    "persist the validated LLM-assisted result"
            );
            analysisJobCompletionService.complete(taskId, result);
        } catch (LlmAnalysisException exception) {
            if (!aiopsProperties.llm().fallbackEnabled()) {
                throw exception;
            }
            analysisJobStateMachine.transition(
                    taskId,
                    AnalysisJobStatus.RULE_FALLBACK_ANALYZING,
                    STEP_RULE_FALLBACK,
                    "LLM failed with status %s: %s"
                            .formatted(exception.status().name(), defaultIfBlank(exception.getMessage(), "unknown LLM failure"))
            );
            analysisJobStateMachine.transition(
                    taskId,
                    AnalysisJobStatus.GENERATING_RESULT,
                    STEP_GENERATE,
                    "generate a degraded rule-based result after LLM failure"
            );
            analysisJobCompletionService.complete(taskId, ruleBasedFallbackAnalyzer.analyze(new RuleBasedAnalysisRequest(
                    taskId,
                    collection,
                    ruleAnalysis,
                    compressedEvidence,
                    AnalysisMode.RULE_BASED_DEGRADED,
                    exception.status().name(),
                    combineDegradedReason(collection.degradedReason(), exception)
            )));
        }
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

    private String combineDegradedReason(String providerReason, LlmAnalysisException exception) {
        String llmReason = "LLM analysis failed with status %s. Switched to deterministic fallback rules."
                .formatted(exception.status().name());
        if (providerReason == null || providerReason.isBlank()) {
            return llmReason;
        }
        return providerReason + " " + llmReason;
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
