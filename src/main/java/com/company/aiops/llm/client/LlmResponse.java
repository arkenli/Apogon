package com.company.aiops.llm.client;

import java.time.Duration;
import java.util.Objects;

/**
 * Raw model output plus provider-side usage metadata required by audit logging.
 */
public record LlmResponse(
        String content,
        String model,
        int inputTokens,
        int outputTokens,
        Duration latency,
        String finishReason
) {

    public LlmResponse {
        content = Objects.requireNonNull(content, "content");
        model = Objects.requireNonNull(model, "model");
        latency = Objects.requireNonNull(latency, "latency");
        finishReason = Objects.requireNonNull(finishReason, "finishReason");
    }
}
