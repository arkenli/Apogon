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
     * Inserts or updates one analysis task snapshot.
     */
    void save(AnalysisJob analysisJob);

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
