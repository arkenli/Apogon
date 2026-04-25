package com.company.aiops.llm.client;

import java.util.Objects;

/**
 * One chat-style prompt message passed to the selected LLM provider.
 */
public record LlmMessage(
        String role,
        String content
) {

    public LlmMessage {
        role = Objects.requireNonNull(role, "role");
        content = Objects.requireNonNull(content, "content");
    }
}
