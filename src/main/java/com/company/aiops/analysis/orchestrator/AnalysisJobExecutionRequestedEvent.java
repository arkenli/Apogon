package com.company.aiops.analysis.orchestrator;

/**
 * Published after a new analysis task is committed so the async worker never races the insert.
 */
public record AnalysisJobExecutionRequestedEvent(String taskId) {
}
