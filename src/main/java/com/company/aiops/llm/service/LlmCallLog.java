package com.company.aiops.llm.service;

import com.company.aiops.llm.client.LlmCallStatus;

import java.time.Duration;
import java.time.Instant;

/**
 * Audit snapshot persisted into {@code aiops_llm_call_log} for each model-call attempt.
 */
public record LlmCallLog(
        String taskId,
        String alertId,
        String provider,
        String modelName,
        String requestJson,
        String responseText,
        Integer inputTokens,
        Integer outputTokens,
        Duration latency,
        LlmCallStatus callStatus,
        String errorMessage,
        Instant expireAt,
        Instant createdAt
) {
}
