package com.company.aiops.analysis.query.model;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.analysis.job.AnalysisJobStatus;

import java.time.Instant;

/**
 * Public payload for single-task and historical analysis lookups.
 */
public record AnalysisTaskQueryResponse(
        String taskId,
        String alertId,
        AnalysisJobStatus status,
        AlertCategory category,
        String serviceName,
        String interfaceName,
        String cluster,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        String currentStep,
        String statusReason,
        String failureReason,
        AnalysisResultView result
) {
}
