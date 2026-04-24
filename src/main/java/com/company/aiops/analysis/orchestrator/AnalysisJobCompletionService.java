package com.company.aiops.analysis.orchestrator;

import com.company.aiops.analysis.model.AnalysisJob;
import com.company.aiops.analysis.model.AnalysisJobStatus;
import com.company.aiops.analysis.model.AnalysisMode;
import com.company.aiops.analysis.model.AnalysisResult;
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
                completionReason(analysisResult.analysisMode())
        );
    }

    private String completionReason(AnalysisMode analysisMode) {
        return switch (analysisMode) {
            case LLM_ASSISTED -> "placeholder LLM-assisted result persisted";
            case RULE_BASED_DEGRADED -> "placeholder degraded result persisted";
            case RULE_BASED_ONLY -> "placeholder rule-only result persisted";
        };
    }
}
