package com.company.aiops.persistence.repository;

import com.company.aiops.analysis.model.AnalysisJob;
import com.company.aiops.analysis.model.AnalysisJobStatus;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

/**
 * Stores task-level state for analysis job lifecycle management.
 */
public interface AnalysisTaskRepository {

    /**
     * Inserts or updates one analysis task snapshot. This is used for initial task creation and
     * test data setup.
     */
    void save(AnalysisJob analysisJob);

    /**
     * Updates lifecycle fields guarded by {@code task_id + state_version}. This is the only write
     * path that the explicit state machine should use after task creation.
     *
     * @return {@code true} when exactly one row was updated; {@code false} when the optimistic-lock
     * check failed and the caller must reload the task state.
     */
    boolean updateState(AnalysisJob analysisJob, long expectedStateVersion);

    /**
     * Loads a task by its generated task id.
     */
    Optional<AnalysisJob> findByTaskId(String taskId);

    /**
     * Finds the newest task inside the dedupe window that is still relevant for reuse.
     */
    Optional<AnalysisJob> findLatestByDedupeKeyAndStatusesSince(
            String dedupeKey,
            Collection<AnalysisJobStatus> statuses,
            Instant createdSince
    );
}
