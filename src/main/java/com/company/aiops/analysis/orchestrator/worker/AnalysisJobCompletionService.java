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
