package com.company.aiops.analysis.orchestrator.worker;

import com.company.aiops.alert.model.AlertEvent;
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
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Async entrypoint that drives one task through the explicit lifecycle graph.
 *
 * <p>Stage 3 intentionally uses placeholder results so the system can validate orchestration,
 * optimistic locking, and audit logs without pretending that provider, rule, or LLM integrations
 * from later phases already exist.</p>
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

    public AnalysisJobWorker(
            AnalysisJobStateMachine analysisJobStateMachine,
            AnalysisJobCompletionService analysisJobCompletionService,
            AlertEventRepository alertEventRepository
    ) {
        this.analysisJobStateMachine = analysisJobStateMachine;
        this.analysisJobCompletionService = analysisJobCompletionService;
        this.alertEventRepository = alertEventRepository;
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

            analysisJobStateMachine.transition(
                    taskId,
                    AnalysisJobStatus.COLLECTING_EVIDENCE,
                    STEP_COLLECT,
                    "provider collection will be implemented in phase 4"
            );
            analysisJobStateMachine.transition(
                    taskId,
                    AnalysisJobStatus.RULE_ANALYZING,
                    STEP_RULE,
                    "rule analysis will be implemented in phase 5"
            );
            analysisJobStateMachine.transition(
                    taskId,
                    AnalysisJobStatus.COMPRESSING_EVIDENCE,
                    STEP_COMPRESS,
                    "evidence compression will be implemented in phase 6"
            );

            switch (scenario) {
                case RULE_BASED_ONLY -> completeRuleOnly(taskId, alert);
                case LLM_ASSISTED_SUCCESS -> completeLlmAssisted(taskId, alert);
                case LLM_FALLBACK_TIMEOUT -> completeLlmFallback(taskId, alert);
            }
        } catch (RuntimeException exception) {
            failIfPossible(taskId, exception);
            throw exception;
        }
    }

    private void completeRuleOnly(String taskId, AlertEvent alert) {
        analysisJobStateMachine.transition(
                taskId,
                AnalysisJobStatus.GENERATING_RESULT,
                STEP_GENERATE,
                "stage 3 runtime path generates a rule-only placeholder result"
        );
        analysisJobCompletionService.complete(taskId, buildPlaceholderResult(
                taskId,
                alert,
                AnalysisMode.RULE_BASED_ONLY,
                false,
                "STAGE3_PLACEHOLDER",
                false,
                null
        ));
    }

    private void completeLlmAssisted(String taskId, AlertEvent alert) {
        analysisJobStateMachine.transition(
                taskId,
                AnalysisJobStatus.LLM_ANALYZING,
                STEP_LLM,
                "simulate successful LLM analysis branch"
        );
        analysisJobStateMachine.transition(
                taskId,
                AnalysisJobStatus.GENERATING_RESULT,
                STEP_GENERATE,
                "simulated LLM output accepted"
        );
        analysisJobCompletionService.complete(taskId, buildPlaceholderResult(
                taskId,
                alert,
                AnalysisMode.LLM_ASSISTED,
                true,
                "SUCCESS",
                false,
                null
        ));
    }

    private void completeLlmFallback(String taskId, AlertEvent alert) {
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
                alert,
                AnalysisMode.RULE_BASED_DEGRADED,
                false,
                "TIMEOUT",
                true,
                "LLM integration is not implemented in phase 3, so the worker validated the fallback branch with a placeholder result."
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
            AlertEvent alert,
            AnalysisMode analysisMode,
            boolean llmUsed,
            String llmStatus,
            boolean degraded,
            String degradedReason
    ) {
        String evidenceId = "stage3:" + alert.alertId();
        String summary = switch (analysisMode) {
            case RULE_BASED_ONLY -> "Stage 3 lifecycle placeholder result generated without invoking LLM.";
            case LLM_ASSISTED -> "Stage 3 lifecycle placeholder result generated for the simulated LLM-assisted branch.";
            case RULE_BASED_DEGRADED -> "Stage 3 lifecycle placeholder result generated for the simulated LLM fallback branch.";
        };

        return new AnalysisResult(
                taskId,
                alert.alertId(),
                alert.category(),
                analysisMode,
                summary,
                new ImpactAssessment(
                        "UNKNOWN",
                        "This stage only validates lifecycle orchestration, so no real provider or rule evidence exists yet."
                ),
                List.of(new RootCauseHypothesis(
                        "PIPELINE_PLACEHOLDER",
                        "The task completed through the explicit state machine for %s/%s."
                                .formatted(alert.serviceName(), alert.interfaceName()),
                        degraded ? 0.25 : 0.30,
                        List.of(evidenceId),
                        "STATE_MACHINE",
                        "Phase 3 only implements orchestration and persistence; later phases replace this placeholder with real evidence-driven analysis."
                )),
                List.of(new Recommendation(
                        "P2",
                        "FOLLOW_UP",
                        "Continue with provider, rule, evidence-compression, and LLM phases to replace the placeholder result.",
                        alert.serviceName()
                )),
                List.of(new EvidenceReference(
                        evidenceId,
                        "STATE_MACHINE_PLACEHOLDER",
                        "Lifecycle steps completed and were persisted with optimistic locking."
                )),
                List.of(),
                List.of("Provider, rule, evidence-compression, and LLM implementations are pending later phases."),
                null,
                llmUsed,
                llmStatus,
                degraded,
                false,
                degradedReason,
                degraded ? 0.25 : 0.30,
                Instant.now()
        );
    }
}
