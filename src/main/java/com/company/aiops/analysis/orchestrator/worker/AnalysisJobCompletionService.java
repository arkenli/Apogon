package com.company.aiops.analysis.orchestrator.worker;

import com.company.aiops.analysis.job.AnalysisJob;
import com.company.aiops.analysis.job.AnalysisJobStatus;
import com.company.aiops.analysis.orchestrator.state.AnalysisJobStateMachine;
import com.company.aiops.analysis.result.AnalysisMode;
import com.company.aiops.analysis.result.AnalysisResult;
import com.company.aiops.persistence.repository.AnalysisResultRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists the final result and the terminal task status in the same transaction.
 */
@Service
public class AnalysisJobCompletionService {

    private static final String STEP_COMPLETE = "analysis.complete";

    private final AnalysisResultRepository analysisResultRepository;
    private final AnalysisJobStateMachine analysisJobStateMachine;

    public AnalysisJobCompletionService(
            AnalysisResultRepository analysisResultRepository,
            AnalysisJobStateMachine analysisJobStateMachine
    ) {
        this.analysisResultRepository = analysisResultRepository;
        this.analysisJobStateMachine = analysisJobStateMachine;
    }

    @Transactional
    public AnalysisJob complete(String taskId, AnalysisResult analysisResult) {
        analysisResultRepository.save(analysisResult);

        AnalysisJobStatus terminalStatus = analysisResult.degraded()
                ? AnalysisJobStatus.DEGRADED_SUCCESS
                : AnalysisJobStatus.SUCCESS;

        return analysisJobStateMachine.transition(
                taskId,
                terminalStatus,
                STEP_COMPLETE,
                completionReason(analysisResult)
        );
    }

    private String completionReason(AnalysisResult analysisResult) {
        return switch (analysisResult.analysisMode()) {
            case LLM_ASSISTED -> "phase-6 compressed evidence persisted and placeholder LLM-assisted result generated";
            case RULE_BASED_DEGRADED -> "degraded result persisted after provider or LLM fallback branch";
            case RULE_BASED_ONLY -> analysisResult.degraded()
                    ? "phase-6 compressed evidence persisted with non-critical missing evidence and placeholder rule-only result generated"
                    : "phase-6 compressed evidence persisted and placeholder rule-only result generated";
        };
    }
}
