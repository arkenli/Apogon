package com.company.aiops.analysis;

import com.company.aiops.alert.AlertCategory;

import java.time.Instant;

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
