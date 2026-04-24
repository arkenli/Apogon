package com.company.aiops.analysis.orchestrator;

import com.company.aiops.analysis.model.AnalysisJob;
import com.company.aiops.analysis.model.AnalysisJobStateLog;
import com.company.aiops.analysis.model.AnalysisJobStatus;
import com.company.aiops.common.exception.AiopsException;
import com.company.aiops.common.exception.ErrorCode;
import com.company.aiops.persistence.repository.AnalysisJobStateLogRepository;
import com.company.aiops.persistence.repository.AnalysisTaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Default implementation of the explicit lifecycle graph described in the design document.
 *
 * <p>The state machine owns transition validation, optimistic locking, and audit-log writes so the
 * async worker cannot accidentally update the task row without leaving a trace.</p>
 */
@Service
public class DefaultAnalysisJobStateMachine implements AnalysisJobStateMachine {

    private static final Map<AnalysisJobStatus, Set<AnalysisJobStatus>> ALLOWED_TRANSITIONS =
            buildAllowedTransitions();

    private final AnalysisTaskRepository analysisTaskRepository;
    private final AnalysisJobStateLogRepository analysisJobStateLogRepository;

    public DefaultAnalysisJobStateMachine(
            AnalysisTaskRepository analysisTaskRepository,
            AnalysisJobStateLogRepository analysisJobStateLogRepository
    ) {
        this.analysisTaskRepository = analysisTaskRepository;
        this.analysisJobStateLogRepository = analysisJobStateLogRepository;
    }

    @Override
    @Transactional
    public AnalysisJob transition(String taskId, AnalysisJobStatus targetStatus, String stepName, String reason) {
        AnalysisJob current = loadTask(taskId);
        validateTransition(current, targetStatus);

        Instant now = Instant.now();
        AnalysisJob next = nextSnapshot(current, targetStatus, stepName, reason, now);
        boolean updated = analysisTaskRepository.updateState(next, current.stateVersion());
        if (!updated) {
            throw new AnalysisJobStateConflictException(taskId, current.stateVersion());
        }

        analysisJobStateLogRepository.save(new AnalysisJobStateLog(
                next.taskId(),
                next.alertId(),
                current.status(),
                next.status(),
                next.currentStep(),
                next.statusReason(),
                next.stateVersion(),
                now
        ));
        return next;
    }

    @Override
    @Transactional
    public AnalysisJob fail(String taskId, String stepName, String reason) {
        AnalysisJob current = loadTask(taskId);
        if (current.status().isTerminal()) {
            return current;
        }
        return transition(taskId, AnalysisJobStatus.FAILED, stepName, reason);
    }

    private AnalysisJob loadTask(String taskId) {
        return analysisTaskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new AiopsException(
                        ErrorCode.NOT_FOUND,
                        "analysis task not found: " + taskId
                ));
    }

    private void validateTransition(AnalysisJob current, AnalysisJobStatus targetStatus) {
        if (current.status() == targetStatus) {
            throw new IllegalAnalysisJobStateTransitionException(current.taskId(), current.status(), targetStatus);
        }
        Set<AnalysisJobStatus> allowedTargets = ALLOWED_TRANSITIONS.getOrDefault(current.status(), Set.of());
        if (!allowedTargets.contains(targetStatus)) {
            throw new IllegalAnalysisJobStateTransitionException(current.taskId(), current.status(), targetStatus);
        }
    }

    private AnalysisJob nextSnapshot(
            AnalysisJob current,
            AnalysisJobStatus targetStatus,
            String stepName,
            String reason,
            Instant now
    ) {
        Instant startedAt = current.startedAt();
        if (targetStatus == AnalysisJobStatus.RUNNING && startedAt == null) {
            startedAt = now;
        }

        Instant finishedAt = current.finishedAt();
        if (targetStatus.isTerminal()) {
            finishedAt = now;
        }

        String failureReason = targetStatus == AnalysisJobStatus.FAILED ? reason : null;

        return new AnalysisJob(
                current.taskId(),
                current.alertId(),
                current.category(),
                current.dedupeKey(),
                targetStatus,
                stepName,
                reason,
                current.retryCount(),
                current.stateVersion() + 1,
                current.createdAt(),
                startedAt,
                finishedAt,
                failureReason
        );
    }

    private static Map<AnalysisJobStatus, Set<AnalysisJobStatus>> buildAllowedTransitions() {
        EnumMap<AnalysisJobStatus, Set<AnalysisJobStatus>> transitions = new EnumMap<>(AnalysisJobStatus.class);
        transitions.put(AnalysisJobStatus.PENDING, EnumSet.of(
                AnalysisJobStatus.RUNNING,
                AnalysisJobStatus.FAILED,
                AnalysisJobStatus.SKIPPED_DUPLICATE
        ));
        transitions.put(AnalysisJobStatus.RUNNING, EnumSet.of(
                AnalysisJobStatus.COLLECTING_EVIDENCE,
                AnalysisJobStatus.FAILED
        ));
        transitions.put(AnalysisJobStatus.COLLECTING_EVIDENCE, EnumSet.of(
                AnalysisJobStatus.RULE_ANALYZING,
                AnalysisJobStatus.FAILED
        ));
        transitions.put(AnalysisJobStatus.RULE_ANALYZING, EnumSet.of(
                AnalysisJobStatus.COMPRESSING_EVIDENCE,
                AnalysisJobStatus.FAILED
        ));
        transitions.put(AnalysisJobStatus.COMPRESSING_EVIDENCE, EnumSet.of(
                AnalysisJobStatus.LLM_ANALYZING,
                AnalysisJobStatus.GENERATING_RESULT,
                AnalysisJobStatus.FAILED
        ));
        transitions.put(AnalysisJobStatus.LLM_ANALYZING, EnumSet.of(
                AnalysisJobStatus.GENERATING_RESULT,
                AnalysisJobStatus.RULE_FALLBACK_ANALYZING,
                AnalysisJobStatus.FAILED
        ));
        transitions.put(AnalysisJobStatus.RULE_FALLBACK_ANALYZING, EnumSet.of(
                AnalysisJobStatus.GENERATING_RESULT,
                AnalysisJobStatus.FAILED
        ));
        transitions.put(AnalysisJobStatus.GENERATING_RESULT, EnumSet.of(
                AnalysisJobStatus.SUCCESS,
                AnalysisJobStatus.DEGRADED_SUCCESS,
                AnalysisJobStatus.FAILED
        ));
        return transitions;
    }
}
