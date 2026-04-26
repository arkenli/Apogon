package com.company.aiops.analysis.orchestrator.worker;

import com.company.aiops.analysis.job.AnalysisJob;
import com.company.aiops.analysis.job.AnalysisJobStatus;
import com.company.aiops.analysis.orchestrator.state.AnalysisJobStateMachine;
import com.company.aiops.analysis.result.AnalysisMode;
import com.company.aiops.analysis.result.AnalysisResult;
import com.company.aiops.analysis.result.HistoricalReference;
import com.company.aiops.common.config.AiopsProperties;
import com.company.aiops.persistence.repository.AnalysisResultRepository;
import com.company.aiops.persistence.repository.AnalysisTaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Persists the final result and the terminal task status in the same transaction.
 *
 * <p>The completion stage also enriches newly generated results with the latest historical
 * reference outside the dedupe window before the JSON payload is stored. This keeps the persisted
 * result structurally aligned with what phase-9 query APIs return.</p>
 */
@Service
public class AnalysisJobCompletionService {

    private static final String STEP_COMPLETE = "analysis.complete";

    private final AiopsProperties aiopsProperties;
    private final AnalysisResultRepository analysisResultRepository;
    private final AnalysisTaskRepository analysisTaskRepository;
    private final AnalysisJobStateMachine analysisJobStateMachine;

    public AnalysisJobCompletionService(
            AiopsProperties aiopsProperties,
            AnalysisResultRepository analysisResultRepository,
            AnalysisTaskRepository analysisTaskRepository,
            AnalysisJobStateMachine analysisJobStateMachine
    ) {
        this.aiopsProperties = aiopsProperties;
        this.analysisResultRepository = analysisResultRepository;
        this.analysisTaskRepository = analysisTaskRepository;
        this.analysisJobStateMachine = analysisJobStateMachine;
    }

    @Transactional
    public AnalysisJob complete(String taskId, AnalysisResult analysisResult) {
        AnalysisResult persistedResult = enrichHistoricalReference(taskId, analysisResult);
        analysisResultRepository.save(persistedResult);

        AnalysisJobStatus terminalStatus = persistedResult.degraded()
                ? AnalysisJobStatus.DEGRADED_SUCCESS
                : AnalysisJobStatus.SUCCESS;

        return analysisJobStateMachine.transition(
                taskId,
                terminalStatus,
                STEP_COMPLETE,
                completionReason(persistedResult)
        );
    }

    private AnalysisResult enrichHistoricalReference(String taskId, AnalysisResult analysisResult) {
        if (analysisResult.historicalReference() != null) {
            return analysisResult;
        }
        return analysisTaskRepository.findByTaskId(taskId)
                .map(task -> lookupHistoricalReference(task.dedupeKey(), task.createdAt()))
                .flatMap(reference -> reference)
                .map(analysisResult::withHistoricalReference)
                .orElse(analysisResult);
    }

    private java.util.Optional<HistoricalReference> lookupHistoricalReference(String dedupeKey, Instant taskCreatedAt) {
        if (dedupeKey == null || dedupeKey.isBlank() || taskCreatedAt == null) {
            return java.util.Optional.empty();
        }
        Instant historicalSince = taskCreatedAt.minus(aiopsProperties.analysis().historicalReferenceLookback());
        Instant duplicateBefore = taskCreatedAt.minus(aiopsProperties.analysis().dedupeWindow());
        return analysisResultRepository.findLatestHistoricalReference(dedupeKey, historicalSince, duplicateBefore);
    }

    private String completionReason(AnalysisResult analysisResult) {
        return switch (analysisResult.analysisMode()) {
            case LLM_ASSISTED -> analysisResult.degraded()
                    ? "stage-8 LLM-assisted result persisted with partial provider evidence"
                    : "stage-8 LLM-assisted result persisted after prompt/validation completed";
            case RULE_BASED_DEGRADED -> "stage-7 degraded rule-based result persisted after provider or LLM fallback";
            case RULE_BASED_ONLY -> analysisResult.degraded()
                    ? "stage-7 rule-only result persisted with partial provider evidence"
                    : "stage-7 rule-only result persisted because LLM is disabled";
        };
    }
}
