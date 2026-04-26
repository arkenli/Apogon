package com.company.aiops.persistence.repository;

import com.company.aiops.analysis.query.model.AnalysisQueryProjection;

import java.util.List;
import java.util.Optional;

/**
 * Read-only repository for phase-9 query APIs.
 *
 * <p>The interface centralizes the cross-table joins required to query tasks, results, alerts, and
 * duplicate-alert mappings without leaking SQL into the web/service layers.</p>
 */
public interface AnalysisQueryRepository {

    /**
     * Loads one task projection by task id.
     */
    Optional<AnalysisQueryProjection> findByTaskId(String taskId);

    /**
     * Resolves the latest task for the alert id. If the alert was merged into an existing task, the
     * original target task is returned.
     */
    Optional<AnalysisQueryProjection> findLatestByAlertId(String alertId);

    /**
     * Returns completed historical results for one service/interface pair ordered by generated time
     * descending.
     */
    List<AnalysisQueryProjection> findHistoryByServiceAndInterface(String serviceName, String interfaceName, int limit);
}
