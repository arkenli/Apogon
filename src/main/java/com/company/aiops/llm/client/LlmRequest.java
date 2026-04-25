package com.company.aiops.llm.client;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Normalized request shape used across different LLM transports.
 */
public record LlmRequest(
        String model,
        List<LlmMessage> messages,
        double temperature,
        int maxOutputTokens,
        Duration timeout,
        String responseFormat
) {

    public LlmRequest {
        model = Objects.requireNonNull(model, "model");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        timeout = Objects.requireNonNull(timeout, "timeout");
        responseFormat = Objects.requireNonNull(responseFormat, "responseFormat");
    }
}
