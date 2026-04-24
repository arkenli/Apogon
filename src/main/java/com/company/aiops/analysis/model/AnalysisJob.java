package com.company.aiops.analysis.model;

import com.company.aiops.alert.model.AlertCategory;

import java.time.Instant;

/**
 * Persistent task snapshot for one alert analysis lifecycle.
 */
public record AnalysisJob(
        String taskId,
        String alertId,
        AlertCategory category,
        String dedupeKey,
        AnalysisJobStatus status,
        String currentStep,
        String statusReason,
        int retryCount,
        long stateVersion,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        String failureReason
) {
}
