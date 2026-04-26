package com.company.aiops.analysis.query.model;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.analysis.job.AnalysisJobStatus;
import com.company.aiops.analysis.result.AnalysisResult;

import java.time.Instant;

/**
 * Internal read-model projection returned by the phase-9 query repository.
 *
 * <p>The projection keeps task metadata, alert dimensions, and the optional final result together
 * so the service can enrich the payload without leaking JDBC concerns into the controller.</p>
 */
public record AnalysisQueryProjection(
        String taskId,
        String alertId,
        AnalysisJobStatus status,
        AlertCategory category,
        String serviceName,
        String interfaceName,
        String cluster,
        String dedupeKey,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        String currentStep,
        String statusReason,
        String failureReason,
        AnalysisResult analysisResult
) {
}
