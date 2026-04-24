package com.company.aiops.persistence.repository;

import com.company.aiops.analysis.model.AnalysisJobStateLog;

import java.util.List;

/**
 * Persists state-machine audit logs for one analysis task.
 */
public interface AnalysisJobStateLogRepository {

    /**
     * Stores one state transition audit entry after the task row has been updated successfully.
     */
    void save(AnalysisJobStateLog stateLog);

    /**
     * Returns the full lifecycle history ordered by state version.
     */
    List<AnalysisJobStateLog> findByTaskId(String taskId);
}
