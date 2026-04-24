package com.company.aiops.analysis.orchestrator.worker;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges committed task creation events to the async worker.
 */
@Component
public class AnalysisJobExecutionScheduler {

    private final AnalysisJobWorker analysisJobWorker;

    public AnalysisJobExecutionScheduler(AnalysisJobWorker analysisJobWorker) {
        this.analysisJobWorker = analysisJobWorker;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTaskCreated(AnalysisJobExecutionRequestedEvent event) {
        analysisJobWorker.submit(event.taskId());
    }
}
