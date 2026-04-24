package com.company.aiops.analysis.orchestrator.worker;

import com.company.aiops.alert.model.AlertEvent;
import com.company.aiops.analysis.context.AnalysisContext;
import com.company.aiops.analysis.context.AnalysisContextFactory;
import com.company.aiops.analysis.evidence.ProviderCollectionResult;
import com.company.aiops.analysis.job.AnalysisJob;
import com.company.aiops.analysis.job.AnalysisJobStatus;
import com.company.aiops.analysis.orchestrator.state.AnalysisJobStateMachine;
import com.company.aiops.analysis.result.AnalysisMode;
import com.company.aiops.analysis.result.AnalysisResult;
import com.company.aiops.analysis.result.EvidenceReference;
import com.company.aiops.analysis.result.ImpactAssessment;
import com.company.aiops.analysis.result.Recommendation;
import com.company.aiops.analysis.result.RootCauseHypothesis;
import com.company.aiops.common.exception.AiopsException;
import com.company.aiops.common.exception.ErrorCode;
import com.company.aiops.persistence.repository.AlertEventRepository;
import com.company.aiops.provider.orchestrator.ProviderOrchestrator;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Async entrypoint that drives one task through the explicit lifecycle graph.
 *
 * <p>The worker now performs real provider collection from phase 4. Rule analysis, evidence
 * compression, and LLM handling still use placeholder outputs so later phases can replace them
 * incrementally without rewriting lifecycle orchestration.</p>
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

    private final AnalysisJobStateMachine analysisJobStateMachine;
    private final AnalysisJobCompletionService analysisJobCompletionService;
    private final AlertEventRepository alertEventRepository;
    private final AnalysisContextFactory analysisContextFactory;
    private final ProviderOrchestrator providerOrchestrator;

    public AnalysisJobWorker(
            AnalysisJobStateMachine analysisJobStateMachine,
            AnalysisJobCompletionService analysisJobCompletionService,
            AlertEventRepository alertEventRepository,
            AnalysisContextFactory analysisContextFactory,
            ProviderOrchestrator providerOrchestrator
    ) {
        this.analysisJobStateMachine = analysisJobStateMachine;
        this.analysisJobCompletionService = analysisJobCompletionService;
        this.alertEventRepository = alertEventRepository;
        this.analysisContextFactory = analysisContextFactory;
        this.providerOrchestrator = providerOrchestrator;
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
                    "provider collection completed; rule analysis remains a phase 5 placeholder"
            );
            analysisJobStateMachine.transition(
                    taskId,
                    AnalysisJobStatus.COMPRESSING_EVIDENCE,
                    STEP_COMPRESS,
                    "provider-backed evidence compression remains a phase 6 placeholder"
            );

            switch (scenario) {
                case RULE_BASED_ONLY -> completeRuleOnly(taskId, collection);
                case LLM_ASSISTED_SUCCESS -> completeLlmAssisted(taskId, collection);
                case LLM_FALLBACK_TIMEOUT -> completeLlmFallback(taskId, collection);
            }
        } catch (RuntimeException exception) {
            failIfPossible(taskId, exception);
            throw exception;
        }
    }

    private void completeRuleOnly(String taskId, ProviderCollectionResult collection) {
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
                null
        ));
    }

    private void completeLlmAssisted(String taskId, ProviderCollectionResult collection) {
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
                null
        ));
    }

    private void completeLlmFallback(String taskId, ProviderCollectionResult collection) {
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
                "LLM integration is not implemented yet, so the worker validated the fallback branch with a provider-backed placeholder result."
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
                collection.degradedReason()
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

    private AnalysisResult buildPlaceholderResult(
            String taskId,
            ProviderCollectionResult collection,
            AnalysisMode analysisMode,
            boolean llmUsed,
            String llmStatus,
            boolean degraded,
            String degradedReason
    ) {
        AlertEvent alert = collection.context().alert();
        boolean providerDegraded = collection.degradedByProviderFailure();
        boolean effectiveDegraded = degraded || providerDegraded;
        String effectiveDegradedReason = degradedReason != null ? degradedReason : collection.degradedReason();
        List<EvidenceReference> evidenceRefs = buildEvidenceReferences(collection);
        List<String> primaryEvidenceIds = collection.missingEvidence().isEmpty()
                ? List.of("provider.collection.window")
                : List.of("provider.collection.missing");
        String summary = switch (analysisMode) {
            case RULE_BASED_ONLY -> providerDegraded
                    ? "Provider collection completed with missing non-critical evidence. Rule, evidence-compression, and LLM phases still emit placeholder output."
                    : "Provider collection completed successfully. Rule, evidence-compression, and LLM phases still emit placeholder output.";
            case LLM_ASSISTED -> providerDegraded
                    ? "Provider collection completed with missing evidence, and the worker simulated an LLM-assisted placeholder branch."
                    : "Provider collection completed successfully, and the worker simulated an LLM-assisted placeholder branch.";
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
                                : "Provider collection completed, but rule analysis and LLM reasoning are still placeholder-backed."
                ),
                List.of(new RootCauseHypothesis(
                        "UNKNOWN",
                        analysisMode == AnalysisMode.RULE_BASED_DEGRADED
                                ? defaultIfBlank(
                                effectiveDegradedReason,
                                "A degraded placeholder result was generated because key provider evidence was unavailable."
                        )
                                : "Provider collection finished for %s/%s, but later analysis phases still use placeholder logic."
                                .formatted(alert.serviceName(), alert.interfaceName()),
                        effectiveDegraded ? 0.20 : 0.35,
                        primaryEvidenceIds,
                        "RULE_ENGINE",
                        analysisMode == AnalysisMode.RULE_BASED_DEGRADED
                                ? "Critical evidence is missing, so the worker can only emit a degraded placeholder conclusion."
                                : "Provider evidence was collected, but later rule and LLM phases are not implemented yet."
                )),
                buildRecommendations(collection, analysisMode, alert),
                evidenceRefs,
                collection.missingEvidence(),
                buildInsufficientEvidence(collection),
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
            AlertEvent alert
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
                "Continue with rule analysis, evidence compression, and LLM integration to replace the placeholder conclusion.",
                alert.serviceName()
        ));
        return recommendations;
    }

    private List<EvidenceReference> buildEvidenceReferences(ProviderCollectionResult collection) {
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
        return references;
    }

    private List<String> buildInsufficientEvidence(ProviderCollectionResult collection) {
        List<String> insufficientEvidence = new ArrayList<>();
        if (collection.degradedReason() != null && !collection.degradedReason().isBlank()) {
            insufficientEvidence.add(collection.degradedReason());
        }
        insufficientEvidence.add("Rule analysis, evidence compression, and LLM integration remain placeholder-backed in the current implementation.");
        return insufficientEvidence;
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
